# Casting: phone → television

How the phone drives a television, what the television has to implement to be
driven, and what is and is not possible for waking a closed app.

The phone half is built and verified. The television half is a contract this
document specifies; `:tv` implements it.

---

## 1. The model

**Being connected to a device is app-wide state, not something the player
owns.** Pick a television once and *every* Play goes there until you say
otherwise. This is how Spotify and Netflix behave, and it is worth stating
because the obvious alternative — a "cast this" action attached to each Play —
is worse in a specific way: it lets the phone and the television end up playing
different things, and it asks the same question every time.

Consequences that fall out of it:

- The cast button is a mode indicator, not a verb. Filled means "playback goes
  to the television".
- The device picker can be opened from the detail screen *before* choosing
  anything to play. Picking a device there arms the next Play rather than doing
  something immediately.
- Leaving the remote screen does not end the cast, so there has to be a
  persistent way back — the bar above the tab bar.

**There is no pairing, and nothing is discovered on the local network.** Both
devices signed in to the same account; the server only routes a command between
two sockets belonging to the same user. Holding the account *is* the
authorization. This also means the phone and the television do not have to be
on the same Wi-Fi, which local discovery protocols would require.

### Screens and states

| Where | What appears | When |
|---|---|---|
| Detail, top bar | cast button | a receiver is connected, or already casting |
| Player, top bar | cast button | same — moves the running playback across |
| Any tab screen | cast bar above the tabs | while connected, except on the remote screen |
| Remote screen | the full controller | while connected |

The cast button **renders nothing** when there is nowhere to cast to. A control
that is always present but usually does nothing teaches people to ignore it;
this one appearing is itself the signal that a television is on.

The picker lists three groups:

1. **This phone** — always, so there is a way back to local playback.
2. **Connected receivers** — with what each is playing, if anything.
3. **Not connected** — televisions signed in to the account whose app is not
   running. They cannot be cast to, and saying so is the point: without that
   line, "why is my television missing" has no visible answer and the list looks
   broken rather than accurate. Deduplicated by device name and capped, because
   several sign-ins from one television produce several sessions and rows
   sharing a name are indistinguishable to whoever is reading them.

### Two details that are easy to get wrong

**The remote screen must interpolate position locally.** The receiver reports
about once a second; a bar driven only by those reports ticks in visible steps.
It advances locally between reports and only while playing — a paused position
that crept forward would be a lie about the television.

**A scrub has to survive until the receiver confirms it.** After a scrub the
phone holds the scrubbed position until a report arrives near it (or four
seconds pass). Without that, the bar snaps back to the last reported position
for about a second and reads as a failed seek.

---

## 2. The wire contract

Everything rides the existing `/api/realtime` socket. No second connection, no
second auth path, no second thing that can be down.

### Becoming a receiver

After the `ready` frame, send:

```json
{ "type": "playback", "state": null }
```

**Sending this frame at all is the announcement.** An idle television must send
it with a null state on startup, or it never appears as a cast target.

Then send it again whenever playback changes, and about once a second while
playing:

```json
{ "type": "playback", "state": {
    "provider": "movieffm", "itemUrl": "…", "title": "…", "subtitle": "…",
    "posterUrl": "…", "episodeLabel": "01",
    "positionMs": 12000, "durationMs": 2700000,
    "paused": false, "buffering": false } }
```

Faster than one frame per 250 ms is dropped by the server.

### Receiving commands

```json
{ "type": "command", "from": "<session id>", "fromName": "Pixel 7",
  "command": { "action": "play", "playback": { "streamUrl": "…", "positionMs": 0, … } } }
```

Actions: `play`, `pause`, `resume`, `seek` (`positionMs`), `stop`, `next`.
An action the build does not know must be ignored, not treated as an error.

`streamUrl` is the provider's direct URL. The receiver resolves its own
`/api/manifest` or `/api/stream` URL from it with its own token — the phone
never sends credentials across.

### On Kotlin

`:core` has all of it: `CastCommand`, `CastPlayRequest`, `CastPlaybackState`,
`CastReceiver`, plus `RealtimeClient.publishPlayback(state)` /
`sendCommand(to, command)` and the `RealtimeEvent.Receivers` /
`RealtimeEvent.Command` events. The receiver side needs no new serialization.

**Do not give frame properties default values.** kotlinx.serialization omits a
property that equals its default, so `state: CastPlaybackState? = null` would
send `{"type":"playback"}` with the key missing. This already cost this app a
realtime socket that never connected. `CastFrameTest` pins both this and the
flat `action` discriminator, because both fail silently.

---

## 3. Waking a television whose app is closed

The question was whether we can get the Spotify-Connect behaviour: the app is
closed, you pick the device on your phone, and it comes to life.

### What does not work here

**Google Cast** needs a receiver application ID registered with Google, and the
receiver is a web app running in the Cast runtime — our ExoPlayer television app
cannot be the receiver. It would mean rebuilding playback in JavaScript, and
registering the app. Rejected.

**DIAL** — the protocol YouTube and Netflix actually use to launch a closed app
on a television — requires the app name to be in the DIAL registry. An
unregistered name returns 404 from the television's DIAL server. Rejected for a
sideloaded app.

**FCM high-priority messages** would work: a high-priority message is an
explicit exemption from the background-start restrictions, and FCM does not
require the app to be distributed through Play — only that Google Play services
is on the device. Two things argue against it as the primary mechanism. FCM
downgrades high-priority messages for apps that use them without producing
user-visible notifications, and once downgraded, starting a foreground service
throws. And delivery to Android TV in standby is reported as unreliable. It is
a reasonable *secondary* path, not a foundation.

### What does work

**A resident foreground service on the television, started at boot, holding the
socket.** Fully self-contained: no third-party registry, no Firebase project, no
network discovery. The television then simply *is* a connected receiver whenever
it has power, and the existing `play` command is the wake mechanism — no new
protocol at all.

Two constraints to build against:

- Android 15 blocks `dataSync`, `camera`, `mediaPlayback`, `phoneCall`,
  `mediaProjection` and `microphone` foreground services from being started by a
  `BOOT_COMPLETED` receiver. `connectedDevice` is not on that list and is the
  honest description of what the service does, so it is the type to use.
- A service still cannot start an Activity from the background without an
  exemption. For a sideloaded app the practical one is `SYSTEM_ALERT_WINDOW`
  ("Display over other apps"), granted once in Settings. Worth surfacing in the
  television app's setup as the thing that makes casting work, rather than
  letting it fail silently later.

### What nothing solves

If the television box itself is powered off, no software on it can respond —
that needs Wake-on-LAN. If the box is on and only the panel is in standby,
HDMI-CEC one-touch-play generally wakes the panel and switches input when
playback starts, which is the common case and comes for free.

The phone's UI should therefore never promise more than this: a signed-in
television that is not connected is shown greyed out with "Open StreamHub on
this device", and once the resident service exists that line will simply stop
appearing for televisions that have power.
