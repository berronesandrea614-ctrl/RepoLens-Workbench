import { defineConfig, type Plugin } from "vitest/config";
import react from "@vitejs/plugin-react";

// @ts-expect-error process is a nodejs global
const host = process.env.TAURI_DEV_HOST;

/**
 * Replace the hard-coded CDN URL inside @monaco-editor/loader's default config
 * object with a safe placeholder at bundle time.
 *
 * The URL is never fetched at runtime (monacoLoader.ts calls loader.config({monaco})
 * before any <Editor> mounts, so the loader uses our bundled monaco directly).
 * This replacement eliminates the string from the dist output so CSP audits and
 * grep-based checks produce zero false positives.
 */
function removeCdnUrlFromBundle(): Plugin {
  const CDN_RE = /https:\/\/cdn\.jsdelivr\.net\/npm\/monaco-editor[^"']*/g;
  return {
    name: "remove-cdn-url-from-bundle",
    enforce: "post",
    generateBundle(_, bundle) {
      for (const asset of Object.values(bundle)) {
        if (asset.type === "chunk") {
          asset.code = asset.code.replace(CDN_RE, "self-hosted");
        }
      }
    },
  };
}

// https://vite.dev/config/
export default defineConfig(async () => ({
  plugins: [react(), removeCdnUrlFromBundle()],

  test: { environment: "node", include: ["src/**/*.test.ts"] },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
  },

  // Monaco editor is self-hosted via monacoLoader.ts — exclude from pre-bundling
  // so that the ?worker imports resolve correctly at build time.
  optimizeDeps: {
    exclude: ["monaco-editor"],
  },

  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          // Split Monaco into its own chunk to keep the main bundle small.
          monaco: ["monaco-editor"],
        },
      },
    },
  },
}));
