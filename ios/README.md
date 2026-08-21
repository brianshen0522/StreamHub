# StreamHub for iOS & iPadOS

One Xcode project, one app target. iPad is not a separate app — it is the same
target with an adaptive layout.

Read [`shared/api/README.md`](../shared/api/README.md) before writing any
networking code — it holds the API behaviour that every client has to get right.

---

## Creating the project

Create it **into this directory** with Xcode's New Project wizard (SwiftUI app,
iPhone + iPad destinations) so the ignore rules and the Docker build context
exclusions already in place apply from the first commit. Xcode 26.6 and Swift
6.3 are installed.

## The one rule that matters: no custom chrome

Build with stock SwiftUI components against the current SDK and the app wears the
system's own design — including the current material treatment — with dark mode,
Dynamic Type and accessibility included for free. Custom-skinned controls are
exactly what makes an app feel like a wrapped web page. The single brand decision
worth making is the accent color.

| Concern | Use |
|---|---|
| Navigation | `NavigationStack` with large titles; the web portal's rail becomes a tab bar |
| Search | the `.searchable` modifier, not a hand-built field |
| Provider filter | segmented control or search scopes |
| Episode / source picking | sheets with detents |
| Poster long-press | context menu with preview — play, resume, mark watched |
| Icons | SF Symbols |
| Colors and surfaces | semantic system colors and materials |
| Player | `AVPlayerViewController` |

`AVPlayerViewController` is worth calling out: it brings gestures, AirPlay,
Picture in Picture, background audio, and lock-screen and Control Center
integration with no work. A custom player UI here is a permanent maintenance cost
for a worse result.

## iPadOS is a layout, not a port

Use `NavigationSplitView` — the web portal's collapsible rail maps onto its
sidebar one-to-one — and drive grid columns from **size classes, never device
checks**, because Split View and Stage Manager resize the window arbitrarily. Add
pointer hover effects on cards, and keyboard shortcuts: space for play/pause,
arrows to seek, `⌘F` to search.

## Playback

AVPlayer plays HLS natively, so hand it `/api/manifest?target=<source url>` and
the rest is ordinary playback. That endpoint is what keeps native playback
ad-free — AVPlayer cannot run the browser's `pLoader` filter — and it returns
absolute CDN URLs, so segments never touch the server.

Attach the auth header to the **asset**, via `AVURLAssetHTTPHeaderFieldsKey`, not
to a one-off request: a master playlist sends AVPlayer back to `/api/manifest`
for the variant it picks.

## Signing

There is no App Store in this plan, so installation is a signed build from Xcode.
A free Apple ID signature expires every 7 days and the app stops launching; a paid
developer account signs for a year. That yearly fee is the entire cost of skipping
the App Store.

Certificates and provisioning profiles are git-ignored and must stay that way.
