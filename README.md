# Rocks

A small, free mobile browser without app ads, promotion slots or a news feed. Rocks prioritizes users in China, with Chinese default UI resources and a starting set of English resources. Android development now follows Kotlin, Jetpack Compose and GeckoView in the standalone `android/gecko-demo` project. The earlier system WebView application remains available for regression checks. Native HarmonyOS uses ArkTS, ArkUI and ArkWeb.

**Early prototype, not a store-ready browser.** The source repository is public, but an open-source license has **not been selected**. Do not assume a license grant from repository visibility. There is no root `LICENSE` until the owner decides.

## Gecko development preview

The Android main line uses GeckoView with Firefox WebExtensions and the Rocks interface. See [Gecko build and feature notes](android/gecko-demo/README.md) and the [verification record](docs/Gecko体验版验证.md).

**[Download Gecko 0.4.2 APK](https://github.com/zhao-yc/rocks/releases/download/v0.4.2-gecko/rocks-gecko-0.4.2.apk)** — Android 10+, ARM64, fixed development certificate, compatible with previous Gecko previews. The original WebView application uses a separate package and data store.

The preview includes an immersive status bar, a floating toolbar that shrinks while reading, sideways tab switching, URL/session restoration, bookmarks/history, search settings, uploads/downloads, default-browser integration, find in page, per-tab desktop mode, website camera/microphone/location requests and curated Firefox extensions. System UI, hardware permissions, cutouts and gesture feel still require phone acceptance; this is a development preview. Complete extension-store integration, account sync, a password vault and private browsing are not implemented.

## Original WebView scope

Android 10 / API 29 and newer:

- Minimal home, bottom address/search bar, back/forward, reload/stop and tab overview.
- Safari-inspired interactions on Android: tap the compact address bar to edit the full URL, cancel without navigating, swipe it to select adjacent tabs, and manage tabs in a full-screen card grid. Long-press the tab button for new/close/close-other actions; share and bookmarks are directly available on the bottom toolbar.
- Rocks keeps its own moss-green and warm-paper visual style. Long-press a web link to open it in a foreground/background tab, copy it or share it. Background tabs load when selected.
- Up to 24 normal tabs. In-memory WebView back/forward stacks remain separate per tab.
- Local restore of tab URLs, selection, bookmarks, bounded history and search preference. Form contents, scroll positions and back/forward stacks are not restored after process death.
- Bing China by default; Baidu, DuckDuckGo and Google are optional. Availability depends on the network.
- Confirmed HTTP(S) downloads via system DownloadManager, document-picker uploads, sharing and a custom video fullscreen view.
- SSL errors always cancel; mixed content, local file/content loading, automatic pop-ups and third-party cookies are disabled.
- Camera, microphone and location requests are explicitly denied in the prototype. `tel`, `mailto`, `sms` and `geo` links require a foreground user gesture and confirmation. Other schemes and `intent:` links are blocked for now.
- Automatic app-launch attempts, background/frame requests and automatic pop-ups are blocked silently. A direct tap on an unsupported link shows a short snackbar; it does not interrupt reading with a confirmation dialog.

HarmonyOS has a real Stage project, UIAbility, ArkUI/ArkWeb navigation page, models and a Preferences session adapter. **Not compiled or run:** this machine lacks DevEco/HarmonyOS SDK. Multi-tab UI, bookmarks/history, downloads, uploads, sharing and fullscreen parity remain planned. See [HarmonyOS notes](harmony/README.md).

The original WebView prototype has no extension support. The Gecko preview supports curated Firefox extensions including uBlock Origin, rather than a complete add-on store. Private browsing has no entry point until storage and session isolation are proven.

## Build Android — Gecko main line

Use JDK 17, Gradle 9.3.1 and already installed Android SDK API 37/build-tools 36.0.0. SDK auto-download is disabled. Keep the existing repository secret `ROCKS_ANDROID_SIGNING` for the fixed development certificate; PRs build without the private key. See [signing setup](docs/Android签名.md).

```sh
node --test scripts/test-gecko-extension.mjs
gradle -p android/gecko-demo :core:test testDebugUnitTest lintDebug assembleDebug
```

[Gecko CI](https://github.com/zhao-yc/rocks/actions/workflows/gecko-demo.yml) runs on `main`, pull requests targeting `main`, and manual dispatch. Main-branch builds verify the actual APK certificate when signing is configured. Artifacts contain APKs and reports; private keys are excluded. The earlier one-off release workflows are kept as [archived source references](docs/archived-workflows/README.md) and no longer execute automatically.

## Build the original WebView reference

Requires JDK 17 and Android SDK packages `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`. Review SDK licenses when installing. Set `JAVA_HOME` and `ANDROID_HOME`, or use ignored `local.properties` with `sdk.dir`. No machine paths are committed.

From 0.2.2-dev, distributable testing APKs use one private development key with a pinned certificate. Configure the repository secret or local signing environment as described in [signing setup](docs/Android签名.md). Builds without this configuration produce unsigned intermediates instead of silently generating a different key. Keep a private offline backup; the earlier temporary keys cannot be recovered.

```powershell
.\gradlew.bat :core:test :android:app:testDebugUnitTest :android:app:lintDebug :android:app:assembleDebug
# With an authorized device/emulator:
.\gradlew.bat :android:app:connectedDebugAndroidTest
# JVM tests without an Android SDK:
.\gradlew.bat --configure-on-demand :core:test
```

On macOS/Linux, use `bash gradlew` with the same tasks. Baseline: AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20, Compose BOM 2025.04.01, compile/target API 35. This is a reproducible prototype baseline, not a claim of current store-policy compliance. Wrapper and distribution checksums are pinned/verified.

[Android CI](https://github.com/zhao-yc/rocks/actions/workflows/android.yml) uses the runner's preinstalled SDK for unit tests, lint and debug APK generation. Emulator tab-flow checks require a manual run with the explicit SDK-license/download option enabled; they are not silently enabled. Debug APKs and reports are workflow artifacts, not Git files. Testing APKs use the fixed development certificate; they are not production store releases.

## Structure

| Directory | Purpose |
| --- | --- |
| `android/gecko-demo` | Main Android GeckoView browser, immersive Compose UI and Firefox extensions |
| `android/app` | Compose UI, WebView adapter, atomic persistence and device interfaces |
| `core` | JVM navigation, tab state and bounded records |
| `harmony` | Native HarmonyOS Stage project and staged implementation plan |
| `shared` | Versioned JSON contract and portable fixture |
| `docs` | Chinese plan, verification record and device checklist |

See [中文方案](docs/方案.md), [verification](docs/验证记录.md), [acceptance checks](docs/验收清单.md) and [contributing](CONTRIBUTING.md). `browser.rocks` is an unpurchased candidate domain; DNS is not configured.

## Privacy boundaries

Normal tabs within each app share that engine's cookies and website storage. The Gecko and original WebView applications have separate packages and profiles. Session files stay in app-private storage; Android backup is disabled. Clearing history clears URL history only, not cookies, cache, open tabs or downloads. Gecko downloads use the system document picker and the original response stream; keep the app running. The original WebView application uses DownloadManager, an OS service with its own download records. No telemetry SDK or native JavaScript bridge is added.

Android session reads and writes are limited to 4 MiB of UTF-8. Unreadable, malformed, oversized or unknown-version sessions remain protected from subsequent saves; browsing continues in memory with a restore warning. Failed writes allow later snapshots to retry through AtomicFile.

## References

- [AGP compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [WebView SSL errors](https://developer.android.com/reference/android/webkit/WebViewClient#onReceivedSslError(android.webkit.WebView,%20android.webkit.SslErrorHandler,%20android.net.http.SslError))
- [ArkWeb page loading](https://github.com/openharmony/docs/blob/master/en/application-dev/web/web-page-loading-with-web-components.md)

Dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
