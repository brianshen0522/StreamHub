import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    // The service worker is written by hand in src/sw.js; the plugin only
    // bundles it and injects the list of files to precache. The manifest is
    // the existing public/site.webmanifest, already linked from index.html,
    // so the plugin is told not to make its own. Registration happens in
    // src/pwa.js rather than through an injected script, so the app can show
    // the "update ready" banner and decide when to reload.
    VitePWA({
      strategies: "injectManifest",
      srcDir: "src",
      filename: "sw.js",
      registerType: "prompt",
      injectRegister: false,
      manifest: false,
      injectManifest: {
        globPatterns: ["**/*.{js,css,html,svg,png,webmanifest}"],
        // hls.js alone is a few hundred KB; the default cap would drop it
        // from the precache and the player would not open offline-first.
        maximumFileSizeToCacheInBytes: 5 * 1024 * 1024,
      },
      devOptions: { enabled: false },
    }),
  ],
  server: {
    host: "0.0.0.0",
    port: 5173,
    // shared/ lives above the vite root; the dev server must be allowed to read it.
    fs: { allow: [".."] },
    proxy: {
      "/api": {
        // Overridable so the dev server can be pointed at a throwaway backend
        // instead of whatever is running on the usual port — which, on a
        // machine that also runs the real stack, is the real stack.
        target: process.env.STREAMHUB_API_ORIGIN || "http://localhost:8787",
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
