/* OWNED BY THE PLAYER AGENT: registers remote keys via tizen.tvinputdevice when present.
 *
 * Arrows, Enter and Back reach the page without registration; every media key
 * must be registered or the platform keeps it. Each key is registered on its
 * own: registerKeyBatch fails as a whole if one name is unknown to the model,
 * and a minimal remote (the M7 Smart Monitor's) lacks several of these.
 * Back is never registered — long-press Back is the platform's way out of an
 * app (Samsung checklist), so it must stay the system's.
 *
 * Needs the http://tizen.org/privilege/tv.inputdevice privilege in config.xml.
 */
(function () {
  "use strict";
  var KEYS = [
    "MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop",
    "MediaRewind", "MediaFastForward", "MediaTrackPrevious", "MediaTrackNext",
    // Not on the M7's minimal remote, but on fuller Samsung remotes; screens
    // may bind them. Registered only where the set reports them.
    "ChannelUp", "ChannelDown", "ColorF0Red", "Info",
    "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
  ];
  var input = window.tizen && window.tizen.tvinputdevice;
  if (!input) return;

  var supported = {};
  try {
    var list = input.getSupportedKeys() || [];
    for (var i = 0; i < list.length; i++) supported[list[i].name] = true;
  } catch (e) {
    supported = null; // unknown: try every key
  }

  var wanted = KEYS.filter(function (k) { return !supported || supported[k]; });
  // One bad name sinks the batch (thrown, or reported to the error callback);
  // fall back to one at a time.
  var fellBack = false;
  function oneByOne() {
    if (fellBack) return;
    fellBack = true;
    wanted.forEach(function (k) {
      try { input.registerKey(k); } catch (err) { /* not on this remote */ }
    });
  }
  try {
    if (typeof input.registerKeyBatch === "function") {
      input.registerKeyBatch(wanted, function () {}, oneByOne);
    } else {
      oneByOne();
    }
  } catch (e) {
    oneByOne();
  }
})();
