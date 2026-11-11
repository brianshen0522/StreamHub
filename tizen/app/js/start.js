/* Starts the Wasm app (lead-owned). A file rather than an inline <script>, so
 * the widget's CSP needs no 'unsafe-inline' for scripts. Runs after
 * streamhub-tizen.js has defined the no-modules `wasm_bindgen` global. */
(function () {
  wasm_bindgen({ module_or_path: "streamhub-tizen_bg.wasm" }).catch(function (error) {
    document.body.textContent = "StreamHub failed to start: " + error;
    console.error(error);
  });
})();
