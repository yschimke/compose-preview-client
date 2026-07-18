# Publishing the session-viewer apps

The mobile + wear session-viewer apps (`:mobile`, `:wear`) ship to the Google
Play **internal** track via [Gradle Play Publisher][gpp] (GPP), driven by
[`.github/workflows/release.yml`](../.github/workflows/release.yml). The setup
mirrors [`yschimke/homeassistant-remotecompose`][harc]'s release pipeline.

## How a release happens

**Normal path — release-please.** Conventional commits keep a release PR updated;
merging it bumps the `appVersionName` in both modules, tags `v<version>`, and
`release-please.yml` calls the publish workflow via `workflow_call`. One button,
no manual tag.

**Escape hatches.** Push a `v<version>` tag by hand, or run the **Release apps**
workflow manually with a tag input.

The publish job decodes the signing keystore, runs
`:{mobile,wear}:assembleRelease` + `bundleRelease`, then
`:{mobile,wear}:publishBundle` to push each AAB to Play's internal track as a
**draft**, and attaches the APKs/AABs to the GitHub release.

Without the secrets below the job still runs — it assembles **unsigned** release
APKs and skips signing + Play (each step gates on its own secret), so PRs and
forks never need credentials.

## Required secrets

| Secret | Purpose |
|---|---|
| `SIGNING_KEYSTORE` | base64 of the upload keystore (`base64 -w0 upload.jks`). Presence gates signing + bundles. |
| `COMPOSEAI_KEYSTORE_PASSWORD` | keystore password |
| `COMPOSEAI_KEY_ALIAS` | key alias inside the keystore |
| `COMPOSEAI_KEY_PASSWORD` | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play Console service-account JSON. Presence gates the Play upload. |

The Gradle build reads the keystore path from `COMPOSEAI_KEYSTORE_PATH` (the CI
job exports it after decoding `SIGNING_KEYSTORE`) and GPP reads the service
account from `ANDROID_PUBLISHER_CREDENTIALS`. Both `play { }` blocks set
`enabled = (ANDROID_PUBLISHER_CREDENTIALS != null)`, so no Play API calls happen
in local or PR builds.

## Play Console one-time setup

1. Create the two apps in the Play Console (`ee.schimke.composeai.clients.mobile`
   and `…clients.wear`).
2. Create a service account with the *Release to internal testing* permission,
   download its JSON key, and store it as `PLAY_SERVICE_ACCOUNT_JSON`.
3. Generate an upload keystore, store it + its passwords as the secrets above.

The `applicationId`s (`ee.schimke.composeai.clients.*`) are unchanged from when
these apps lived in `yschimke/compose-ai-tools`, so the Play listings carry over.

## Store listing + graphics

Listing text and images live under each module's `src/main/play/` and are
uploaded by GPP alongside the bundle:

```
mobile/src/main/play/
  listings/en-US/title.txt | short-description.txt | full-description.txt
  listings/en-US/graphics/{icon,feature-graphic,phone-screenshots}/…
  release-notes/en-US/internal.txt
```

The **phone / wear screenshots are the apps' own `@Preview` chrome**, rendered by
the [compose-preview][gpp-preview] pipeline (consumed from Maven Central) — so
they regenerate for free and stay in sync with the UI (see each
`graphics/README.md`):

```sh
./gradlew :mobile:composePreviewRenderAll
cp mobile/build/compose-previews/renders/*.png \
   mobile/src/main/play/listings/en-US/graphics/phone-screenshots/
```

The `icon/` (512×512) and `feature-graphic/` (1024×500) files are committed
placeholders — replace them with final brand art before any public (non-internal)
release.

## Versioning

Each module's `versionName` is a single constant in its `build.gradle.kts`, carrying
the `// x-release-please-version` marker so release-please bumps it; `versionCode` is
packed from it (`MAJOR*10000 + MINOR*100 + PATCH`). The version line continues from
`0.2.1` (where it stood when the apps were extracted from `compose-ai-tools`). For a
manual one-off, edit the constant and push a `v<version>` tag.

[gpp]: https://github.com/Triple-T/gradle-play-publisher
[gpp-preview]: https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin
[harc]: https://github.com/yschimke/homeassistant-remotecompose
