# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

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
- Added warm browser preloading for faster first-use browser startup.
  - Added `browser-preload-enabled`, `browser-preload-transparent-pool-size`, and `browser-preload-opaque-pool-size` settings.
  - Browser preload pools now refresh immediately when those settings are changed at runtime.

### Fixed
- Fixed infinite recursion in `MCEFBrowser.startDragging(...)`.
- Fixed popup paint robustness and correctness in `MCEFBrowser`.
  - Added guards for null/invalid popup state.
  - Corrected popup region copy offsets and row-wise copy behavior.
  - Corrected popup restore coordinates.
- Fixed render-thread safety for browser texture uploads.
  - `MCEFBrowser` now dispatches off-thread paint work to the Minecraft render thread.
  - `MCEFRenderer` now asserts render-thread usage in paint upload methods.
  - `MCEFBrowser` now initializes renderer textures immediately when already on the render thread.
- Fixed warm browser preload lifecycle/threading edge cases.
  - Preload tasks now execute through Minecraft's thread submit path (instead of a separate preload executor).
  - Preload tasks now re-check live settings before pooling a browser.
  - Shutdown now blocks further preload creation before CEF teardown.
- Fixed handler list concurrency risks in `MCEFClient`.
  - Switched handler collections to `CopyOnWriteArrayList`.
- Fixed platform architecture detection gaps in `MCEFPlatform`.
  - Added support for `x86_64` and `arm64` aliases.
- Fixed checksum parser compatibility in `MCEFDownloader`.
  - Parser now accepts common `.sha256` text layouts by extracting a valid SHA-256 token from content.

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
- Improved downloader startup flow in `MixinClientPackSource`.
  - Reset download listener state at startup.
  - Improved failure reporting paths.
  - `skip-download=true` now requires platform-specific runtime directory existence.

### Build
- Hardened dependency repository scoping in root Gradle scripts.
  - Added `exclusiveContent` repository filters in `build.gradle`.
  - Added `exclusiveContent` plugin repository filters in `settings.gradle`.
- Added optional dependency locking scaffolding in `build.gradle`.
  - Locking is behind `-Pmcef.enableDependencyLocking=true`.
- Narrowed JitPack filter scope to avoid forcing unrelated `com.github.*` artifacts to JitPack.
  - Limited to explicitly needed groups.
- Removed incompatible `dependencyVerification {}` block from `settings.gradle` after environment compatibility failure.

### Reverted
- Reverted a temporary logging change made in `common/java-cef`.
  - Final state keeps `common/java-cef` unchanged.
