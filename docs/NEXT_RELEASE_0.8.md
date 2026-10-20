# AzadLink 0.8.x — next release plan

This checklist distinguishes UI work from verified tunnel-engine work. A protocol must not be marked connected until the selected engine reports readiness and a real data-plane probe succeeds.

## Requested by Reza

- [x] Four bottom navigation tabs: Servers (VLESS/VMess/Trojan/Shadowsocks/WireGuard), DNS tunnels (DNSTT/MasterDNS/StormDNS), Aether/MASQUE, and Psiphon.
- [x] Lightweight tab/card animations.
- [x] MASQUE advanced settings: HTTP/2 or HTTP/3, scan mode, obfuscation, IP family, SNI, optional endpoint, fragmentation, and retry.
- [x] App logo is used in the header and launcher icon.
- [x] Per-app routing UI: all apps, all except selected apps, or only selected apps.
- [ ] Shared proxy-only mode for every engine. Each engine needs a local SOCKS/HTTP adapter; engines without that capability must be clearly disabled rather than pretending to support proxy mode.
- [ ] Stable release signing and APK attached to GitHub Releases. The signing keystore must be kept in GitHub Actions secrets, never committed to this repository.
- [ ] Full device-level verification for each engine and routing mode.

## Additional improvements proposed by Mina

- [x] Default MASQUE preset uses the observed successful HTTP/2 + firewall profile.
- [x] Default MASQUE preset enables a deeper retry after an unsuccessful scan.
- [x] CI checks that the bundled Psiphon source accepts the exact protocol reported invalid by the device and pins the TLS module revision.
- [ ] Add a connection detail view: selected engine, transport, gateway, connection duration, and last real traffic verification.
- [ ] Improve error messages so configuration errors, engine crashes, gateway discovery failures, and data-plane failures are distinct.
- [ ] Add export/import and encrypted local backup for profiles; never upload private connection links to CI logs.
- [ ] Add a diagnostics export that redacts credentials, UUIDs, passwords, private keys, and sensitive query parameters by default.
- [ ] Add a smoke-test matrix for VLESS, VMess, Trojan, Shadowsocks, MASQUE, Psiphon, DNSTT, MasterDNS, and StormDNS. Report unsupported or unverified integrations honestly.
- [ ] Validate app inclusion/exclusion settings before starting a tunnel and show Android's package visibility limitations clearly.
- [ ] Keep a reproducible release checklist: version bump, CI green, APK checksum, stable signature verification, release notes, and GitHub Release attachment.

## Release and signing

The workflow currently builds a debug APK artifact on pushes. A stable signed release is built on a version tag only when all four GitHub Actions secrets are configured:

- `AZADLINK_KEYSTORE_BASE64`
- `AZADLINK_STORE_PASSWORD`
- `AZADLINK_KEY_ALIAS`
- `AZADLINK_KEY_PASSWORD`

Do not commit the keystore or passwords into source control. If the original signing key is lost, a newly generated key will not be able to update an installation signed by the old key.
