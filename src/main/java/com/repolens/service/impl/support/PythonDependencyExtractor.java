package com.repolens.service.impl.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python 依赖提取器。
 * <ul>
 *   <li><b>清单级（MANIFEST）</b>：requirements.txt / pyproject.toml 中新增依赖行。</li>
 *   <li><b>import 级（IMPORT）</b>：.py 源码 {@code import X} / {@code from X import} 顶层模块，
 *       过滤 stdlib + 相对导入，内置 ~50 条「模块名 → PyPI 项目名」别名映射。</li>
 * </ul>
 */
@Slf4j
@Component
public class PythonDependencyExtractor implements DependencyExtractor {

    /** Python 常见 stdlib 模块名（快照，非完整版，覆盖常见误报源）。 */
    static final Set<String> STDLIB_MODULES = Set.of(
            "__future__", "_thread", "abc", "aifc", "argparse", "array", "ast", "asynchat",
            "asyncio", "asyncore", "atexit", "audioop", "base64", "bdb", "binascii",
            "binhex", "bisect", "builtins", "bz2", "calendar", "cgi", "cgitb",
            "chunk", "cmath", "cmd", "code", "codecs", "codeop", "collections",
            "colorsys", "compileall", "concurrent", "configparser", "contextlib",
            "contextvars", "copy", "copyreg", "cProfile", "csv", "ctypes",
            "curses", "dataclasses", "datetime", "dbm", "decimal", "difflib",
            "dis", "distutils", "doctest", "email", "encodings", "enum",
            "errno", "faulthandler", "fcntl", "filecmp", "fileinput",
            "fnmatch", "fractions", "ftplib", "functools", "gc", "getopt",
            "getpass", "gettext", "glob", "grp", "gzip", "hashlib", "heapq",
            "hmac", "html", "http", "idlelib", "imaplib", "imghdr", "imp",
            "importlib", "inspect", "io", "ipaddress", "itertools", "json",
            "keyword", "lib2to3", "linecache", "locale", "logging", "lzma",
            "mailbox", "marshal", "math", "mimetypes", "mmap", "modulefinder",
            "multiprocessing", "netrc", "nis", "nntplib", "numbers", "operator",
            "optparse", "os", "ossaudiodev", "pathlib", "pdb", "pickle",
            "pickletools", "pipes", "pkgutil", "platform", "plistlib", "poplib",
            "posix", "posixpath", "pprint", "profile", "pstats", "pty", "pwd",
            "py_compile", "pyclbr", "pydoc", "queue", "quopri", "random",
            "re", "readline", "reprlib", "resource", "rlcompleter", "runpy",
            "sched", "secrets", "select", "selectors", "shelve", "shlex",
            "shutil", "signal", "site", "smtpd", "smtplib", "sndhdr",
            "socket", "socketserver", "spwd", "sqlite3", "sre_compile",
            "sre_constants", "sre_parse", "ssl", "stat", "statistics",
            "string", "stringprep", "struct", "subprocess", "sunau",
            "symtable", "sys", "sysconfig", "syslog", "tabnanny", "tarfile",
            "telnetlib", "tempfile", "termios", "test", "textwrap", "threading",
            "time", "timeit", "tkinter", "token", "tokenize", "tomllib",
            "trace", "traceback", "tracemalloc", "tty", "turtle", "turtledemo",
            "types", "typing", "unicodedata", "unittest", "urllib", "uu",
            "uuid", "venv", "warnings", "wave", "weakref", "webbrowser",
            "wsgiref", "xdrlib", "xml", "xmlrpc", "zipapp", "zipfile",
            "zipimport", "zlib", "zoneinfo"
    );

    /**
     * 模块名 → PyPI 项目名 别名映射（降低 import 级误报）。
     * 当 import 的模块名与 PyPI 项目名不同时，用 PyPI 项目名查 registry。
     */
    static final Map<String, String> MODULE_TO_PYPI = Map.ofEntries(
            Map.entry("yaml", "PyYAML"),
            Map.entry("cv2", "opencv-python"),
            Map.entry("sklearn", "scikit-learn"),
            Map.entry("PIL", "Pillow"),
            Map.entry("bs4", "beautifulsoup4"),
            Map.entry("pil", "Pillow"),
            Map.entry("wx", "wxPython"),
            Map.entry("gi", "PyGObject"),
            Map.entry("dotenv", "python-dotenv"),
            Map.entry("attr", "attrs"),
            Map.entry("MySQLdb", "MySQL-python"),
            Map.entry("MySQLdb".toLowerCase(Locale.ROOT), "MySQL-python"),
            Map.entry("pymysql", "PyMySQL"),
            Map.entry("psycopg2", "psycopg2-binary"),
            Map.entry("cryptography", "cryptography"),
            Map.entry("jwt", "PyJWT"),
            Map.entry("nacl", "PyNaCl"),
            Map.entry("skimage", "scikit-image"),
            Map.entry("dateutil", "python-dateutil"),
            Map.entry("Crypto", "pycryptodome"),
            Map.entry("crypto", "pycryptodome"),
            Map.entry("magic", "python-magic"),
            Map.entry("serial", "pyserial"),
            Map.entry("usb", "pyusb"),
            Map.entry("docx", "python-docx"),
            Map.entry("pptx", "python-pptx"),
            Map.entry("xlrd", "xlrd"),
            Map.entry("xlwt", "xlwt"),
            Map.entry("openpyxl", "openpyxl"),
            Map.entry("fitz", "PyMuPDF"),
            Map.entry("gtk", "PyGObject"),
            Map.entry("lxml", "lxml"),
            Map.entry("aiofiles", "aiofiles"),
            Map.entry("httpx", "httpx"),
            Map.entry("ujson", "ujson"),
            Map.entry("orjson", "orjson"),
            Map.entry("msgpack", "msgpack"),
            Map.entry("toml", "toml"),
            Map.entry("boto3", "boto3"),
            Map.entry("botocore", "botocore"),
            Map.entry("paramiko", "paramiko"),
            Map.entry("fabric", "fabric"),
            Map.entry("invoke", "invoke"),
            Map.entry("jinja2", "Jinja2"),
            Map.entry("markupsafe", "MarkupSafe"),
            Map.entry("werkzeug", "Werkzeug"),
            Map.entry("sqlalchemy", "SQLAlchemy"),
            Map.entry("alembic", "alembic"),
            Map.entry("peewee", "peewee"),
            Map.entry("tortoise", "tortoise-orm")
    );

