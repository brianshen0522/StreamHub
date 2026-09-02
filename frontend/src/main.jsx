import React from "react";
import ReactDOM from "react-dom/client";
import RootApp from "./RootApp.jsx";
import "./styles.css";

// Safari in a browser tab ignores `user-scalable=no` on purpose, but it does
// still fire its proprietary gesture events for a pinch, and cancelling the
// first one blocks the zoom. Passive must be off or preventDefault is a no-op.
// Harmless everywhere else: no other engine fires these.
document.addEventListener("gesturestart", (e) => e.preventDefault(), { passive: false });

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <RootApp />
  </React.StrictMode>,
);
