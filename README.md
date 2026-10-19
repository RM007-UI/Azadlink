# AzadLink — Android multi-engine VPN

AzadLink is an experimental Android VPN client integrating Aether/MASQUE, Psiphon, Xray share-link protocols, and DNS tunnel engines.

## Current 0.5.0-alpha work

- Four bottom navigation sections: server protocols, DNS tunnels, Aether/MASQUE, and Psiphon.
- MASQUE advanced options: HTTP/2 vs engine default, obfuscation profile, IP family, and TLS SNI.
- App-based split tunneling: route all apps, exclude selected apps, or route only selected apps.
- Launcher icon and release-signing configuration.
- Proxy-only mode is implemented for Aether/Psiphon and Xray-based server protocols; apps must be configured to use the local SOCKS endpoint. DNS tunnel engines still need separate VPN/proxy integration.
- Psiphon is built from the pinned Aether-compatible fork in CI. Device-level startup and live traffic still need verification.

A successful CI build does not prove that every protocol works on every network. Check the Actions workflow and test on-device before treating a build as stable.

## Stable APK signing

**Do not commit the release keystore or its passwords to this public repository.** The release workflow expects these GitHub Actions repository secrets:

- `AZADLINK_KEYSTORE_BASE64`: base64 encoding of the persistent `.jks`/ `.keystore` file
- `AZADLINK_STORE_PASSWORD`
- `AZADLINK_KEY_ALIAS`
- `AZADLINK_KEY_PASSWORD`

Keep a secure offline backup of the keystore. If it is lost, future APKs cannot use the same Android signing identity. Once these secrets are configured, push a version tag such as `v0.5.0`; CI builds the signed release APK and attaches it to the GitHub Release.

The regular CI build publishes an arm64 debug APK as an Actions artifact for testing. Debug builds are not the stable signed release.

## Build

The Android CI workflow checks out the pinned submodule, builds the native engines, runs unit tests, and assembles the debug APK. Native VPN routing, proxy-only behavior, and protocol connectivity require separate device testing.
