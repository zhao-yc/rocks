# Rocks Gecko development preview

The accepted Android browser direction is **GeckoView 158.0.20261021001945**, native Compose UI and Firefox WebExtensions. This standalone project keeps application ID `org.rocks.browser.geckodemo` and the existing fixed development certificate. Version 0.2.0-gecko (code 2) can update the 0.1 demo without uninstalling it. Android 10+, ARM64 phones. This is not an official Mozilla product.

## 0.2 scope

- Bottom address-bar drag with animated adjacent pages, short-drag snap-back, search/edit/cancel, back/forward/reload/share and a full-screen tab card grid. Up to 24 normal tabs; adjacent pages load in advance and other tabs load on selection.
- Restore tab URLs/titles/selection, bookmarks (200), URL history (500) and chosen search engine. Forms, scroll positions and navigation stacks survive tab switching/configuration changes but are not restored after process death.
- The portable schema in `shared/browser-data.schema.json` is unchanged. This project compiles the existing SessionCodec, SessionRepository and AtomicSessionFile sources and the shared core. Native extension pages are excluded from portable tab persistence.
- A process-wide single IO consumer orders writes and restores, including across Activity replacement. UI changes are debounced; leaving the Activity flushes its latest immutable snapshot. Reads/writes are bounded to 4 MiB, strict UTF-8. Corrupt/oversized/future files remain protected from overwrite. Failed writes may be retried by later changes.
- Bookmarks/history/search settings are in the top menu; the bookmark button toggles the current page. Clearing URL history does not clear cookies, website data, open tabs or downloads.
- Link long-press: foreground/background tab, copy and share.
- Downloads save the original Gecko response stream, retaining the authenticated response. Confirm and select a destination with Android's document picker; progress and cancellation are shown in Downloads. Keep the app running: process death stops a transfer and cancellation/failure may leave an incomplete destination file. The task list is session-only. HTTP(S) response downloads are supported; arbitrary blob downloads and external-app handoff are not implemented.
- Website file uploads use the system document picker, including multiple files. Selection is bound to the originating tab and URL; canceled/stale prompts receive no file. Folder/camera capture uploads are not provided.
- Website alert/confirm prompts identify the source host and are denied/dismissed on backgrounding, tab changes or page navigation. Hardware permissions remain denied.
- Extensions: explicit installation permissions, real toolbar panels, per-tab action overrides, options tabs, enable/disable/uninstall and manual update checks. New update permissions require foreground confirmation; background changes never silently approve permissions. Bundled Rocks Paper needs no network download; Dark Reader and uBlock Origin install from Mozilla. No arbitrary XPI installer or complete add-on store is claimed. Extensions/settings persist in Gecko's private profile.

No password vault, account sync, private browsing, arbitrary app intents, native JS bridges, certificate exceptions or telemetry SDK is added. Browser data and Gecko profile are excluded from Android backup/device transfer. This is a development preview: automated checks do not replace real-device acceptance.

## Build and test

Use JDK 17, Gradle 9.3.1, AGP 9.1.1, Kotlin/Compose compiler 2.4.0 and **already installed** SDK API 37/build-tools 36.0.0. SDK auto-download is disabled. This toolchain is independent of the original WebView application.

```sh
node --test scripts/test-gecko-extension.mjs
gradle -p android/gecko-demo :core:test testDebugUnitTest lintDebug assembleDebug
```

The build includes the existing codec/repository regression tests plus browser policy and process-wide storage ordering tests. Use the existing private development signing configuration; without it only unsigned intermediates are produced. Never publish an unsigned package or generate a different key. Device/emulator testing must be explicitly arranged; this environment has no mobile SDK/device.

## Try on a phone

1. Install over the 0.1 Gecko demo. Verify existing extension settings remain.
2. Open several tabs, switch with the address bar and grid, bookmark a page, then restart. Check restored URLs and selected tab. Change search engine and verify persistence.
3. Long-press links and try foreground/background tabs, copying and sharing.
4. Download an HTTP(S) attachment and choose a destination. Verify the file using the system Files app. Cancel another transfer and verify its incomplete state is reported. Upload selected files to a trusted website and test canceling the picker/tab changes.
5. Install/open/disable/re-enable Paper, Dark Reader and uBlock Origin. Check for updates and reject any unwanted new permissions.

## Components and source

GeckoView is used unmodified under **Mozilla Public License 2.0**, with its own third-party notices. Source and license information:

- [Mozilla source](https://github.com/mozilla-firefox/firefox)
- [Corresponding Mozilla source release](https://archive.mozilla.org/pub/firefox/releases/158.0/source/)
- [Pinned GeckoView artifacts and wrapper source](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/158.0.20261021001945/)
- [MPL 2.0](https://www.mozilla.org/MPL/2.0/)
- [GeckoView documentation](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/index.html)
- [Dark Reader / MIT](https://github.com/darkreader/darkreader)
- [uBlock Origin / GPLv3](https://github.com/gorhill/uBlock)

Dark Reader/uBlock packages are downloaded separately from Mozilla and are not bundled. AndroidX/Compose, Kotlin and build tools retain their own licenses. The license for Rocks itself remains undecided; public repository visibility is not a license grant.
