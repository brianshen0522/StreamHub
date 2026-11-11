/* OWNED BY THE PLAYER AGENT: window.StreamHubPlayer over webapis.avplay, or <video> in a desktop browser.
 *
 * One interface, three backends, picked at each open():
 *   mock   — localStorage streamhub.mockPlayer = "1". A fake clock and no media,
 *            so QA in headless Chromium (no H.264) can drive the whole player.
 *   avplay — Samsung's hardware player (webapis.avplay) on a real set.
 *   html5  — <video> + hls.js (js/vendor/hls.min.js, loaded on first use).
 *
 *   open(url, startMs) play() pause() seek(ms) stop() close()
 *   position() -> ms   duration() -> ms   state()   backendName()
 *   setListener({ onState, onTime, onBuffering, onEnded, onError })
 *
 * States: idle | loading | playing | paused | ended | error.
 * Every callback is fenced by an open() generation, so a late event from a
 * source that was replaced can never reach the current one.
 *
 * Mock knobs (localStorage, all optional):
 *   streamhub.mockPlayerFail     "direct" — /manifest URLs fail (the ladder
 *                                must recover on the relay); "once" — only the
 *                                first open of the page fails; "all" — every
 *                                open fails (reaches the error panel).
 *   streamhub.mockDuration       seconds, default 1500.
 *   streamhub.mockRate           clock speed multiplier, default 1.
 * StreamHubPlayer.opened records every {url, startMs, backend} opened.
 */
