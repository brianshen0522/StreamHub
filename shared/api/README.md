# API notes for client authors

Behaviour every StreamHub client has to get right, kept in one place so the web,
Android and iOS clients cannot quietly disagree about it. This is prose, not a
schema — a generated `openapi.json` belongs here too and is not written yet.

The server is Express on port 8787. Every route is reachable at both `/api/…`
and **`/api/v1/…`**, which are the same routes — the version prefix is rewritten
away before routing. The web app uses the unversioned paths; **clients should
pin themselves to `/api/v1`**, so the unversioned surface stays free to change
without breaking a build already sitting on a phone or a TV, where nothing
pushes updates.

`GET /api/health` answers `{ ok: true, apiVersion: 1 }`, which is the cheapest
way for a client to check what it is talking to.

## Telling one failure from another

An empty search looks identical whether the server is unreachable, a provider is
switched off, or the site simply has no match. Three endpoints separate them:

- `GET /api/health` — is the server there, and how long did it take.
- `GET /api/me/providers` — each provider's `status` from the server's own
  health poller (`HEALTHY`, `DEGRADED`, `DOWN`, `DISABLED`), plus
  `responseTimeMs`, `lastCheckedAt` and `errorMessage`. By default it returns
  only providers this account can search; add **`?all=true`** to get the ones
  that are switched off or not permitted too, which is exactly what a status
  view needs and a search filter does not.
- The realtime socket — live sync being down is a different problem from the
  server being unreachable, and should not be reported as the same thing.

One caveat on provider health: the poller checks a site by running a real
search, and that search is cached for five minutes, so every poll after the
first answers from cache with a response time near zero. Treat a very low
`responseTimeMs` as "not actually measured" rather than "extremely fast".

---

## Auth

`POST /api/auth/login` takes `{ login, password }` — the field is `login`, not
`username`, and it matches against either username or email. It answers
`{ user, accessToken, refreshToken }`, all three at the top level. No cookies are
set; this is a pure bearer-token API, which is why no native client needs a
compatibility layer.

Access tokens are HS256 JWTs valid for 4 hours. Refresh tokens are opaque and
valid for 30 days, and **refresh rotates**: `POST /api/auth/refresh` with
`{ refreshToken }` returns a new pair and immediately invalidates the old refresh
token. A client that fires two refreshes concurrently will kill its own session,
so the refresh must be single-flight — one in-flight request that every waiting
caller awaits. The web client does this in `frontend/src/api.js`.

**Identify yourself and the server will refuse admin accounts for you.** Send
`X-StreamHub-Client: <android|tv|ios>` on every request. Login and refresh then
answer `403` with a message fit to show on the login screen, instead of handing
back a working token for an account that would `403` on every content route
afterwards. The web app does not send the header, because its login form is
shared with the admin console.

Send it anyway even though the rule is enforced server-side, and still check
`user.role === "USER"` before storing a session — the header is what activates
the refusal, and a client that forgets it gets the old behaviour: a valid
session and an app that fails on every screen.

`requireAuth` cannot distinguish an expired token from a malformed one; both are
a plain `401`. The correct client behaviour is to attempt one refresh on any
`401` and retry the request once, then give up and sign out.

`POST /api/auth/heartbeat` bumps the user's last-seen time, which is what the
admin console's "online" view counts within a 120-second window. Poll it roughly
every 30–60 seconds while the app is foregrounded.

## Errors

Every error is `{ "error": string }`. Two shapes carry more: validation failures
are `400` with `{ error: "Validation failed.", details: [...] }`, and a unique
constraint violation is `409 Duplicate value.`

`GET /api/search` is the exception that catches people out: it fans out to the
providers with per-provider failure isolation and **returns 200 even when a
provider fails**. Check the `error` field on each entry of `results`, not the
HTTP status.

## Media

`GET /api/sources` streams **NDJSON** — one JSON object per line, written as each
source finishes its health probe. Parse it incrementally; waiting for the body to
close throws away the whole point. Failing sources are simply never emitted.
`preferredLabel` biases which source is probed first but guarantees nothing about
arrival order. Each line carries `directUrl` and `proxyUrl`; prefer `directUrl`,
since direct CDN access works without a Referer header and keeps the server out
of the video path.

