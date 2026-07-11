// RepoLens Tauri backend — PTY terminal bridge + file-watch bridge.
// greet() example command has been removed; all window communication goes through the
// term_* / repo_watch_* commands below or via the frontend's HTTP calls to the Spring Boot backend.
use notify::{Config as NotifyConfig, RecommendedWatcher, RecursiveMode, Watcher};
use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::sync::{mpsc, Mutex};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, State};

struct PtySession {
    writer: Box<dyn Write + Send>,
    master: Box<dyn portable_pty::MasterPty + Send>,
    child: Box<dyn portable_pty::Child + Send + Sync>,
}

#[derive(Default)]
struct PtyState(Mutex<HashMap<u32, PtySession>>);

/// Acquire a (potentially poisoned) Mutex guard.
/// In a PTY scenario a panicking thread leaves the data in a consistent-enough state
/// (the PTY handle is still valid), so we recover from poison rather than propagating.
macro_rules! lock_state {
    ($state:expr) => {
        $state.0.lock().unwrap_or_else(|p| p.into_inner())
    };
}

#[tauri::command]
fn term_spawn(
    app: AppHandle,
    state: State<PtyState>,
    id: u32,
    cwd: Option<String>,
    program: Option<String>,
    args: Option<Vec<String>>,
    cols: u16,
    rows: u16,
) -> Result<(), String> {
    let pty = native_pty_system()
        .openpty(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| e.to_string())?;
    let default_shell = if cfg!(windows) { "powershell" } else { "zsh" };
    let prog = program.as_deref().unwrap_or(default_shell);
    let mut cmd = CommandBuilder::new(prog);
    if let Some(arg_list) = args {
        cmd.args(arg_list);
    }
    if let Some(dir) = cwd.filter(|d| std::path::Path::new(d).is_dir()) {
        cmd.cwd(dir);
    }
    // Extend PATH so claude CLI and other npm-global binaries can be found in PTY.
    let home = std::env::var("HOME").unwrap_or_default();
    let npm_global = if home.is_empty() {
        String::new()
    } else {
        format!("{}/.npm-global/bin:", home)
    };
    let current_path = std::env::var("PATH").unwrap_or_default();
    let extra = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin";
    // 避免尾随冒号把当前目录并入 PATH（CWE-427）：仅当继承的 PATH 非空时才拼接它。
    let new_path = if current_path.is_empty() {
        format!("{npm_global}{extra}")
    } else {
        format!("{npm_global}{extra}:{current_path}")
    };
    cmd.env("PATH", new_path);
    let child = pty.slave.spawn_command(cmd).map_err(|e| e.to_string())?;
    let mut reader = pty.master.try_clone_reader().map_err(|e| e.to_string())?;
    let writer = pty.master.take_writer().map_err(|e| e.to_string())?;

    let app_clone = app.clone();
    std::thread::spawn(move || {
        let mut buf = [0u8; 4096];
        loop {
            match reader.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    let chunk = String::from_utf8_lossy(&buf[..n]).to_string();
                    let _ = app_clone.emit(&format!("term-out-{id}"), chunk);
                }
            }
        }
        let _ = app_clone.emit(&format!("term-out-{id}"), "\r\n[进程已退出]\r\n".to_string());
    });

    lock_state!(state).insert(id, PtySession { writer, master: pty.master, child });
    Ok(())
}