(function () {
  "use strict";

  function ls(key) {
    try { return window.localStorage.getItem(key); } catch (e) { return null; }
  }

  var listener = {};
  var gen = 0;
  var state = "idle";
  var current = null;
  var opened = [];

  function emit(name, arg) {
    var f = listener[name];
    if (typeof f !== "function") return;
    try { f(arg); } catch (e) { if (window.console) console.error(e); }
  }
  function setState(next, g) {
    if (g !== undefined && g !== gen) return;
    if (state === next) return;
    state = next;
    emit("onState", next);
  }
  function fail(message, g) {
    if (g !== gen) return;
    state = "error";
    emit("onState", "error");
    emit("onError", String(message || "PLAYBACK_ERROR"));
  }

  // ── Tizen: webapis.js is only present when the page asks for it ──────────
  var webapisWaiters = [];
  var webapisDone = !window.tizen || !!window.webapis;
  if (!webapisDone) {
    var s = document.createElement("script");
    s.src = "$WEBAPIS/webapis/webapis.js";
    s.onload = s.onerror = function () {
      webapisDone = true;
      var w = webapisWaiters; webapisWaiters = [];
      w.forEach(function (f) { f(); });
    };
    (document.head || document.documentElement).appendChild(s);
  }
  function whenWebapis(f) { if (webapisDone) f(); else webapisWaiters.push(f); }

  // ── avplay ───────────────────────────────────────────────────────────────
  var avplay = {
    name: "avplay",
    el: null,
    active: false,
    p: function () { return window.webapis.avplay; },
    open: function (url, startMs, g) {
      var p = this.p();
      try { p.stop(); } catch (e) {}
      try { p.close(); } catch (e) {}
      if (!this.el) {
        // The video is a hardware plane under the web layer; this element is
        // the hole it shows through. Coordinates are 1920×1080 even on a 4K panel.
        var o = document.createElement("object");
        o.type = "application/avplayer";
        o.id = "vp-avplayer";
        o.style.cssText = "position:fixed;left:0;top:0;width:1920px;height:1080px;z-index:0;";
        document.body.insertBefore(o, document.body.firstChild);
        this.el = o;
      }
      this.active = true;
      try {
        p.open(url);
        p.setListener({
          onbufferingstart: function () { if (g === gen) emit("onBuffering", true); },
          onbufferingprogress: function () {},
          onbufferingcomplete: function () { if (g === gen) emit("onBuffering", false); },
          oncurrentplaytime: function (ms) { if (g === gen) emit("onTime", Number(ms) || 0); },
          onstreamcompleted: function () {
            if (g !== gen) return;
            setState("ended", g);
            emit("onEnded");
          },
          onevent: function () {},
          onerror: function (type) { fail(type, g); },
          onerrormsg: function (type, msg) { fail(type + (msg ? ": " + msg : ""), g); },
          ondrmevent: function () {},
          onsubtitlechange: function () {},
        });
        p.setDisplayRect(0, 0, 1920, 1080);
        try { p.setDisplayMethod("PLAYER_DISPLAY_MODE_LETTER_BOX"); } catch (e) {}
        emit("onBuffering", true);
        p.prepareAsync(function () {
          if (g !== gen) return;
          var go = function () {
            if (g !== gen) return;
            try { p.play(); setState("playing", g); emit("onBuffering", false); }
            catch (e) { fail(e && e.name || "PLAY_FAILED", g); }
          };
          if (startMs > 0) {
            try { p.seekTo(startMs, go, go); } catch (e) { go(); }
          } else {
            go();
          }
        }, function (e) { fail((e && (e.name || e.message)) || "PREPARE_FAILED", g); });
      } catch (e) {
        fail((e && (e.name || e.message)) || "OPEN_FAILED", g);
      }
    },
    play: function () { try { this.p().play(); setState("playing"); } catch (e) {} },
    pause: function () { try { this.p().pause(); setState("paused"); } catch (e) {} },
    seek: function (ms) {
      var g = gen;
      try {
        emit("onBuffering", true);
        this.p().seekTo(Math.max(0, ms),
          function () { if (g === gen) emit("onBuffering", false); },
          function () { if (g === gen) emit("onBuffering", false); });
      } catch (e) {}
    },
    stop: function () { try { this.p().stop(); } catch (e) {} },
    close: function () {
      this.active = false;
      try { this.p().stop(); } catch (e) {}
      try { this.p().close(); } catch (e) {}
    },
    position: function () { try { return this.p().getCurrentTime() || 0; } catch (e) { return 0; } },
    duration: function () { try { return this.p().getDuration() || 0; } catch (e) { return 0; } },
  };

  // The platform takes the decoder away from a hidden app; hand it back politely.
  document.addEventListener("visibilitychange", function () {
    if (!avplay.active) return;
    try {
      if (document.hidden) avplay.p().suspend();
      else avplay.p().restore();
    } catch (e) {}
  });

  // ── html5 + hls.js ───────────────────────────────────────────────────────
  var hlsWaiters = null;
  function loadHls(done) {
    if (window.Hls) return done();
    if (hlsWaiters) return hlsWaiters.push(done);
    hlsWaiters = [done];
    var s = document.createElement("script");
    s.src = "js/vendor/hls.min.js";
    s.onload = s.onerror = function () {
      var w = hlsWaiters; hlsWaiters = null;
      w.forEach(function (f) { f(); });
    };
    (document.head || document.documentElement).appendChild(s);
  }

  var html5 = {
    name: "html5",
    v: null,
    hls: null,
    g: -1,
    video: function () {
      if (this.v) return this.v;
      var v = document.createElement("video");
      v.id = "vp-video";
      v.setAttribute("playsinline", "");
      v.style.cssText = "position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:0;background:#000;object-fit:contain;";
      var self = this;
      var on = function (name, f) { v.addEventListener(name, function (e) { if (self.g === gen) f(e); }); };
      on("playing", function () { setState("playing"); emit("onBuffering", false); });
      on("pause", function () { if (!v.ended) setState("paused"); });
      on("waiting", function () { emit("onBuffering", true); });
      on("seeked", function () { emit("onBuffering", false); });
      on("timeupdate", function () { emit("onTime", Math.round(v.currentTime * 1000)); });
      on("ended", function () { setState("ended"); emit("onEnded"); });
      on("error", function () {
        if (self.hls) return; // hls.js reports its own fatal errors
        fail("MEDIA_ERROR_" + (v.error ? v.error.code : "?"), self.g);
      });
      document.body.insertBefore(v, document.body.firstChild);
      this.v = v;
      return v;
    },
    open: function (url, startMs, g) {
      var self = this;
      this.teardown();
      this.g = g;
      var v = this.video();
      v.style.display = "";
      emit("onBuffering", true);
      loadHls(function () {
        if (g !== gen) return;
        var start = Math.max(0, startMs) / 1000;
        // hls.js before canPlayType: Chrome claims "maybe" for HLS and then
        // fails to demux (the same order the web app uses).
        if (window.Hls && window.Hls.isSupported()) {
          var hls = new window.Hls({ startPosition: start > 0 ? start : -1 });
          self.hls = hls;
          hls.on(window.Hls.Events.ERROR, function (_e, d) {
            if (d && d.fatal) fail((d.type || "hls") + ": " + (d.details || ""), g);
          });
          hls.on(window.Hls.Events.MANIFEST_PARSED, function () {
            if (g !== gen) return;
            var pr = v.play();
            if (pr && pr.catch) pr.catch(function () {});
          });
          hls.loadSource(url);
          hls.attachMedia(v);
        } else if (v.canPlayType("application/vnd.apple.mpegurl")) {
          v.src = url;
          v.addEventListener("loadedmetadata", function once() {
            v.removeEventListener("loadedmetadata", once);
            if (start > 0) v.currentTime = start;
          });
          var pr = v.play();
          if (pr && pr.catch) pr.catch(function () {});
        } else {
          fail("HLS_UNSUPPORTED", g);
        }
      });
    },
    teardown: function () {
      if (this.hls) { try { this.hls.destroy(); } catch (e) {} this.hls = null; }
      if (this.v) {
        try { this.v.pause(); } catch (e) {}
        this.v.removeAttribute("src");
        try { this.v.load(); } catch (e) {}
      }
    },
    play: function () { if (this.v) { var p = this.v.play(); if (p && p.catch) p.catch(function () {}); } },
    pause: function () { if (this.v) this.v.pause(); },
    seek: function (ms) { if (this.v) this.v.currentTime = Math.max(0, ms) / 1000; },
    stop: function () { if (this.v) this.v.pause(); },
    close: function () {
      this.teardown();
      this.g = -1;
      if (this.v) this.v.style.display = "none";
    },
    position: function () { return this.v ? Math.round(this.v.currentTime * 1000) : 0; },
    duration: function () {
      var d = this.v ? this.v.duration : 0;
      return isFinite(d) && d > 0 ? Math.round(d * 1000) : 0;
    },
  };

  // ── mock ─────────────────────────────────────────────────────────────────
  var TICK_MS = 250;
  var mock = {
    name: "mock",
    pos: 0,
    dur: 0,
    playing: false,
    timer: null,
    pending: null,
    opens: 0,
    clear: function () {
      clearInterval(this.timer); this.timer = null;
      clearTimeout(this.pending); this.pending = null;
    },
    shouldFail: function (url) {
      var mode = ls("streamhub.mockPlayerFail") || "";
      if (mode === "all") return true;
      if (mode === "once") return this.opens === 1;
      if (mode === "direct") return url.indexOf("/manifest") !== -1;
      return false;
    },
    open: function (url, startMs, g) {
      var self = this;
      this.clear();
      this.opens += 1;
      this.playing = false;
      this.pos = Math.max(0, startMs);
      this.dur = 0;
      emit("onBuffering", true);
      var failing = this.shouldFail(url);
      this.pending = setTimeout(function () {
        if (g !== gen) return;
        if (failing) { emit("onBuffering", false); fail("MOCK_SOURCE_ERROR", g); return; }
        self.dur = (Number(ls("streamhub.mockDuration")) || 1500) * 1000;
        if (self.pos >= self.dur) self.pos = 0;
        emit("onBuffering", false);
        self.playing = true;
        setState("playing", g);
        emit("onTime", self.pos);
        self.timer = setInterval(function () {
          if (g !== gen || !self.playing) return;
          var rate = Number(ls("streamhub.mockRate")) || 1;
          self.pos = Math.min(self.dur, self.pos + TICK_MS * rate);
          emit("onTime", self.pos);
          if (self.pos >= self.dur) {
            self.playing = false;
            clearInterval(self.timer); self.timer = null;
            setState("ended", g);
            emit("onEnded");
          }
        }, TICK_MS);
      }, 400);
    },
    play: function () { if (this.dur > 0 && state !== "ended") { this.playing = true; setState("playing"); } },
    pause: function () { if (this.dur > 0 && state !== "ended") { this.playing = false; setState("paused"); } },
    seek: function (ms) {
      if (!this.dur) return;
      this.pos = Math.max(0, Math.min(this.dur - 1000, ms));
      emit("onBuffering", true);
      emit("onTime", this.pos);
      var g = gen;
      setTimeout(function () { if (g === gen) emit("onBuffering", false); }, 150);
    },
    stop: function () { this.playing = false; this.clear(); },
    close: function () { this.playing = false; this.clear(); this.dur = 0; this.pos = 0; },
    position: function () { return this.pos; },
    duration: function () { return this.dur; },
  };

  function pick() {
    if (ls("streamhub.mockPlayer") === "1") return mock;
    if (window.webapis && window.webapis.avplay) return avplay;
    return html5;
  }

  window.StreamHubPlayer = {
    opened: opened,
    open: function (url, startMs) {
      gen += 1;
      var g = gen;
      state = "idle";
      setState("loading", g);
      whenWebapis(function () {
        if (g !== gen) return;
        var next = pick();
        if (current && current !== next) current.close();
        current = next;
        opened.push({ url: String(url), startMs: Number(startMs) || 0, backend: next.name });
        next.open(String(url), Number(startMs) || 0, g);
      });
    },
    play: function () { if (current) current.play(); },
    pause: function () { if (current) current.pause(); },
    seek: function (ms) { if (current) current.seek(Number(ms) || 0); },
    stop: function () { if (current) current.stop(); },
    close: function () {
      gen += 1;
      if (current) current.close();
      current = null;
      state = "idle";
    },
    position: function () { return current ? Math.max(0, Math.round(current.position())) : 0; },
    duration: function () { return current ? Math.max(0, Math.round(current.duration())) : 0; },
    state: function () { return state; },
    backendName: function () { return (current || pick()).name; },
    setListener: function (l) { listener = l || {}; },
  };
})();
