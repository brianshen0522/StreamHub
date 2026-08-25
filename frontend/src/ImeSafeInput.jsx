import { memo, useEffect, useRef } from "react";

/**
 * A text input that a composing input method can trust.
 *
 * A controlled React input writes `value` back into the DOM whenever its state
 * disagrees — and on a page as heavy as the watch page, state runs a beat
 * behind a composition's rapid events, so React writes a stale value in the
 * same tick as the next update. A programmatic value write cancels the active
 * composition and commits its text, which is how 注音 came out as a trail of
 * dead bopomofo: ㄏ, then ㄏㄨ appended after it, then ㄏㄨㄛ after that.
 *
 * The rule here is absolute rather than clever: while a composition is open,
 * React does not touch this element. The input runs uncontrolled; nothing
 * re-renders it, nothing writes to it, the input method owns it outright. Only
 * when composition ends — or on ordinary non-composing keystrokes — does the
 * value flow up, and only when the outside value genuinely differs (a clear
 * button, a reset) does it flow back down.
 */
const ImeSafeInput = memo(function ImeSafeInput({ value, onValueChange, inputRef, ...rest }) {
  const ownRef = useRef(null);
  const composing = useRef(false);
  // The latest external value, readable outside the render — the
  // composition-end reconciliation below needs it after the parent has had its
  // say, which is later than this closure.
  const valueRef = useRef(value);
  valueRef.current = value;

  const setRef = (node) => {
    ownRef.current = node;
    if (typeof inputRef === "function") inputRef(node);
    else if (inputRef) inputRef.current = node;
  };

  useEffect(() => {
    const el = ownRef.current;
    if (el && !composing.current && el.value !== value) el.value = value;
  }, [value]);

  return (
    <input
      {...rest}
      ref={setRef}
      defaultValue={value}
      onCompositionStart={() => { composing.current = true; }}
      onCompositionEnd={(event) => {
        composing.current = false;
        onValueChange(event.currentTarget.value);
        // A committed composition the parent rejects entirely — bopomofo into
        // the code field, say — leaves the external value exactly what it was,
        // so the value-sync effect never fires and the junk would sit on
        // screen. Reconcile once the parent has processed the change.
        requestAnimationFrame(() => {
          const el = ownRef.current;
          if (el && !composing.current && el.value !== valueRef.current) {
            el.value = valueRef.current;
          }
        });
      }}
      onChange={(event) => {
        if (!composing.current) onValueChange(event.currentTarget.value);
      }}
    />
  );
});

export default ImeSafeInput;
