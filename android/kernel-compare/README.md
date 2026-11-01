# Minimal engine comparison

Two diagnostic APKs share the same small native Java Activity, viewport layout, address field, Back/Reload/Local test buttons, and offline HTML/JavaScript. They do not include the Rocks browser UI, Compose, extensions, blur, screenshots, account data, or session restoration. They are separate experiments; the production browser engine is unchanged.

- **Chromium**: Android's installed `WebView` provider. Its actual package/version appears at runtime; this is not a bundled Chrome APK or a fixed Chromium version.
- **Firefox**: embedded GeckoView `158.0.20261021001945`, with its default rendering backend and a single session.
- Different application IDs allow side-by-side installation: `org.rocks.browser.kerneltest.chromium` and `org.rocks.browser.kerneltest.firefox`. Both use the existing fixed development signing certificate; neither replaces Rocks.
- Both target Android 10+ and use identical hardware acceleration and system-inset handling. Neither requests a particular refresh rate, overrides the user agent, or changes browser frame-rate preferences. Camera, microphone, location, external application launches and native JavaScript bridges are not supported.

The native label shows the display rate Android reports **for that app**. The page independently estimates `requestAnimationFrame` callbacks per second and the 95th percentile frame interval, updates text once per second, and runs the same moving dot and optional 600 CSS-pixel/second automatic scroll. The first timestamp is not counted as a frame interval. Backgrounding/long pauses reset sampling. The estimate is not a measurement of actual compositor presentations or touch latency.

Install both phone APKs, keep the same system refresh-rate/power settings, and compare the default offline page first. Wait several seconds for the meter, then compare manual dragging, a fast swipe, inertial scrolling, and Auto scroll. Reset the meter or reopen Local test for another sample. The address bar can load the same HTTP(S) site in both packages. Results from a 60Hz emulator do not establish behavior on a high-refresh-rate phone; OEM per-app limits may differ between package IDs.

Build with JDK 17, Gradle 9.3.1 and the already installed SDK 37/build-tools 36.0.0:

```sh
node --test scripts/test-kernel-comparison.mjs
gradle -p android/kernel-compare testChromiumDebugUnitTest testGeckoDebugUnitTest lintChromiumDebug lintGeckoDebug assembleChromiumDebug assembleGeckoDebug
```

Phone Gecko builds contain only ARM64. The CI archives them before building a separate x86_64 emulator APK with `-PkernelTestAbi=x86_64`; the Chromium APK has no bundled native library and runs on both. CI verifies the pinned certificate, package IDs, phone ABI and byte-identical test assets. PR builds compile unsigned intermediates without signing secrets. Do not install new SDKs or accept licenses just to run this comparison.
