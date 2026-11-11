// Minimal static server for tizen/dist: correct MIME for .wasm (instantiateStreaming
// insists on application/wasm), no caching, index.html for unknown paths.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const TYPES = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".wasm": "application/wasm", ".json": "application/json",
  ".png": "image/png", ".svg": "image/svg+xml", ".ico": "image/x-icon",
};

export function serve(root, port) {
  const base = path.resolve(root);
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, "http://x");
    let file = path.resolve(base, "." + decodeURIComponent(url.pathname));
    if (!file.startsWith(base)) { res.writeHead(403).end(); return; }
    const missing = !fs.existsSync(file) || fs.statSync(file).isDirectory();
    // Unknown asset paths 404 (as $WEBAPIS/webapis/webapis.js does off the
    // TV); only extension-less paths fall back to the app shell.
    if (missing && path.extname(file)) { res.writeHead(404).end("not found"); return; }
    if (missing) file = path.join(base, "index.html");
    fs.readFile(file, (err, body) => {
      if (err) { res.writeHead(404).end("not found"); return; }
      res.writeHead(200, {
        "content-type": TYPES[path.extname(file)] || "application/octet-stream",
        "cache-control": "no-store",
      });
      res.end(body);
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [root = "../dist", port = "5190"] = process.argv.slice(2);
  await serve(root, Number(port));
  console.log(`serving ${root} on http://localhost:${port}`);
}
