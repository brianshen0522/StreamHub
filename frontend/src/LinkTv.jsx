import { Suspense, lazy, useCallback, useEffect, useRef, useState } from "react";
import ImeSafeInput from "./ImeSafeInput.jsx";
import { useSearchParams } from "react-router-dom";
import { apiJson } from "./api.js";
import { fmt } from "./i18n.js";

// The camera half is only reached by pressing Scan, and it pulls a decoder in
// behind it on Safari; nobody who types the code should pay for that.
const QrScanner = lazy(() => import("./QrScanner.jsx"));

/**
 * Signing a television in from something with a keyboard.
 *
 * Two ways in, one screen. Scanning the QR on the television opens this page
 * with the code already in the address, so the whole job is one tap; typing the
 * code by hand is the same screen a step earlier. Both land on the same
 * confirmation, because the confirmation is the part that matters.
 *
 * It matters because no amount of cryptography closes the one hole a device
 * flow has by construction: somebody can be talked into approving a code that
 * is not their television's. The only defence is that the person sees what they
 * are about to hand over and can recognise whether it is theirs — so the device
 * is named, and the consequence is spelled out rather than implied by an
 * "Approve" button.
 */

const STEP_CODE = "code";
const STEP_CONFIRM = "confirm";
const STEP_DONE = "done";
const STEP_DENIED = "denied";

