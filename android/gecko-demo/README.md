# Rocks Gecko development preview

The accepted Android browser direction is **GeckoView 158.0.20261021001945**, native Compose UI and Firefox WebExtensions. This standalone project keeps application ID `org.rocks.browser.geckodemo` and the existing fixed development certificate. Version 0.4.0-gecko (code 4) can update the 0.1/0.2/0.3 demo without uninstalling it. Android 10+, ARM64 phones. This is not an official Mozilla product.

## 0.4 everyday browser entry points

- Settings offers Android's default-browser chooser and reads the actual role state again on return. The app never claims the role before Android grants it.
- A single browser Activity handles HTTP/HTTPS VIEW/BROWSABLE intents on cold and warm starts. External links open in a new tab (or reuse the sole empty start page), after portable data restoration. A bounded queue protects restoration; reaching the tab limit leaves existing pages intact. Extras, search text and non-web schemes are not interpreted as navigation commands.
- More → Find in page uses Gecko's native finder: highlighted matches, match counts, previous/next with wrapping, empty query clearing and Android Back to close. Requests/results are bound to the active tab and document; switching pages clears old highlights. The search strip remains above the keyboard.
- More → Desktop site changes both Gecko user-agent mode and viewport mode, then reloads the current tab. It is a per-tab setting for this process; restart returns to mobile mode. No portable schema change is made.
- More → Site permissions shows the current site and links to Android app permissions. Camera and microphone requests require a website confirmation even when Android app permission was already granted. A short-lived, one-use handshake joins Gecko's Android/media permission stages to avoid duplicate confirmations for the same request.
- Only the selected foreground HTTPS page's own origin may request camera, microphone or location. Screen/audio capture, third-party-frame device requests, background tabs, extension pages and unrelated permissions remain unsupported. Media choices are restricted to the camera/microphone sources supplied by Gecko.
- Android runtime permissions are requested only after website confirmation. Approximate location is accepted. The native result is checked against the same tab, full URL and document revision; cancel, navigation and switching tabs reject outstanding requests. Ordinary backgrounding cancels pending website confirmations. A launched system permission dialog may pause or stop the host Activity on different Android builds; that result alone is held until resume and revalidated, and never grants a background page. Returning a stale result cannot authorize a newer document.
- Gecko saves location allow/deny decisions per origin in its private profile. Site permissions can reset this choice and reload the current page to stop that page's streams/location requests; a confirmation warns about unsaved forms. Other tabs and Android app permissions are not reset. Camera/microphone use appears in the expanded address bar; website controls or closing/reloading its tab stop active capture. No background capture service is added.

Phone acceptance:

1. Choose Rocks in Settings → Default browser. Open web links from a message app with Rocks closed and already running. Confirm restored tabs survive and a link opens once; cancel the system chooser and verify it does not report success.
2. Find a repeated word on a long page, step forward/backward and wrap, change/clear the query, close with Android Back, then switch tabs and navigate while a search is pending.
3. Toggle Desktop site on a responsive test page; verify a desktop user agent and wide layout, then switch back. Verify other tabs stay unchanged.
4. On a trusted HTTPS media/geolocation test site, approve/deny website prompts and Android prompts, including approximate location. Repeat while Android already grants the app permission. Check capture indicators, stop with website controls or reload, reset site location, then retry.
5. Navigate/reload the same URL, switch tabs, background or rotate the Activity while an Android permission dialog is outstanding. Confirm an old result cannot grant access to a replacement page. System permissions and device APIs still need real-device acceptance.

## 0.3 immersive controls

The app title row is removed. Web content fills a stable viewport below the Android status/cutout area and extends behind the floating bottom toolbar. Expanding or shrinking the toolbar does not resize the GeckoView or reserve a blank strip. Gecko receives a measured bottom-occlusion hint to keep bottom-fixed website controls clear of the overlay. Android system indicators remain available; website fullscreen still hides browser/system controls.

The toolbar uses Rocks sage/forest colors, a thin green outline and a single rounded surface. Scroll down through actual page content to collapse it into a small domain pill; scroll upward or tap the pill to expand. The first tap expands the pill; tap the expanded address to edit. Tap-sized targets remain at least 48 dp even in the compact state. Back, reload, tabs and More stay in the expanded bar; extensions, forward, bookmarking, share, start page and libraries move into More.

Only Gecko compositor scroll events marked as user interaction drive collapse. Direction hysteresis prevents jitter; script scrolling and zoom do not hide controls. Navigation/tab changes restore the bar. Address editing, the keyboard, sheets/dialogs, the start page, touch exploration and large system text keep it expanded. Horizontal address-bar drags work in both states and do not consume webpage vertical gestures. No native JavaScript bridge is used.

Phone acceptance for this version:

1. Open a long page, scroll down, then up slowly. Confirm smooth pill/bar transitions while page content does not jump. Check both portrait/landscape and Android gesture/three-button navigation.
2. Tap the pill once to expand, again to edit. Submit/cancel with the keyboard and Android Back. Open a webpage input near the bottom and check it remains reachable.
3. Swipe the address/pill left and right with multiple tabs. Try short drags, flicks, first/last tabs and the tab grid. Open More and confirm extension panels and all previous actions remain available.
4. Check script scrolling, pinch zoom, fixed website footers, video fullscreen and return from fullscreen. Enable TalkBack/large text: controls must remain expanded.

Automated scroll-state tests cover collapse/reveal, jitter, programmatic movement, zoom and interaction locks. UI animation, keyboard and Android inset behavior still require device acceptance.

## Existing browser functions

- Bottom address-bar drag with animated adjacent pages, short-drag snap-back, search/edit/cancel, back/forward/reload/share and a full-screen tab card grid. Up to 24 normal tabs; adjacent pages load in advance and other tabs load on selection.
- Restore tab URLs/titles/selection, bookmarks (200), URL history (500) and chosen search engine. Forms, scroll positions and navigation stacks survive tab switching/configuration changes but are not restored after process death.
- The portable schema in `shared/browser-data.schema.json` is unchanged. This project compiles the existing SessionCodec, SessionRepository and AtomicSessionFile sources and the shared core. Native extension pages are excluded from portable tab persistence.
- A process-wide single IO consumer orders writes and restores, including across Activity replacement. UI changes are debounced; leaving the Activity flushes its latest immutable snapshot. Reads/writes are bounded to 4 MiB, strict UTF-8. Corrupt/oversized/future files remain protected from overwrite. Failed writes may be retried by later changes.
- Bookmarks/history/search settings are in the top menu; the bookmark button toggles the current page. Clearing URL history does not clear cookies, website data, open tabs or downloads.
- Link long-press: foreground/background tab, copy and share.
- Downloads save the original Gecko response stream, retaining the authenticated response. Confirm and select a destination with Android's document picker; progress and cancellation are shown in Downloads. Keep the app running: process death stops a transfer and cancellation/failure may leave an incomplete destination file. The task list is session-only. HTTP(S) response downloads are supported; arbitrary blob downloads and external-app handoff are not implemented.
- Website file uploads use the system document picker, including multiple files. Selection is bound to the originating tab and URL; canceled/stale prompts receive no file. Folder/camera capture uploads are not provided.
- Website alert/confirm prompts identify the source host and are denied/dismissed on backgrounding, tab changes or page navigation. Device permissions follow the 0.4 flow above.
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
