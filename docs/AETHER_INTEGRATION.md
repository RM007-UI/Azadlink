# Aether / MASQUE integration plan

## Current truth

AzadLink is still a preview. The current `AzadLinkVpnService` immediately reports that the engine is not integrated and stops. The dark UI and the upstream source pin do not create a tunnel. Do not label any build as a working VPN until a device test proves that traffic traverses the tunnel.

## Pinned upstream

- Project: https://github.com/bepass-org/oblivion
- Pinned revision: `bd8d2ac0659ac7524504354f8756447e6a54ef92`
- Android library source: `oblivion-upstream/android/aether-vpn`
- Native Aether source: nested upstream submodule `oblivion-upstream/aetherproject`
- TUN-to-SOCKS bridge: nested upstream submodule `oblivion-upstream/android/aether-vpn/src/main/jni/hev-socks5-tunnel`

## Implementation sequence

1. Fetch all pinned upstream submodules in CI and verify the Aether Kotlin sources, Rust core source, and hev-socks5-tunnel source are present.
2. Integrate the upstream Android VPN library without changing its service contract; resolve Gradle/AGP/Kotlin/NDK versions and build native binaries for supported ABIs.
3. Add an AzadLink adapter that constructs the upstream `TunnelConfig` / `TunnelBundle`, launches the real Aether VPN service after Android VPN consent, and translates upstream lifecycle/log events into app state.
4. Connect the automatic MASQUE scan flow and define a clear failure state when no endpoint is reachable. Do not claim "connected" merely because Android granted VPN permission or the process started.
5. Build and test on CI; then verify on a real Android device by checking the upstream connected/validation signal and successful network requests through the tunnel, plus disconnect/reconnect.

## Acceptance criteria

- No placeholder service is used for a claimed successful connection.
- Missing native libraries, no endpoint, startup failure, tunnel failure, revocation, and disconnect all produce truthful state.
- CI assembles the APK and records the artifact; device traffic validation is reported separately.
- Upstream license notices are preserved.