export default function LinkTvPage({ setTopbar, t }) {
  const [params, setParams] = useSearchParams();
  const [step, setStep] = useState(STEP_CODE);
  const [code, setCode] = useState(() => params.get("code") || "");
  const [device, setDevice] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [scanning, setScanning] = useState(false);
  const inputRef = useRef(null);

  useEffect(() => {
    setTopbar({ title: t.linkTitle, sub: t.linkSub });
  }, [setTopbar, t]);

  const lookUp = useCallback(async (raw) => {
    const clean = normalise(raw);
    if (clean.length !== 8) {
      setError(t.linkBadCode);
      return;
    }
    setBusy(true);
    setError("");
    try {
      const found = await apiJson(`/api/auth/device/pending?code=${encodeURIComponent(clean)}`);
      setDevice(found);
      setStep(STEP_CONFIRM);
    } catch (failure) {
      // 404 and 400 are the same thing to the person holding the phone: this
      // code will not work, get another one.
      setError(/expired|already|not look/i.test(failure.message) ? t.linkGoneCode : failure.message);
    } finally {
      setBusy(false);
    }
  }, [t]);

  // Arriving from the QR means the code is already known, so the typing step is
  // skipped entirely — scanning should be one action, not one action and a form.
  const scanned = params.get("code");
  useEffect(() => {
    if (!scanned) {
      inputRef.current?.focus();
      return;
    }
    // Dropped from the address once used: a reload should not re-run a decision
    // about access, and the code is a secret that does not belong in history.
    setParams({}, { replace: true });
    lookUp(scanned);
  }, [scanned, setParams, lookUp]);

  // Stable, because QrScanner restarts its camera whenever this identity
  // changes and a new function every render would restart it every render.
  const handleScanned = useCallback((found) => {
    setScanning(false);
    setCode(`${found.slice(0, 4)}-${found.slice(4)}`);
    lookUp(found);
  }, [lookUp]);

  const handleScanError = useCallback((message) => {
    setScanning(false);
    setError(message);
  }, []);

  async function decide(approve) {
    setBusy(true);
    setError("");
    try {
      await apiJson(`/api/auth/device/${approve ? "approve" : "deny"}`, {
        method: "POST",
        body: JSON.stringify({ code: normalise(code || device?.userCode || "") }),
      });
      setStep(approve ? STEP_DONE : STEP_DENIED);
    } catch (failure) {
      setError(/expired|already/i.test(failure.message) ? t.linkGoneCode : failure.message);
      setStep(STEP_CODE);
    } finally {
      setBusy(false);
    }
  }

  function startOver() {
    setCode("");
    setDevice(null);
    setError("");
    setStep(STEP_CODE);
  }

  return (
    <div className="usr-link">
      {error ? <div className="usr-alert usr-alert-bad"><span>{error}</span></div> : null}

      {step === STEP_CODE ? (
        <section className="usr-panel">
          <header className="usr-panel-head">
            <div className="usr-panel-title">{t.linkTitle}</div>
            <div className="usr-panel-desc">{t.linkSub}</div>
          </header>
          <div className="usr-panel-body">
            {scanning ? (
              <div className="usr-form">
                <Suspense fallback={<p className="usr-panel-desc">{t.scanStarting}</p>}>
                  <QrScanner onCode={handleScanned} onError={handleScanError} t={t} />
                </Suspense>
                <div className="usr-form-actions">
                  <button type="button" className="usr-btn usr-btn-ghost" onClick={() => setScanning(false)}>
                    {t.scanStop}
                  </button>
                </div>
              </div>
            ) : (
            <form
              className="usr-form"
              onSubmit={(event) => { event.preventDefault(); lookUp(code); }}
            >
              <label className="usr-field" style={{ position: "relative" }}>
                <span className="usr-label">{t.linkCodeLabel}</span>
                <ImeSafeInput
                  inputRef={inputRef}
                  className="usr-input usr-code-input"
                  value={code}
                  // ImeSafeInput leaves the element alone while a composition
                  // is open, so the transform only ever sees settled text —
                  // rewriting mid-composition destroys the buffer, the same
                  // fault both phone apps once had in their own dress.
                  onValueChange={(value) => setCode(groupForDisplay(value))}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" && (event.nativeEvent.isComposing || event.keyCode === 229)) {
                      event.preventDefault();
                    }
                  }}
                  placeholder={t.linkCodePlaceholder}
                  // The code is not a word, so every helper the browser offers
                  // for words gets in the way.
                  autoCapitalize="characters"
                  autoCorrect="off"
                  spellCheck="false"
                  autoComplete="one-time-code"
                  inputMode="text"
                  maxLength={9}
                />
                {code ? (
                  <button
                    type="button"
                    className="usr-input-clear usr-code-clear"
                    onClick={() => { setCode(""); inputRef.current?.focus(); }}
                    aria-label={t.clearInput}
                  >
                    ×
                  </button>
                ) : null}
              </label>
              <div className="usr-form-actions">
                <button
                  type="submit"
                  className="usr-btn usr-btn-primary"
                  disabled={busy || normalise(code).length !== 8}
                >
                  {busy ? t.linkChecking : t.linkContinue}
                </button>
                <button
                  type="button"
                  className="usr-btn usr-btn-ghost"
                  onClick={() => { setError(""); setScanning(true); }}
                >
                  {t.scanOpen}
                </button>
              </div>
            </form>
            )}
          </div>
        </section>
      ) : null}

      {step === STEP_CONFIRM && device ? (
        <section className="usr-panel">
          <header className="usr-panel-head">
            <div className="usr-panel-title">{t.linkConfirmTitle}</div>
            <div className="usr-panel-desc">{t.linkConfirmBody}</div>
          </header>
          <div className="usr-panel-body">
            <div className="usr-kv">
              <div className="usr-kv-row"><span>{t.linkDevice}</span><span>{device.deviceName}</span></div>
              <div className="usr-kv-row"><span>{t.linkCodeLabel}</span><span className="usr-code-shown">{device.userCode}</span></div>
            </div>
            <div className="usr-form-actions">
              <button type="button" className="usr-btn usr-btn-primary" disabled={busy} onClick={() => decide(true)}>
                {busy ? t.linkApproving : t.linkApprove}
              </button>
              <button type="button" className="usr-btn usr-btn-ghost" disabled={busy} onClick={() => decide(false)}>
                {t.linkDeny}
              </button>
            </div>
          </div>
        </section>
      ) : null}

      {step === STEP_DONE || step === STEP_DENIED ? (
        <section className="usr-panel">
          <header className="usr-panel-head">
            <div className="usr-panel-title">{step === STEP_DONE ? t.linkDone : t.linkDenied}</div>
            <div className="usr-panel-desc">
              {step === STEP_DONE
                ? fmt(t.linkDoneBody, { d: device?.deviceName || "" })
                : t.linkDeniedBody}
            </div>
          </header>
          <div className="usr-panel-body">
            <button type="button" className="usr-btn usr-btn-ghost" onClick={startOver}>
              {t.linkAnother}
            </button>
          </div>
        </section>
      ) : null}
    </div>
  );
}

function normalise(value) {
  return String(value || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
}

/** Puts the break back as they type, so what is on screen matches the TV. */
function groupForDisplay(value) {
  const clean = normalise(value).slice(0, 8);
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean;
}
