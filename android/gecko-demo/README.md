# Rocks Firefox-engine demo

A separate native Android / Compose application using **GeckoView 158.0.20261021001945**. This is a feasibility demo, not a migration of the existing WebView browser and not an official Mozilla product.

- App ID: `org.rocks.browser.geckodemo`; Android 10+, ARM64 phones.
- Moss-green / warm-paper Rocks UI, editable bottom address bar, search, back/forward/reload/share, up to 8 normal tabs.
- Drag the bottom address bar to move the actual page surfaces. GeckoView uses its TextureView backend so page movement can follow the gesture. A short drag snaps back; a flick selects one adjacent tab. Website scrolling does not capture this gesture.
- Sessions and their per-tab page state survive switching tabs and configuration changes. Tab lists and page state are **not persisted across process death** in this demo.
- Real WebExtension installation, explicit permission confirmation, toolbar popup surfaces, per-tab action overrides and active-tab synchronization, enable/disable/uninstall, extension-created options tabs.
- Extensions and their local settings persist in Gecko's app-private profile. No private-browsing entry point is offered.

## Try it

1. Open Extensions and install **Rocks Paper**. This first-party extension is bundled, requires confirmation, uses extension-local storage and content scripts, and makes no network or native-messaging requests.
2. Open Example.org (a second tab is initially provided). Open Rocks Paper's panel from Extensions. Toggle warm paper background or increased spacing, close the panel and inspect the live page changes.
3. Install **Dark Reader** or **uBlock Origin** over the network from the Mozilla add-ons URLs in `BrowserModel.kt`. GeckoView verifies Mozilla's XPI signature; no signature-verification setting is disabled. Installation asks for requested permissions. The initial versions are pinned; this demo does not implement automatic add-on updates or arbitrary XPI installation.
4. Try each add-on's actual popup on a website. Disable/re-enable it, switch tabs and relaunch the app. Enabling/disabling/uninstalling refreshes HTTP(S) pages to remove stale injected styles.

Mozilla add-ons may be unreachable on some networks. Rocks Paper can be installed without reaching the add-on server. This is **Firefox WebExtensions**, not Chrome Web Store compatibility. Privileged pages and some websites restrict extension execution.

## Scope and verification

Bookmarks, persistent tab restore, downloads/uploads, password management, full extension-store browsing and website camera/microphone/location permissions are not implemented. Android native permission requests default to rejection; the demo does not launch arbitrary app intents, add native JavaScript bridges, or create TLS exceptions. Extension permissions require a foreground confirmation and are denied when leaving the app or dismissing the prompt.

This demo is a standalone Gradle project so the main browser keeps its existing build toolchain. Build with JDK 17, Gradle 9.3.1, AGP 9.1.1 and already installed Android SDK API 37/build-tools 36.0.0. SDK auto-download is disabled:

```sh
node --test scripts/test-gecko-extension.mjs
gradle -p android/gecko-demo testDebugUnitTest lintDebug assembleDebug
```

Use the project's existing private development signing configuration. Without it, builds intentionally produce unsigned intermediates. Never publish an unsigned APK or generate a replacement key. Device/emulator execution is a separate acceptance check; compilation is not evidence of a passing device flow.

## Third-party components

GeckoView is distributed under **Mozilla Public License 2.0**, with additional notices for its third-party dependencies. The engine is used unmodified. Source and licensing information:

- [Mozilla source repository](https://github.com/mozilla-firefox/firefox)
- [Mozilla source releases](https://archive.mozilla.org/pub/firefox/releases/158.0/source/)
- [Pinned GeckoView Maven artifacts and wrapper source](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/158.0.20261021001945/)
- [MPL 2.0](https://www.mozilla.org/MPL/2.0/)
- [GeckoView embedding documentation](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/index.html)
- [Dark Reader source / MIT license](https://github.com/darkreader/darkreader)
- [uBlock Origin source / GPLv3 license](https://github.com/gorhill/uBlock)

Dark Reader and uBlock Origin are installed separately from Mozilla; their packages are not included in this app or repository. AndroidX/Compose, Kotlin and the build tools retain their own licenses. Rocks' own project license remains undecided; this document does not grant a license to Rocks code or authorize Mozilla branding.