#[tauri::command]
fn term_write(state: State<PtyState>, id: u32, data: String) -> Result<(), String> {
    if let Some(s) = lock_state!(state).get_mut(&id) {
        s.writer.write_all(data.as_bytes()).map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
fn term_resize(state: State<PtyState>, id: u32, cols: u16, rows: u16) -> Result<(), String> {
    if let Some(s) = lock_state!(state).get_mut(&id) {
        s.master
            .resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
fn term_kill(state: State<PtyState>, id: u32) {
    if let Some(mut s) = lock_state!(state).remove(&id) {
        let _ = s.child.kill();
    }
}

// ─────────────────────────────────────────────────────────────
//  File-watch state
// ─────────────────────────────────────────────────────────────

/// Returns true when `path_str` should be suppressed (not forwarded to the
/// frontend).  Mirrors the ignore rules documented in the CC-3 design spec.
fn ignore_path(path_str: &str) -> bool {
    // Normalise to forward slashes for uniform matching on all platforms.
    let p = path_str.replace('\\', "/");

    let noisy_dirs = [".git", "node_modules", "target", "dist", ".idea"];
    for dir in noisy_dirs {
        // File/directory somewhere inside the noisy directory.
        if p.contains(&format!("/{dir}/")) {
            return true;
        }
        // The noisy directory itself is the event target.
        if p.ends_with(&format!("/{dir}")) {
            return true;
        }
        // Root-relative path (no leading slash), e.g. ".git/HEAD".
        if p == dir || p.starts_with(&format!("{dir}/")) {
            return true;
        }
    }

    // Ignore .DS_Store anywhere in the path.
    if let Some(basename) = p.split('/').next_back() {
        if basename == ".DS_Store" {
            return true;
        }
    }

    false
}

/// One active file-watcher registered by `repo_watch_start`.
struct WatchHandle {
    /// Must stay alive — dropping it stops the OS-level watch.
    _watcher: RecommendedWatcher,
    /// One-shot stop signal for the debounce background thread.
    stop_tx: mpsc::SyncSender<()>,
}

#[derive(Default)]
struct WatchState(Mutex<HashMap<u32, WatchHandle>>);

/// Start watching `path` recursively.  Debounces events for ~300 ms then
/// emits `repo-file-changed` with `{ watchId, paths: string[] }`.
///
/// If a watcher for `watch_id` is already running it is stopped first.
#[tauri::command]
fn repo_watch_start(
    app: AppHandle,
    state: State<WatchState>,
    watch_id: u32,
    path: String,
) -> Result<(), String> {
    // Remove (and thus stop) any previous watcher for this id.
    lock_state!(state).remove(&watch_id);

    let (event_tx, event_rx) = mpsc::channel::<notify::Result<notify::Event>>();
    let mut watcher = RecommendedWatcher::new(event_tx, NotifyConfig::default())
        .map_err(|e| e.to_string())?;
    watcher
        .watch(std::path::Path::new(&path), RecursiveMode::Recursive)
        .map_err(|e| e.to_string())?;

    let (stop_tx, stop_rx) = mpsc::sync_channel::<()>(1);
    let app_clone = app.clone();

    std::thread::spawn(move || {
        const DEBOUNCE: Duration = Duration::from_millis(300);
        const POLL: Duration = Duration::from_millis(30);
        const MAX_PATHS: usize = 50;

        let mut pending: Vec<String> = Vec::new();
        let mut deadline: Option<Instant> = None;

        loop {
            // Check stop signal without blocking.
            if stop_rx.try_recv().is_ok() {
                break;
            }

            // Compute how long to block in recv_timeout.
            let timeout = match deadline {
                Some(d) => d
                    .saturating_duration_since(Instant::now())
                    .min(POLL),
                None => POLL,
            };

            match event_rx.recv_timeout(timeout) {
                Ok(Ok(event)) => {
                    for p in event.paths {
                        let s = p.to_string_lossy().to_string();
                        if !ignore_path(&s) && !pending.contains(&s) {
                            pending.push(s);
                        }
                    }
                    if !pending.is_empty() {
                        // Extend (or reset) the debounce window.
                        deadline = Some(Instant::now() + DEBOUNCE);
                    }
                }
                Ok(Err(_)) | Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            }

            // Emit when the debounce window has expired.
            if let Some(d) = deadline {
                if Instant::now() >= d && !pending.is_empty() {
                    pending.truncate(MAX_PATHS);
                    let payload = serde_json::json!({
                        "watchId": watch_id,
                        "paths": pending,
                    });
                    let _ = app_clone.emit("repo-file-changed", payload);
                    pending.clear();
                    deadline = None;
                }
            }
        }
    });

    lock_state!(state).insert(watch_id, WatchHandle { _watcher: watcher, stop_tx });
    Ok(())
}

/// Stop a previously-started file watcher.  Silently succeeds if `watch_id`
/// was never started.
#[tauri::command]
fn repo_watch_stop(state: State<WatchState>, watch_id: u32) {
    if let Some(handle) = lock_state!(state).remove(&watch_id) {
        // Signal the background debounce thread to exit cleanly.
        let _ = handle.stop_tx.try_send(());
        // `handle._watcher` is dropped here, which detaches the OS watch.
    }
}

// ─────────────────────────────────────────────────────────────

/// Read a text file at `path`, verifying it resides inside `base_dir`.
///
/// Security:
///   - Both paths are canonicalized before comparison, defeating `../` traversal
///     and symlink escapes outside the project tree.
///   - `base_dir` must be the repo's real directory (realDir from the frontend).
#[tauri::command]
fn read_text_file(path: String, base_dir: String) -> Result<String, String> {
    let base = std::path::Path::new(&base_dir)
        .canonicalize()
        .map_err(|e| format!("invalid base_dir: {e}"))?;
    let target = std::path::Path::new(&path)
        .canonicalize()
        .map_err(|e| format!("cannot resolve path: {e}"))?;
    if !target.starts_with(&base) {
        return Err("path is outside the project directory".to_string());
    }
    std::fs::read_to_string(&target).map_err(|e| e.to_string())
}

/// Write text content to a file at `path`, restricted to `base_dir`.
///
/// Used by the frontend to write `.mcp.json` into the repo's real directory
/// when activating the Claude Code engine for that project.
///
/// Security:
///   - `base_dir` is canonicalized first (must exist).
///   - `path` is resolved relative to `base_dir` and canonicalized
///     after any existing parent is found (for new files the nearest
///     existing ancestor is checked).
///   - The resolved path must start_with the canonicalized `base_dir`.
///   - Parent directories are created automatically (mkdir -p).
#[tauri::command]
fn write_text_file(path: String, base_dir: String, content: String) -> Result<(), String> {
    let base = std::path::Path::new(&base_dir)
        .canonicalize()
        .map_err(|e| format!("invalid base_dir: {e}"))?;

    // Resolve target path lexically first
    let raw_target = base.join(&path);
    let normalized = raw_target
        .components()
        .fold(std::path::PathBuf::new(), |mut acc, c| {
            match c {
                std::path::Component::ParentDir => { acc.pop(); }
                std::path::Component::CurDir => {}
                other => acc.push(other),
            }
            acc
        });

    // Lexical containment check
    if !normalized.starts_with(&base) {
        return Err("path is outside the project directory".to_string());
    }

    // Symlink-escape check: find nearest existing ancestor and verify its real path
    let mut ancestor = normalized.parent().map(|p| p.to_path_buf());
    while let Some(ref a) = ancestor {
        if a.exists() {
            let real_ancestor = a.canonicalize()
                .map_err(|e| format!("cannot resolve ancestor: {e}"))?;
            if !real_ancestor.starts_with(&base) {
                return Err("path is outside the project directory".to_string());
            }
            break;
        }
        ancestor = a.parent().map(|p| p.to_path_buf());
    }

    // Final-component symlink check: if the target itself already exists as a symlink,
    // reject — writing through it would follow the link and could land outside base_dir.
    if let Ok(meta) = std::fs::symlink_metadata(&normalized) {
        if meta.file_type().is_symlink() {
            return Err("target path is a symlink — refusing to write".to_string());
        }
    }

    // Create parent directories and write
    if let Some(parent) = normalized.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("cannot create dirs: {e}"))?;
    }
    std::fs::write(&normalized, content).map_err(|e| e.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(PtyState::default())
        .manage(WatchState::default())
        .invoke_handler(tauri::generate_handler![
            term_spawn,
            term_write,
            term_resize,
            term_kill,
            read_text_file,
            write_text_file,
            repo_watch_start,
            repo_watch_stop
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
