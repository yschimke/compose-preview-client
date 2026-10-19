# compose-preview-client

Mobile + Wear **session-viewer** clients for
[`compose-preview serve`](https://github.com/yschimke/compose-ai-tools)'s
streaming lane.

Tap a session link → an Android / Wear OS app opens, connects to a live
`compose-preview serve` instance over `WS /ws/{previewId}`, paints the pushed
PNG frames, and forwards your pointer / key / rotary input back into the running
composition — so a remote preview behaves like a complete local app.

They are also a **device preview** for UI-builder **`.uid` designs** and Remote Compose **`.rc`
documents**: a `.rc` (or a design that compiles to one, compiled for this device's screen) plays on
the device in the AndroidX Remote Compose player, and follows edits live. See
[docs/DEVICE-PREVIEW.md](docs/DEVICE-PREVIEW.md).

These apps used to live in `yschimke/compose-ai-tools` as `:clients:{core,mobile,wear}`.
They were split out because they're a distinct **interactive streaming** product
(with its own Google Play publishing pipeline), separate from that repo's core
mission of headless *render-`@Preview`-to-PNG*
([compose-ai-tools#2533](https://github.com/yschimke/compose-ai-tools/issues/2533)).

## Modules

| Module | What it is |
|---|---|
| **`:core`** | Pure-JVM engine — the WS frame protocol, the connection state machine, the `composeai://` session-link parser, the mDNS discovery contract, and the device-preview engines (UI-builder client, `.rc` source, link parser). No Android, no Compose, so it's unit-tested headlessly. |
| **`:player`** | Android library both apps share — the AndroidX Remote Compose player in Compose, the device profile, local files, and the `DevicePreview` host. |
| **`:mobile`** | Android phone/tablet app. Compose **Material 3**. |
| **`:wear`** | Wear OS app. **Wear Compose Material 3**, with rotary-bezel input. |

`:core` depends on **no** internal module and consumes `serve` purely through a
[versioned wire contract](https://github.com/yschimke/compose-ai-tools/blob/main/docs/serve/SESSION-VIEWER-PROTOCOL.md)
(WS JSON frame protocol + session-link format + `_composeai._tcp` mDNS) owned by
`compose-ai-tools` — never a code dependency.

## Docs

- [docs/SESSION-VIEWER.md](docs/SESSION-VIEWER.md) — architecture, session links, discovery, input.
- [docs/DEVICE-PREVIEW.md](docs/DEVICE-PREVIEW.md) — `.uid` designs and `.rc` documents on the device.
- [docs/PUBLISHING.md](docs/PUBLISHING.md) — the Google Play publishing pipeline.

## Building

```sh
./gradlew :core:test          # pure-JVM engine tests
./gradlew :player:testDebugUnitTest   # the bundled .rc samples decode
./gradlew :mobile:assembleDebug :wear:assembleDebug   # needs an Android SDK
```

The mobile/wear `@Preview` chrome is rendered by the
[`ee.schimke.composeai.preview`](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
plugin (consumed from Maven Central) to regenerate the Play-store screenshots —
see [docs/PUBLISHING.md](docs/PUBLISHING.md).

## Conventions

See [CLAUDE.md](CLAUDE.md). Branches are `agent/…`; commits + PR titles follow
Conventional Commits; git history is never attributed to an AI agent. Install the
hooks with `scripts/install-git-hooks.sh`.
