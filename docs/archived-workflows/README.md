# Historical publication workflows

These YAML files are source references for the completed one-off WebView and Gecko preview publications. They pin historical commits/artifacts and are intentionally outside `.github/workflows`, so merging or changing them cannot republish an old APK or use expired transfer links. Existing GitHub Releases and APK downloads remain unchanged.

Active validation lives in `.github/workflows/gecko-demo.yml` (Gecko main line) and `.github/workflows/android.yml` (original WebView regression). Both protect signing secrets from pull-request jobs. The fixed signing certificate and the existing repository Secret are retained.

New preview releases use the manually dispatched `.github/workflows/publish-gecko-preview.yml`. It requires a successful signed Gecko build of the exact current `main` commit, verifies the artifact digest and APK metadata, and checks uploaded bytes before publishing. Pushes and pull requests cannot publish a release.

The completed source branches are archived as same-name tags:

- `build/gecko-demo-20261021` → `d8f65a90f4ae7da17571c48dc7e215af64005b0d`
- `build/safari-debug-20261021` → `7331f18ccd7f3d6e33c645bbfee9673de777ebaf`

This keeps historical `/blob/build/...` source links in old releases and installed APKs usable after the branches are deleted. New code links to `main`.
