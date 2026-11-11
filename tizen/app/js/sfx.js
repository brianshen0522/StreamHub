/*
 * Interface sounds: a quiet tick on focus moves, a soft pop on OK, a falling
 * note on Back and a dull thud at an edge. Synthesised once into AudioBuffers
 * (no files to ship), played through one Web Audio graph.
 *
 * The rules that keep it from ever sounding wrong:
 *
 * - One voice. A new sound fades the previous one out over 8 ms instead of
 *   layering over it, so a burst can never pile up into a chord or a click.
 * - Priority. OK and Back outrank an edge thud, which outranks a move tick:
 *   a lower sound arriving while a higher one is still sounding is dropped,
 *   not allowed to cut it off.
 * - Spacing. The same sound inside its own minimum gap is dropped (OK and its
 *   click arriving together, a held D-pad), so holding a key gives an even,
 *   quieter pulse rather than a rattle.
 * - Silent while the player is up: nothing over the film's own audio, and the
 *   AudioContext is suspended then so it never competes with AVPlay for the
 *   output.
 * - Never throws. Every entry point swallows errors: a sound is decoration and
 *   must not break navigation. No AudioContext (or a blocked one) = silence.
 *
 * window.StreamHubSfx = { play(name, repeat), setEnabled(bool), enabled(),
 *                         setPlayer(bool), stats() }
 */
