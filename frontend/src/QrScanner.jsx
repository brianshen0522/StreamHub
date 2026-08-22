import { useEffect, useRef, useState } from "react";

/**
 * Reading the pairing code off a television with the camera.
 *
 * Two decoders, because there is no one API that works everywhere.
 * `BarcodeDetector` is native, fast and costs nothing to ship, but Safari does
 * not have it — and Safari is the whole iPhone, which is the device most likely
 * to be pointed at a television. So jsQR is loaded on demand as the fallback.
 * It is only fetched when it is actually needed, which on Chrome is never.
 *
 * The camera stream is stopped on the way out, without exception. A page that
 * leaves the capture running leaves the recording light on, and on a phone that
 * reads as the app watching you.
 */

/** How often to look at a frame. Faster than this is spent battery, not speed. */
const SCAN_INTERVAL_MS = 160;

export default function QrScanner({ onCode, onError, t }) {
  const videoRef = useRef(null);
  const canvasRef = useRef(null);
  const [status, setStatus] = useState("starting");
  // Held in a ref so the scan loop cannot fire twice on the frame after a hit.
  const doneRef = useRef(false);

  useEffect(() => {
    let stream = null;
    let timer = null;
    let detector = null;
    let decodeFallback = null;
    let cancelled = false;

    async function start() {
      if (!navigator.mediaDevices?.getUserMedia) {
        // Which is also what a page served over plain http looks like: the API
        // is simply absent outside a secure context.
        setStatus("unsupported");
        onError?.(t?.scanUnsupported || "This browser cannot use the camera here.");
        return;
      }

      try {
        stream = await navigator.mediaDevices.getUserMedia({
          // The back camera, and a resolution big enough to resolve the modules
          // of a code across a room without asking the phone for 4K.
          video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false,
        });
      } catch (failure) {
        if (cancelled) return;
        const denied = failure?.name === "NotAllowedError" || failure?.name === "SecurityError";
        setStatus(denied ? "denied" : "failed");
        onError?.(denied
          ? (t?.scanDenied || "Camera access was refused.")
          : (t?.scanFailed || "Could not start the camera."));
        return;
      }

      if (cancelled) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }

      const video = videoRef.current;
      if (!video) return;
      video.srcObject = stream;
      // playsInline matters on iOS: without it the video takes over the screen
      // as a fullscreen player and the scanner is never seen.
      video.setAttribute("playsinline", "true");
      await video.play().catch(() => {});
      setStatus("scanning");

      if ("BarcodeDetector" in window) {
        try {
          detector = new window.BarcodeDetector({ formats: ["qr_code"] });
        } catch { detector = null; }
      }

      timer = window.setInterval(async () => {
        if (doneRef.current || !videoRef.current) return;
        const raw = detector
          ? await readWithDetector(detector, videoRef.current)
          : await readWithFallback();
        // Anything that is not one of our codes is ignored rather than
        // reported: a camera pointed at a room finds wifi codes and packaging,
        // and stopping on each of them would make this unusable.
        const found = codeFromScan(raw);
        if (found && !doneRef.current) {
          doneRef.current = true;
          onCode(found);
        }
      }, SCAN_INTERVAL_MS);
    }

    async function readWithFallback() {
      const video = videoRef.current;
      const canvas = canvasRef.current;
      if (!video || !canvas || !video.videoWidth) return null;
      if (!decodeFallback) {
        // Fetched the first time a frame is actually looked at, so the decoder
        // never lands in the bundle of anyone whose browser has the native one.
        const module = await import("jsqr");
        decodeFallback = module.default || module;
      }
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      const context = canvas.getContext("2d", { willReadFrequently: true });
      context.drawImage(video, 0, 0, canvas.width, canvas.height);
      const image = context.getImageData(0, 0, canvas.width, canvas.height);
      const result = decodeFallback(image.data, image.width, image.height, {
        inversionAttempts: "dontInvert",
      });
      return result?.data || null;
    }

    start();

    return () => {
      cancelled = true;
      if (timer) window.clearInterval(timer);
      // Every track, every time — this is what turns the camera light off.
      stream?.getTracks().forEach((track) => track.stop());
      const video = videoRef.current;
      if (video) video.srcObject = null;
    };
  }, [onCode, onError, t]);

  return (
    <div className="usr-scan">
      <video ref={videoRef} className="usr-scan-video" muted playsInline />
      <canvas ref={canvasRef} hidden />
      <div className="usr-scan-frame" aria-hidden="true" />
      <p className="usr-scan-hint">
        {status === "scanning"
          ? (t?.scanHint || "Point the camera at the code on your television.")
          : status === "starting"
            ? (t?.scanStarting || "Starting the camera…")
            : (t?.scanBlocked || "Enter the code by hand instead.")}
      </p>
    </div>
  );
}

async function readWithDetector(detector, video) {
  try {
    const codes = await detector.detect(video);
    return codes?.[0]?.rawValue || null;
  } catch {
    return null;
  }
}

/**
 * The pairing code out of whatever the camera read.
 *
 * The television encodes a link, so the common case is a URL — but only its
 * `code` parameter is taken and the URL itself is never followed. A QR is
 * something a stranger can print and leave on a wall; treating one as a place
 * to navigate would hand the signed-in page to whoever printed it.
 */
function codeFromScan(text) {
  const clean = (value) => String(value || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (!text) return null;

  try {
    const url = new URL(text);
    const param = clean(url.searchParams.get("code"));
    if (param.length === 8) return param;
  } catch {
    // Not a URL — fall through and treat it as a bare code.
  }

  const bare = clean(text);
  return bare.length === 8 ? bare : null;
}
