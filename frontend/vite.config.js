import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
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
