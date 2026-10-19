# Device preview: `.uid` designs and `.rc` documents

The apps open two more kinds of link besides a running preview's frame stream, and show each as if
it were the app it previews: full screen, edge to edge, at this device's real size, density, font
scale, theme and locale, following changes as they are made.

| Opened | How it's shown | Follows changes |
|---|---|---|
| A **`.rc`** document (URL or file) | Played on the device by the AndroidX Remote Compose player | URL: polled with `ETag` / `Last-Modified`, repainted when the bytes change |
| A **`.uid`** design on a UI builder | Compiled to `.rc` **for this device**, then played on the device. If the design can't be expressed as a document, the host's native Compose render is streamed instead | The design's `updates` socket: each new revision reloads |
| A **`.uid`** file | Compiled by the configured UI builder for this device, then played; a still render if it can't be | — |
| A **catalog preview** (`/<catalog>/p/<id>`, `…/render/<id>.png`) | Its `…/render/<id>.rc`, served beside the `.png`, **played on the device**. A preview that isn't Remote Compose (the `.rc` is 404) streams live when signed in, and otherwise shows the `.png` | The `.rc` is re-fetched every 10 s (the catalog sends no validators) |
| An **uploaded document** (`/d/<id>`) | `/d/<id>/raw` played on the device | Polled, until it expires on the server |

## Why play on the device

A `.rc` document is what a host app ships, and the player in `:player` is the same
`RemoteComposePlayer` a host embeds. Playing it here means touches, scrolling, animation, state and
clicks run on the device, with no round trip, and what's drawn is what a user would see. The
document's host actions are shown briefly in the chrome (`action <id> · <payload>`), since a preview
has no host to route them to.

Each player is primed before it's shown: one off-screen measure, layout and draw at the screen's
size, then a forced re-measure. The AndroidX player only measures a document once its paint context
exists, and the first draw creates it. Without priming, a document whose layout depends on measuring
(the catalog's alarm card, a `StateLayout`) draws its first frame blank.

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

A `401`/`403` asks the device to [sign in](#signing-in); a `404` on the design says it doesn't
exist.
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

## Your own documents in the UI builder

The phone's **Share a .rc of your own** uploads a document to the configured server's document lane
(`POST /docs`) and hands back its permalink, `https://<server>/d/<id>`. That one URL:

- plays in the app on any device (paste or tap it);
- plays in a browser;
- plays inside a UI-builder design: a `remote-compose/document` node takes it as its `documentUrl`,
  the same shape the builder's own inline captures publish.

Uploads expire on the server's schedule. A `--public` server takes them anonymously. A private one
needs its operator token in the connect screen, because the server's ingest gate refuses sign-in
grants on purpose, so a preview grant can never publish anything.

## Signing in

Private designs, and the native render's stream, need a credential. The apps get one with the
server's own **agent access grant**, an RFC 8628 device grant (`/agent-access/…`, see
compose-preview-server's `AGENT_ACCESS_GRANTS.md`), so nobody pastes the operator token into a
phone:

1. A preview the server refuses (401/403) shows **Sign in to <server>**.
2. The app `POST`s `/agent-access/request` for scope `live` and capabilities `ui-builder-read`,
   `ui-builder-export`: enough to read and compile a design and stream its render, never to edit
   it. The label names the device ("Compose Device Preview on Google Pixel 9").
3. The approval page opens in the browser. On the watch it opens **on the paired phone**. The app
   shows the user code to check against the page. It builds the page URL from the server it asked,
   and never follows a URL from the reply.
4. The person approves, signed in to GitHub (or holding the operator token) in that browser. The
   app's held poll (`/agent-access/poll`, 8 s waits) collects the token, stores it for that server
   and reopens the preview with it.

Every later link to that server uses the stored grant until it expires (8 h by default, the
server's ceiling). An expired grant, or one that doesn't reach a design, asks again. The phone's
connect screen shows who approved the grant and when it expires, with **Sign out**, which revokes
it on the server. Grants are kept in the app's private preferences and the apps opt out of backup.
A server restart drops every grant, so the next preview asks to sign in again.

An operator token typed into the connect screen, or a link's own `?token=`, still takes precedence.

## Links

| Shape | Opens |
|---|---|
| `https://H/ui-builder/<designId>` (the editor permalink), `…/ui-builder/<catalog>/<designId>` | design |
| `https://H/api/ui-builder/v1/designs/<designId>/…` (any design route, including its `export.rc`) | design, still followed live |
| `composeai://design?host=H[&port=P][&secure=true]&id=<designId>[&token=T][&revision=N]` | design |
| `https://H/<catalog>/p/<id>`, `https://H/<catalog>/render/<id>.png` | catalog preview, played from its `.rc` |
| `https://H/d/<id>` | uploaded document |
| `https://H/…/<name>.rc[?token=T]` | `.rc` document |
| `composeai://rc?src=<url>[&token=T]` | `.rc` document |
| `content://…`, `file://…` | local `.rc`, or `.uid` by file name |
| every [session link](SESSION-VIEWER.md#session-links) | frame stream |

`?token=` and `?revision=` work on the `https` forms. The apps claim `https` links only on
`preview.coo.ee` and `ui.coo.ee`, under `/ui-builder/`, `/d/` and `/<catalog>/p/`. Paste any other
server's link into the connect screen.

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
