# Contributing

Rocks is in early development. The owner still needs to choose a license; public visibility alone does not grant redistribution rights.

Keep changes focused and include relevant regression tests. Run the README's Android unit tests, lint and debug build. Record the actual device, SDK, engine version and observed result for device behavior. Never claim HarmonyOS compilation without a real SDK result.

Keep the `shared` contract compatible or add an explicit migration. Preserve unreadable and unknown-version data. Do not add SSL bypasses, automatic grants, telemetry, native JavaScript bridges or a private-browsing switch without isolation tests. Do not commit secrets, signing files, user browsing data, personal paths, logs or build outputs.
