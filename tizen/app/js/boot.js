/* Loader guard, owned by the lead (not the player agent).
 *
 * The packaged app runs from file://. wasm-bindgen's loader fetch()es the
 * .wasm, and a web runtime may refuse fetch() on file: URLs while still
 * allowing XHR. When fetch of a file: URL rejects, retry it with XHR and
 * hand back a Response typed application/wasm, so instantiateStreaming (or
 * its arrayBuffer fallback) gets the bytes either way. Nothing else is
 * touched: network requests go to the real fetch untouched.
 */
(function () {
  var realFetch = window.fetch && window.fetch.bind(window);
  if (!realFetch) return;

  function isLocal(input) {
    var url = typeof input === "string" ? input : (input && input.url) || "";
    try { return new URL(url, location.href).protocol === "file:"; } catch (e) { return false; }
  }

  function xhrFetch(input) {
    var url = new URL(typeof input === "string" ? input : input.url, location.href).href;
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open("GET", url, true);
      xhr.responseType = "arraybuffer";
      xhr.onload = function () {
        // file: XHRs report status 0 on success.
        if (xhr.status !== 0 && (xhr.status < 200 || xhr.status >= 300)) {
          reject(new TypeError("XHR " + xhr.status + " for " + url));
          return;
        }
        var type = /\.wasm(\?|$)/.test(url) ? "application/wasm"
          : /\.js(\?|$)/.test(url) ? "text/javascript" : "application/octet-stream";
        resolve(new Response(xhr.response, { status: 200, headers: { "Content-Type": type } }));
      };
      xhr.onerror = function () { reject(new TypeError("XHR failed for " + url)); };
      xhr.send();
    });
  }

  window.fetch = function (input, init) {
    if (!isLocal(input)) return realFetch(input, init);
    return realFetch(input, init).then(function (response) {
      // Some runtimes answer file: fetches with an opaque status-0 response.
      if (response.ok) return response;
      return xhrFetch(input);
    }, function () { return xhrFetch(input); });
  };

  // instantiateStreaming insists on the application/wasm MIME type, which a
  // file: response may not carry; fall back to compiling the bytes.
  if (WebAssembly && WebAssembly.instantiateStreaming) {
    var streaming = WebAssembly.instantiateStreaming.bind(WebAssembly);
    WebAssembly.instantiateStreaming = function (source, imports) {
      return Promise.resolve(source).then(function (response) {
        var copy = response.clone();
        return streaming(response, imports).catch(function () {
          return copy.arrayBuffer().then(function (bytes) {
            return WebAssembly.instantiate(bytes, imports);
          });
        });
      });
    };
  }
})();