    /** 匹配 `import X` 或 `from X.Y import Z`，捕获顶层模块名。 */
    private static final Pattern IMPORT_STMT = Pattern.compile(
            "^\\s*(?:import\\s+(\\w+)|from\\s+(\\w+)\\s+import)",
            Pattern.MULTILINE
    );

    /** requirements.txt 行正则：pkg / pkg==ver / pkg>=ver / pkg[extra] 等，剥离注释/空行/条件。 */
    private static final Pattern REQ_LINE = Pattern.compile(
            "^\\s*([A-Za-z0-9_\\-\\.]+(?:\\[[A-Za-z0-9_,]+\\])?)\\s*(?:[><=!~^][^;#\\s]*)?",
            Pattern.MULTILINE
    );

    /** pyproject.toml dependencies 行（简单正则，覆盖常见格式）。 */
    private static final Pattern PYPROJECT_DEP = Pattern.compile(
            "^\\s*[\"']?([A-Za-z0-9_\\-\\.]+)(?:\\[[A-Za-z0-9_,]+\\])?[\"']?\\s*(?:=|>|<|~|\\^|,|\")",
            Pattern.MULTILINE
    );

    @Override
    public boolean supports(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase(Locale.ROOT);
        String name = lower.contains("/")
                ? lower.substring(lower.lastIndexOf('/') + 1)
                : lower;
        return name.endsWith(".py")
                || name.equals("requirements.txt")
                || name.equals("pyproject.toml")
                || name.startsWith("requirements") && name.endsWith(".txt");
    }

    @Override
    public List<ExtractedDep> extractAdded(String filePath, String oldContent, String newContent) {
        if (filePath == null || newContent == null) return List.of();
        String lower = filePath.toLowerCase(Locale.ROOT);
        String name = lower.contains("/")
                ? lower.substring(lower.lastIndexOf('/') + 1)
                : lower;

        if (name.equals("requirements.txt") || (name.startsWith("requirements") && name.endsWith(".txt"))) {
            return extractRequirementsTxtAdded(filePath, oldContent, newContent);
        } else if (name.equals("pyproject.toml")) {
            return extractPyprojectTomlAdded(filePath, oldContent, newContent);
        } else {
            return extractSourceAdded(filePath, oldContent, newContent);
        }
    }

    // ─────────────────────────── requirements.txt ────────────────────────────

    private List<ExtractedDep> extractRequirementsTxtAdded(String filePath, String old, String neu) {
        Set<String> oldPkgs = parseReqLines(old == null ? "" : old);
        Set<String> newPkgsMap = new LinkedHashSet<>();
        List<String[]> newEntries = parseReqLinesWithVersion(neu);

        List<ExtractedDep> result = new ArrayList<>();
        for (String[] entry : newEntries) {
            String pkgName = entry[0];
            String version = entry[1]; // may be null
            if (!oldPkgs.contains(normalizePypiName(pkgName))) {
                result.add(new ExtractedDep(
                        ExtractedDep.ECOSYSTEM_PYPI,
                        normalizePypiName(pkgName),
                        version,
                        ExtractedDep.SOURCE_MANIFEST,
                        filePath,
                        null
                ));
            }
        }
        return result;
    }

    private Set<String> parseReqLines(String content) {
        Set<String> result = new HashSet<>();
        if (content.isBlank()) return result;
        Matcher m = REQ_LINE.matcher(content);
        while (m.find()) {
            String pkg = m.group(1);
            if (pkg != null && !pkg.isBlank() && !pkg.startsWith("-") && !pkg.startsWith("#")) {
                // Strip extras
                int bracket = pkg.indexOf('[');
                if (bracket > 0) pkg = pkg.substring(0, bracket);
                result.add(normalizePypiName(pkg));
            }
        }
        return result;
    }

