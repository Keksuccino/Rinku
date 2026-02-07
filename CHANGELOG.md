# Changelog

## [2.2.0]

### Added
- Added native file download support in the MCEF client wrapper.
  - `MCEFClient` now implements `CefDownloadHandler`.
  - Default download behavior now opens the OS-level Save dialog (`callback.Continue(..., true)`).
  - Added `addDownloadHandler(CefDownloadHandler)` for custom download handling.
- Added hardened download policy APIs for runtime binary downloads.
  - Added `MCEFDownloader.DownloadPolicy`.
  - Added `MCEFDownloader.MirrorPolicy`.
  - Added `MCEFSettings.createDownloadPolicy()` to wire settings into downloader behavior.
- Added persisted hardening and browser-flag settings in `mcef.properties`.
  - `download-mirror-policy`
  - `enforce-download-checksums`
  - `download-connect-timeout-ms`
  - `download-read-timeout-ms`
  - `download-max-archive-bytes`
  - `download-max-checksum-bytes`
  - `download-max-extracted-bytes`
  - `cef-disable-web-security`
  - `cef-enable-widevine-cdm`
  - `cef-console-log-forwarding-min-severity`
    - Default is `LOGSEVERITY_DISABLE` (no website console messages forwarded to MC log).
- Added warm browser preloading for faster first-use browser startup.
  - Added `browser-preload-enabled`, `browser-preload-transparent-pool-size`, and `browser-preload-opaque-pool-size` settings.
  - Browser preload pools refresh immediately when those settings are changed at runtime.
  - Preloading is lifecycle-safe: tasks are submitted on Minecraft's thread path, re-check live settings before pooling, and stop before CEF teardown during shutdown.

### Fixed
- Fixed infinite recursion in `MCEFBrowser.startDragging(...)`.
- Fixed AltGr character input handling for browser text entry.
  - Normalized AltGr (`Right Alt` reported as `Ctrl+Alt` on many layouts) in MCEF key forwarding so characters like `@` input correctly.
  - Adjusted example screen keyboard event consumption to avoid Minecraft/global handlers interfering with browser text input.
- Fixed popup paint robustness and correctness in `MCEFBrowser`.
  - Added guards for null/invalid popup state.
  - Corrected popup region copy offsets and row-wise copy behavior.
  - Corrected popup restore coordinates.
- Fixed render-thread safety for browser texture uploads.
  - `MCEFBrowser` now dispatches off-thread paint work to the Minecraft render thread.
  - `MCEFRenderer` now asserts render-thread usage in paint upload methods.
  - `MCEFBrowser` now initializes renderer textures immediately when already on the render thread.
- Fixed handler list concurrency risks in `MCEFClient`.
  - Switched handler collections to `CopyOnWriteArrayList`.
- Fixed platform architecture detection gaps in `MCEFPlatform`.
  - Added support for `x86_64` and `arm64` aliases.
- Fixed checksum parser compatibility in `MCEFDownloader`.
  - Parser now accepts common `.sha256` text layouts by extracting a valid SHA-256 token from content.
- Fixed intermittent GLFW cursor GL errors when opening browsers.
  - Mapped unsupported/unavailable CEF cursor shapes to OS default cursor instead of creating invalid GLFW standard cursors.
  - Added safe fallback for invalid/out-of-range cursor IDs.

### Security
- Hardened runtime archive extraction against path traversal and link-based abuse.
  - Normalized and validated all output paths remain within extraction root.
  - Rejected symlink and hardlink tar entries.
  - Preserved compatibility by skipping non-file, non-directory metadata entries.
- Added runtime archive integrity verification.
  - Downloaded archive SHA-256 is validated against checksum before extraction.
- Hardened downloader network and resource limits.
  - Added connect/read timeouts.
  - Added archive/checksum/extracted-size limits.
  - Replaced `available()`-driven buffer resizing with fixed-size streaming buffers.
  - Added `.part` temporary file workflow with safe replacement move.
- Replaced broad process kill behavior on Windows.
  - Removed blanket `taskkill /IM jcef_helper.exe`.
  - Added scoped `ProcessHandle` cleanup targeting relevant helper processes under MCEF library path.

### Changed
- CEF risky switches are now explicitly settings-driven in `CefUtil`.
  - `--disable-web-security` and `--enable-widevine-cdm` are toggled by `MCEFSettings`.
  - Both toggles are currently defaulted to opt-out behavior:
    - `cef-disable-web-security=true` by default.
    - `cef-enable-widevine-cdm=true` by default.
- Website console messages are now filtered by `cef-console-log-forwarding-min-severity` in `MCEFSettings`.
  - `MCEFClient` always consumes CEF console callbacks to prevent unfiltered direct console spam.
  - Forwarding to Minecraft log occurs only when message severity meets the configured threshold.
- Native CEF/Chromium log verbosity is now settings-driven via `cef-native-log-severity`.
  - Default is `LOGSEVERITY_DISABLE` to suppress Chromium warning/error spam in the Minecraft log.
- Runtime binary downloads now use GitHub release assets from `Keksuccino/mcef_resources`.
  - Official source moved to `https://github.com/Keksuccino/mcef_resources/releases/download`.
  - Java-CEF assets are resolved from release tags in the format `java-cef-<commit>`.
- Improved the in-dev example browser screen.
  - Added URL bar with live address updates and Enter-to-navigate behavior.
  - Added Back/Forward/Reload navigation buttons.
  - Added loading indicator over the URL bar while pages are loading.
- Improved downloader startup flow in `MixinClientPackSource`.
  - Reset download listener state at startup.
  - Improved failure reporting paths.
  - `skip-download=true` now requires platform-specific runtime directory existence.
