import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { apiJson } from "./api.js";

/**
 * Pairing sign-in for a television that opened the web app in its browser.
 *
 * The same device flow the native TV apps use, with this browser in the
 * television's seat: it starts the pairing, shows the QR and the short code,
 * and polls until someone signed in on a phone approves it. The session is
 * minted from *this* request's address and user agent, so the account's
 * device list names the television rather than the phone that said yes.
 *
 * The QR encodes the verification URL with the user code in it — never the
 * device code, which collects the session: a photograph of a television
 * screen must not be a sign-in.
 */

export default function TvSignIn({ onSession }) {
  const [pairing, setPairing] = useState(null);
  const [status, setStatus] = useState("starting"); // starting | waiting | denied | error
  const canvasRef = useRef(null);
  // The generation counter tears down a superseded pairing's poll loop:
  // restarting after expiry must not leave the old loop polling a dead code.
  const generation = useRef(0);

  async function start() {
    const gen = ++generation.current;
    setStatus("starting");
    setPairing(null);
    try {
      const started = await apiJson("/api/auth/device/start", { method: "POST", body: JSON.stringify({}) });
      if (generation.current !== gen) return;
      setPairing(started);
      setStatus("waiting");
    } catch {
      if (generation.current !== gen) return;
      setStatus("error");
    }
  }

  useEffect(() => {
    start();
    return () => { generation.current += 1; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Draw the QR whenever a pairing is live.
  useEffect(() => {
    if (!pairing || !canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, pairing.verificationUrlComplete, {
      width: 320,
      margin: 1,
      color: { dark: "#0b0b0f", light: "#ffffff" },
    }).catch(() => { /* the code below the QR still signs the television in */ });
  }, [pairing]);

  // The code dies on schedule, so it is replaced on schedule: waiting for a
  // poll to notice leaves an expired QR on screen for up to an interval —
  // longer on a backgrounded phone, whose timers freeze until it returns.
  useEffect(() => {
    if (!pairing || status !== "waiting") return undefined;
    const gen = generation.current;
    const ttlMs = Math.max(5, pairing.expiresInSeconds || 600) * 1000;
    const timer = window.setTimeout(() => {
      if (generation.current === gen) start();
    }, ttlMs + 1_000);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pairing, status]);

  // Poll until approved; expiry quietly starts a fresh code.
  useEffect(() => {
    if (!pairing || status !== "waiting") return undefined;
    const gen = generation.current;
    const interval = Math.max(2, pairing.intervalSeconds || 5) * 1000;
    const timer = window.setInterval(async () => {
      let result;
      try {
        result = await apiJson("/api/auth/device/poll", {
          method: "POST",
          body: JSON.stringify({ deviceCode: pairing.deviceCode }),
        });
      } catch {
        return; // transient — the next tick asks again
      }
      if (generation.current !== gen) return;
      if (result.status === "approved") {
        window.clearInterval(timer);
        onSession(result);
      } else if (result.status === "denied") {
        window.clearInterval(timer);
        setStatus("denied");
      } else if (result.status === "expired") {
        window.clearInterval(timer);
        start();
      }
    }, interval);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pairing, status]);

  return (
    <div className="auth-tv">
      <p className="auth-tv-lead">
        Scan with your phone, or open <b>{pairing ? pairing.verificationUrl.replace(/^https?:\/\//, "") : "…"}</b> on
        any signed-in device and type the code.
      </p>
      <div className="auth-tv-qr" aria-hidden={!pairing}>
        {status === "waiting" || pairing ? <canvas ref={canvasRef} /> : null}
      </div>
      <div className="auth-tv-code" aria-label="Pairing code">
        {status === "starting" && "········"}
        {status === "error" && "—"}
        {(status === "waiting" || status === "denied") && (pairing?.userCode || "")}
      </div>
      {status === "waiting" ? <p className="auth-tv-hint">Waiting for approval…</p> : null}
      {status === "denied" ? (
        <p className="auth-tv-hint">
          The request was declined. <button type="button" className="auth-tv-again" onClick={start}>Get a new code</button>
        </p>
      ) : null}
      {status === "error" ? (
        <p className="auth-tv-hint">
          Could not reach the server. <button type="button" className="auth-tv-again" onClick={start}>Try again</button>
        </p>
      ) : null}
    </div>
  );
}