    private List<String[]> parseReqLinesWithVersion(String content) {
        List<String[]> result = new ArrayList<>();
        if (content == null || content.isBlank()) return result;
        for (String line : content.split("\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue;
            // Remove inline comments
            int hashIdx = line.indexOf('#');
            if (hashIdx > 0) line = line.substring(0, hashIdx).trim();
            // Remove environment markers
            int semiIdx = line.indexOf(';');
            if (semiIdx > 0) line = line.substring(0, semiIdx).trim();
            // Extract name and version
            Matcher m = Pattern.compile("^([A-Za-z0-9_\\-\\.]+(?:\\[[A-Za-z0-9_,]+\\])?)\\s*([><=!~^\\^].*)?$").matcher(line);
            if (m.matches()) {
                String pkg = m.group(1);
                if (pkg == null || pkg.isBlank()) continue;
                // Strip extras
                int bracket = pkg.indexOf('[');
                if (bracket > 0) pkg = pkg.substring(0, bracket);
                String ver = m.group(2);
                result.add(new String[]{pkg.trim(), ver != null ? ver.trim() : null});
            }
        }
        return result;
    }

    // ─────────────────────────── pyproject.toml ──────────────────────────────

    private List<ExtractedDep> extractPyprojectTomlAdded(String filePath, String old, String neu) {
        Set<String> oldPkgs = parsePyprojectDeps(old == null ? "" : old);
        Set<String> newPkgs = parsePyprojectDeps(neu);

        List<ExtractedDep> result = new ArrayList<>();
        for (String pkg : newPkgs) {
            if (!oldPkgs.contains(pkg)) {
                result.add(new ExtractedDep(
                        ExtractedDep.ECOSYSTEM_PYPI,
                        pkg,
                        null,
                        ExtractedDep.SOURCE_MANIFEST,
                        filePath,
                        null
                ));
            }
        }
        return result;
    }

    private Set<String> parsePyprojectDeps(String content) {
        Set<String> result = new LinkedHashSet<>();
        if (content == null || content.isBlank()) return result;
        // Find [project] dependencies / [tool.poetry.dependencies] / [build-system] etc.
        // Simple approach: match all lines that look like package specs
        boolean inDepsSection = false;
        for (String line : content.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inDepsSection = trimmed.contains("dependencies");
                continue;
            }
            if (!inDepsSection) continue;
            Matcher m = PYPROJECT_DEP.matcher(line);
            if (m.find()) {
                String pkg = m.group(1);
                if (pkg != null && !pkg.isBlank() && !pkg.equalsIgnoreCase("python")) {
                    result.add(normalizePypiName(pkg));
                }
            }
        }
        return result;
    }

    // ─────────────────────────── Python source .py ───────────────────────────

    private List<ExtractedDep> extractSourceAdded(String filePath, String old, String neu) {
        Set<String> oldMods = extractTopLevelModules(old == null ? "" : old);
        Set<String> newMods = extractTopLevelModules(neu);

        List<ExtractedDep> result = new ArrayList<>();
        for (String mod : newMods) {
            if (!oldMods.contains(mod)) {
                // Map module name → PyPI project name
                String pypiName = resolveModuleName(mod);
                if (pypiName != null) {
                    result.add(new ExtractedDep(
                            ExtractedDep.ECOSYSTEM_PYPI,
                            pypiName,
                            null,
                            ExtractedDep.SOURCE_IMPORT,
                            filePath,
                            null
                    ));
                }
            }
        }
        return result;
    }

    /**
     * 从 Python 源码提取顶层模块名，返回原始模块名（未映射）。
     * 过滤 stdlib 和相对导入（from . import ...）。
     */
    Set<String> extractTopLevelModules(String content) {
        Set<String> result = new LinkedHashSet<>();
        if (content == null || content.isBlank()) return result;
        Matcher m = IMPORT_STMT.matcher(content);
        while (m.find()) {
            String mod = m.group(1) != null ? m.group(1) : m.group(2);
            if (mod == null || mod.isBlank()) continue;
            if (STDLIB_MODULES.contains(mod)) continue;
            result.add(mod);
        }
        return result;
    }

    /**
     * 将模块名映射到 PyPI 项目名。
     * 未在别名表中的模块，直接使用原名。
     * stdlib 模块已在 extractTopLevelModules 阶段过滤，此处不重复检查。
     */
    String resolveModuleName(String moduleName) {
        if (moduleName == null || moduleName.isBlank()) return null;
        // Check alias map (case-sensitive first, then lowercase)
        String mapped = MODULE_TO_PYPI.get(moduleName);
        if (mapped == null) {
            mapped = MODULE_TO_PYPI.get(moduleName.toLowerCase(Locale.ROOT));
        }
        return mapped != null ? mapped : moduleName;
    }

    /**
     * PyPI 包名归一化（PEP 503）：小写，-_. 均视为分隔符替换为 -。
     * 用于 oldContent 包名集合比较，避免因大小写/分隔符不同漏检已有包。
     */
    public static String normalizePypiName(String name) {
        if (name == null) return null;
        return name.toLowerCase(Locale.ROOT).replaceAll("[_.]", "-");
    }
}
