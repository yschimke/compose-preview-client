# Device preview: `.uid` designs and `.rc` documents

The apps open two more kinds of link besides a running preview's frame stream, and show each as if
it were the app it previews: full screen, edge to edge, at this device's real size, density, font
scale, theme and locale, following changes as they are made.

| Opened | How it's shown | Follows changes |
|---|---|---|
| A **`.rc`** document (URL or file) | Played on the device by the AndroidX Remote Compose player | URL: polled with `ETag` / `Last-Modified`, repainted when the bytes change |
| A **`.uid`** design on a UI builder | Compiled to `.rc` **for this device**, then played on the device. If the design can't be expressed as a document, the host's native Compose render is streamed instead | The design's `updates` socket: each new revision reloads |
| A **`.uid`** file | Compiled by the configured UI builder for this device, then played; a still render if it can't be | — |

## Why play on the device

A `.rc` document is what a host app ships, and the player in `:player` is the same
`RemoteComposePlayer` a host embeds. Playing it here means touches, scrolling, animation, state and
clicks run on the device, with no round trip, and what's drawn is what a user would see. The
document's host actions are shown briefly in the chrome (`action <id> · <payload>`), since a preview
has no host to route them to.

## A `.uid` design, step by step

`DesignPreview` (`:core`) tries, for each load:

1. **Compile for this device.** `GET /api/ui-builder/v1/designs/{id}` reads the document; its
   `environment` is pointed at the device (`widthDp`, `heightDp`, `density`, `fontScale`, `theme`,
   `locale`, `layoutDirection`; everything else kept), and
   `POST /api/ui-builder/v1/documents/export.rc` compiles it. Nothing is saved: that route never
   writes a design or a revision.
2. **The authored export**, `GET …/designs/{id}/export.rc`, for a host that can't take a document.
   The player scales it.
3. **The native render**, `POST …/designs/{id}/native-preview`. When the reply names a live session,
   frames stream from `ws(s)://host/{sessionId}/ws/{previewId}` and the device is sent as
   `setOverrides` (`widthPx`, `heightPx`, `density`, `fontScale`, `uiMode`, `localeTag`) on every
   (re)connect and device change. Touches are forwarded. Without a live lane, the first frame is
   shown as a still.

A `401`/`403` stops with "open the link with a token"; a `404` on the design says it doesn't exist.
A reload that fails after something is showing keeps the last good preview up and shows the problem
as a notice.

Edits arrive on `WS …/designs/{id}/updates`. Presence, operation outcomes and revisions the device
already shows are ignored; anything else reloads (debounced). The socket reconnects on its own. A
link pinned to a `?revision=` doesn't follow.

Credentials ride the `X-Compose-Preview-Token` header on HTTP, and `?token=` on WebSockets.

These are compose-preview-server's UI-builder routes (`ServeUiBuilderRoutes`,
`ServeUiBuilderExportRoutes`), consumed over the wire like the stream lane: no code dependency.

### On the hosted builder today

`preview.coo.ee` doesn't have the Remote Compose export lane enabled, so `export.rc` routes answer
`404` there and designs take step 3. A host started with the Remote Compose export lane enabled
serves steps 1 and 2.

## Links

| Shape | Opens |
|---|---|
| `https://H/ui-builder/<designId>` (the editor permalink), `…/ui-builder/<catalog>/<designId>` | design |
| `https://H/api/ui-builder/v1/designs/<designId>/…` (any design route, including its `export.rc`) | design, still followed live |
| `composeai://design?host=H[&port=P][&secure=true]&id=<designId>[&token=T][&revision=N]` | design |
| `https://H/…/<name>.rc[?token=T]` | `.rc` document |
| `composeai://rc?src=<url>[&token=T]` | `.rc` document |
| `content://…`, `file://…` | local `.rc`, or `.uid` by file name |
| every [session link](SESSION-VIEWER.md#session-links) | frame stream |

`?token=` and `?revision=` work on the `https` forms. The phone app claims `https` links only for
`preview.coo.ee` and `ui.coo.ee` under `/ui-builder/`; paste any other server's link into the
connect screen.

## The phone app

- **Open a .rc or .uid file**: the system file picker. Files opened from a file manager or a chat
  attachment arrive the same way.
- **UI builder for .uid files**: the server a local `.uid` is compiled on (default
  `https://preview.coo.ee`), and an optional token.
- **Samples**: two bundled `.rc` documents (from `homeassistant-remotecompose`), to check the player
  with no server.

The screen stays on while a preview is open. Back leaves.

## Modules

| Module | Adds |
|---|---|
| `:core` (`preview/`) | `PreviewLink`, `DeviceProfile`, `UiBuilderApi`, `RemoteDocumentSource`, `DesignPreview`: pure JVM, tested against Ktor's mock engine |
| `:player` | `RemoteDocumentPlayer` (the AndroidX player in Compose), `rememberDeviceProfile`, `LocalFiles`, and `DevicePreview`, which ties them together with slots for each app's stream screen and chrome |