(function () {
  "use strict";
  var STORE_KEY = "streamhub.sfx";
  var MASTER = 0.5;
  var FADE = 0.008;

  // gain: per-sound level under MASTER. gap: ms the same sound must wait.
  // prio: higher wins while it is still sounding.
  var SPEC = {
    move: { prio: 1, gap: 70, holdGap: 120, gain: 0.11 },
    edge: { prio: 2, gap: 260, gain: 0.30 },
    select: { prio: 3, gap: 140, gain: 0.17 },
    back: { prio: 3, gap: 140, gain: 0.15 },
  };

  var ctx = null;
  var master = null;
  var buffers = null;
  var voice = null; // { name, prio, src, gain, end }
  var last = {}; // name -> ms of the last accepted play
  var playerUp = false;
  var enabled = readEnabled();
  var counts = { played: 0, dropped: 0, cut: 0 };

  function readEnabled() {
    try { return window.localStorage.getItem(STORE_KEY) !== "0"; } catch (e) { return true; }
  }

  function now() { return Date.now(); }

  function ensureContext() {
    if (ctx) return ctx;
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    try {
      ctx = new AC();
      master = ctx.createGain();
      master.gain.value = MASTER;
      master.connect(ctx.destination);
      buffers = render(ctx.sampleRate);
    } catch (e) {
      ctx = null;
      master = null;
      buffers = null;
    }
    return ctx;
  }

  // ── synthesis ────────────────────────────────────────────────────────────
  // Each sound: partials (freq Hz, amp), a pitch glide, an attack and an
  // exponential decay. Short, soft-edged, no high harshness.
  function synth(rate, o) {
    var n = Math.ceil(rate * o.dur);
    var buf = ctx.createBuffer(1, n, rate);
    var d = buf.getChannelData(0);
    var attack = Math.max(1, Math.floor(rate * o.attack));
    var phase = [];
    for (var p = 0; p < o.partials.length; p += 1) phase.push(0);
    var peak = 0;
    for (var i = 0; i < n; i += 1) {
      var t = i / n;
      var glide = o.from + (o.to - o.from) * (1 - Math.pow(1 - t, 2));
      var env = (i < attack ? i / attack : 1) * Math.exp(-(i / rate) / o.decay);
      // Last 3 ms always ramp to zero: the buffer ends silent, never on a step.
      var tail = n - i;
      var tailN = Math.floor(rate * 0.003);
      if (tail < tailN) env *= tail / tailN;
      var s = 0;
      for (var k = 0; k < o.partials.length; k += 1) {
        phase[k] += (2 * Math.PI * glide * o.partials[k][0]) / rate;
        s += Math.sin(phase[k]) * o.partials[k][1];
      }
      d[i] = s * env;
      if (Math.abs(d[i]) > peak) peak = Math.abs(d[i]);
    }
    if (peak > 0) for (var j = 0; j < n; j += 1) d[j] /= peak; // normalise; level comes from SPEC
    return buf;
  }

  function render(rate) {
    return {
      // A tiny, high, rounded tick.
      move: synth(rate, { dur: 0.045, attack: 0.002, decay: 0.012, from: 1400, to: 1320, partials: [[1, 1], [2, 0.12]] }),
      // A low, short thud: felt more than heard.
      edge: synth(rate, { dur: 0.09, attack: 0.003, decay: 0.03, from: 190, to: 130, partials: [[1, 1], [2, 0.25]] }),
      // A soft rising pop.
      select: synth(rate, { dur: 0.11, attack: 0.003, decay: 0.035, from: 700, to: 940, partials: [[1, 1], [1.5, 0.3], [2, 0.12]] }),
      // The same pop, falling.
      back: synth(rate, { dur: 0.11, attack: 0.003, decay: 0.035, from: 900, to: 620, partials: [[1, 1], [1.5, 0.25]] }),
    };
  }

  // ── voice ────────────────────────────────────────────────────────────────
  function release(v, at) {
    try {
      v.gain.gain.cancelScheduledValues(at);
      v.gain.gain.setValueAtTime(v.gain.gain.value, at);
      v.gain.gain.linearRampToValueAtTime(0, at + FADE);
      v.src.stop(at + FADE + 0.002);
    } catch (e) {}
  }

  function play(name, repeat) {
    try {
      var spec = SPEC[name];
      if (!spec || !enabled || playerUp) return false;
      if (document.hidden) return false;
      var t = now();
      var gap = repeat && spec.holdGap ? spec.holdGap : spec.gap;
      if (last[name] && t - last[name] < gap) { counts.dropped += 1; return false; }
      if (voice && t < voice.end && voice.prio > spec.prio) { counts.dropped += 1; return false; }
      var c = ensureContext();
      if (!c || !buffers) return false;
      if (c.state === "suspended") {
        // resume() is async; this sound is dropped rather than played late.
        try { c.resume(); } catch (e) {}
        counts.dropped += 1;
        return false;
      }
      var at = c.currentTime;
      if (voice && t < voice.end) { release(voice, at); counts.cut += 1; }
      var src = c.createBufferSource();
      src.buffer = buffers[name];
      var g = c.createGain();
      g.gain.value = spec.gain * (repeat && name === "move" ? 0.6 : 1);
      src.connect(g);
      g.connect(master);
      var v = { name: name, prio: spec.prio, src: src, gain: g, end: t + src.buffer.duration * 1000 };
      src.onended = function () {
        try { src.disconnect(); g.disconnect(); } catch (e) {}
        if (voice === v) voice = null;
      };
      src.start(at);
      voice = v;
      last[name] = t;
      counts.played += 1;
      return true;
    } catch (e) {
      return false;
    }
  }

  function silence() {
    if (voice && ctx) release(voice, ctx.currentTime);
    voice = null;
  }

  function setPlayer(up) {
    try {
      playerUp = !!up;
      if (!ctx) return;
      if (playerUp) {
        // The OK that opened the player has just started its pop: let it
        // finish (≤ 110 ms) rather than chop it, then free the output.
        var left = voice ? Math.max(0, voice.end - now()) : 0;
        setTimeout(function () { if (playerUp && ctx) { try { ctx.suspend(); } catch (e) {} } }, left + 30);
      } else {
        try { ctx.resume(); } catch (e) {}
      }
    } catch (e) {}
  }

  function setEnabled(on) {
    enabled = !!on;
    try { window.localStorage.setItem(STORE_KEY, enabled ? "1" : "0"); } catch (e) {}
    if (!enabled) silence();
    return enabled;
  }

  document.addEventListener("visibilitychange", function () {
    try {
      if (!ctx) return;
      if (document.hidden) { silence(); ctx.suspend(); } else if (!playerUp) { ctx.resume(); }
    } catch (e) {}
  });

  window.StreamHubSfx = {
    play: function (name, repeat) { return play(String(name), !!repeat); },
    setEnabled: setEnabled,
    enabled: function () { return enabled; },
    setPlayer: setPlayer,
    stats: function () {
      return { played: counts.played, dropped: counts.dropped, cut: counts.cut, enabled: enabled, playerUp: playerUp,
        state: ctx ? ctx.state : "none", voice: voice ? voice.name : null };
    },
  };
})();