`durationSeconds` on those lines already has detected ad segments subtracted, so
it matches the playable timeline rather than the raw manifest.

`GET /api/manifest?target=<url>` is what a **native player should be handed**,
not the raw source URL. It returns the playlist with spliced ad runs removed and
every segment, key and init-map URL made absolute, so playback is ad-free while
segments stream device-to-CDN — only the manifest passes through the server.
Point AVPlayer or ExoPlayer at it and the rest is ordinary HLS.

Two things follow from that. A master playlist comes back with each variant and
rendition pointed at `/api/manifest` again, so the player will make a second
authenticated request to this server when it picks a variant — the auth header
has to be set for every request on the asset, not just the first
(`AVURLAssetHTTPHeaderFieldsKey` on iOS, `setDefaultRequestProperties` on
Media3). And the response body deliberately contains **no access token**, unlike
`/api/stream`, so nothing sensitive lands in a cache.

The response carries `x-adfilter-removed-seconds`, and `x-adfilter-reason` when
nothing was removed — useful when a source's runtime looks wrong.

`GET /api/item` is **polymorphic** and returned bare, not wrapped. Discriminate on
which key is present:

| Key present | Meaning | Follow-up call |
|---|---|---|
| `seasons` | movieffm TV hub | `/api/item` or `/api/episodes` on the chosen season URL |
| `episodes` | a season or a single-page series | `/api/sources` per episode |
| `streams` | a movie | `/api/check-sources` |

Episodes are plain strings (`"EP1"`, `"第3集"`), not objects.

`GET /api/stream` and `GET /api/poster` accept the access token as an
`?accessToken=` query parameter as well as a header. That exists because
`hls.js` cannot attach headers to segment requests. Native clients set a proper
`Authorization` header and should not use the query form — but note that when
playback falls back to the proxy, the server rewrites every segment URL in the
manifest to carry the token in the query string regardless.

Posters sit behind auth, so a plain unauthenticated image load will fail. The
image loader needs the bearer header (Coil and Kingfisher both support this) or
the query parameter.

## Realtime

A WebSocket at `/api/realtime` carries per-user cache invalidation only — no media
data. Authentication happens in the **first frame**, not the query string, and
must arrive within 5 seconds:

```json
{ "type": "auth", "token": "<accessToken>" }
```

The server answers `{ "type": "ready", "expiresAt": <ms epoch> }` and then ignores
everything the client sends. Events are `favorites` (`added` / `removed`) and
`progress` (`updated` / `removed`); a `progress.updated` frame carries
`history: boolean` telling the client whether history also needs refetching.

Close codes matter: `4001` auth timeout, `4003` unauthorized, and **`4002` access
token expired** — the server closes the socket precisely when the JWT expires.
The response to `4002` is to refresh the token *first* and only then reconnect,
otherwise the client reconnect-loops until the refresh token dies too.

## Library data

Progress positions are **integer seconds** — round before sending.
`progressPercent` and `isCompleted` are derived server-side; do not send them.

`DELETE /api/me/progress` takes a **JSON body**, which some native HTTP stacks
discourage on DELETE. Confirm your client actually sends it. Its `scope: "title"`
mode deletes by `(providerKey, title)` and deliberately ignores `itemUrl`, which
is what dismissing a continue-watching card needs, because a card stands for a
whole title while providers like dramasq give every episode its own `itemUrl`.

`GET /api/me/continue-watching` is not a plain list: it collapses progress rows to
one item per title and adds `nextUp`, `episodesTouched` and `episodesCompleted`.
When `nextUp` is true the last-watched episode is finished and the client should
resolve the *next* episode from the episode list on arrival.

`/api/me/history` and `/api/me/progress` are capped at 200 rows with **no
pagination**, so a client cannot page further back than that.

Favorites are keyed per episode, not per title:
`(userId, providerKey, itemUrl, seasonUrl, episodeLabel)`, with absent values
coerced to `""`. Send those fields consistently or you will create duplicate rows.
