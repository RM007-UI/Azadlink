# AzadLink — Android multi-engine VPN

AzadLink is an experimental Android VPN client integrating Aether/MASQUE, Psiphon, Xray share-link protocols, and DNS tunnel engines.

## Current version: 0.11.4



- Four bottom navigation sections: server protocols, DNS tunnels, Aether/MASQUE, and Psiphon.
- MASQUE advanced options: HTTP/2 vs engine default, obfuscation profile, IP family, and TLS SNI.
- Better automatic MASQUE gateway selection: Balanced scan compares verified gateways on first connect; when it finds no gateway, automatic fallback expands to a full-subnet Thorough scan over HTTP/2 before trying HTTP/3. Aether's quick-reconnect path reuses its persisted last-working endpoint on later connects. Concurrent reconnects stop the previous session so stale attempts cannot keep multiple Aether cores alive.
- This release packages only `arm64-v8a`, because the Aether executable and the bundled tunnel engines are built for 64-bit ARM. Older 32-bit-only Android systems are not supported by this build.
- App-based split tunneling: route all apps, exclude selected apps, or route only selected apps.
- Launcher icon and release-signing configuration.
- Proxy-only mode is implemented for Aether/Psiphon and Xray-based server protocols; apps must be configured to use the local SOCKS endpoint. DNS tunnel engines use the built-in SOCKS5-to-TUN bridge and require valid server-side configuration.
- Psiphon is built from the pinned Aether-compatible fork in CI. Device-level startup and live traffic still need verification.

Regression tests cover share-link parsing, WireGuard block parsing, Xray configuration generation, and reconnect backoff. A successful CI build still does not prove that every protocol works on every network. Check the Actions workflow and test on-device before treating a build as stable.

## Stable APK signing

**Do not commit the release keystore or its passwords to this public repository.** The release workflow expects these GitHub Actions repository secrets:

- `AZADLINK_KEYSTORE_BASE64`: base64 encoding of the persistent `.jks`/ `.keystore` file
- `AZADLINK_STORE_PASSWORD`
- `AZADLINK_KEY_ALIAS`
- `AZADLINK_KEY_PASSWORD`

Keep a secure offline backup of the keystore. If it is lost, future APKs cannot use the same Android signing identity. Once these secrets are configured, every non-pull-request branch build also produces a **signed release APK** as an Actions artifact. To publish a versioned public release, push a version tag such as `v0.9.1`; CI then attaches that same signed APK to the GitHub Release.

The regular CI build also publishes an arm64 debug APK as a separate Actions artifact for testing. Debug builds are not the stable signed release.

## Build

The Android CI workflow checks out the pinned submodule, builds the native engines, runs unit tests, and assembles the debug APK. Native VPN routing, proxy-only behavior, and protocol connectivity require separate device testing.
