# Minimal engine comparison

Two diagnostic APKs share the same small native Java Activity, viewport layout, address field, Back/Reload/Local test buttons, and offline HTML/JavaScript. They do not include the Rocks browser UI, Compose, extensions, blur, screenshots, account data, or session restoration. They are separate experiments; the production browser engine is unchanged.

- **Chromium**: Android's installed `WebView` provider. Its actual package/version appears at runtime; this is not a bundled Chrome APK or a fixed Chromium version.
- **Firefox**: embedded GeckoView `158.0.20261021001945`, with its default rendering backend and a single session.
- Different application IDs allow side-by-side installation: `org.rocks.browser.kerneltest.chromium` and `org.rocks.browser.kerneltest.firefox`. Both use the existing fixed development signing certificate; neither replaces Rocks.
- Both target Android 10+ and use identical hardware acceleration and system-inset handling. Version 1.1 adds a shared refresh-rate selector; neither overrides the user agent or changes browser-internal frame-rate preferences. Camera, microphone, location, external application launches and native JavaScript bridges are not supported.

The native label separately shows the rate requested through the window and the display rate Android reports **for that app**. The page independently estimates `requestAnimationFrame` callbacks per second and the 95th percentile frame interval, updates text once per second, and runs the same moving dot and optional 600 CSS-pixel/second automatic scroll. The first timestamp is not counted as a frame interval. Backgrounding/long pauses reset sampling. The estimate is not a measurement of actual compositor presentations or touch latency.

Install both version 1.1 phone APKs over the previous comparison apps, keep the same system refresh-rate/power settings, and choose the same refresh-rate option before comparing the default offline page. Wait several seconds for the meter, then compare manual dragging, a fast swipe, inertial scrolling, and Auto scroll. Reset the meter or reopen Local test for another sample. The address bar can load the same HTTP(S) site in both packages. Results from a 60Hz emulator do not establish behavior on a high-refresh-rate phone; OEM per-app limits may differ between package IDs.


## Refresh-rate selector (1.1)

The shared native selector offers Follow system, the valid rates Android reports for the current physical resolution, and Highest (with its resolved value). Android 12+ seamless alternative rates are included. Fractional rates such as 59.94Hz remain selectable; other resolutions and invalid/duplicate values are excluded. The app does not invent a 60/120Hz option on devices that do not report it.

Both engines use the same window-level preferredRefreshRate request with preferredDisplayModeId cleared to zero. No display mode/resolution is pinned, and no system-wide settings are written. Follow system clears the request. Each package remembers its own choice, withdraws the window request in the background and restores it on resume; Highest is recalculated when display capabilities change. A previously selected numeric rate that is no longer available falls back to Follow system. The OS may ignore or adjust requests, so requested Hz, system-reported Hz and webpage rAF FPS remain separate observations.

The selector does not reload the page or add per-frame native work. After switching, allow a few seconds for the system to settle, then use Reset meter before collecting another sample. A 60Hz emulator can validate choices, window requests and lifecycle behavior; phone high-refresh acceptance remains separate.

Build with JDK 17, Gradle 9.3.1 and the already installed SDK 37/build-tools 36.0.0:

```sh
node --test scripts/test-kernel-comparison.mjs
gradle -p android/kernel-compare testChromiumDebugUnitTest testGeckoDebugUnitTest lintChromiumDebug lintGeckoDebug assembleChromiumDebug assembleGeckoDebug
```

Phone Gecko builds contain only ARM64. The CI archives them before building a separate x86_64 emulator APK with `-PkernelTestAbi=x86_64`; the Chromium APK has no bundled native library and runs on both. CI verifies the pinned certificate, package IDs, phone ABI and byte-identical test assets. PR builds compile unsigned intermediates without signing secrets. Do not install new SDKs or accept licenses just to run this comparison.
