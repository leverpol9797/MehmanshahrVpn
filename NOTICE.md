# Third-party notices

Aethon is an independent graphical frontend maintained by hamvex.

This application bundles the Aether executable from https://github.com/CluvexStudio/Aether.
Aether is licensed under GNU AGPL v3.0. The bundled executable is downloaded from the official v1.9.0 release and verified against the publisher-provided SHA-256 checksum during reproducible builds. Aether and its marks are subject to the upstream project's TRADEMARK.md policy; Firstham AetherGui is an independent frontend and is not endorsed by CluvexStudio.

System-wide VPN Mode bundles Xray v26.3.27 from https://github.com/XTLS/Xray-core as the TUN and SOCKS5 routing engine. Xray is licensed under MPL-2.0. The unmodified official Windows archive is pinned to SHA-256 `d004c39288ce9ada487c6f398c7c545f7d749e44bdfdd59dbc9f865afba4e1ad`; the extracted `xray.exe` is pinned separately to SHA-256 `15c2d007954ac53ba69b80ec91242786b3c0b71d52649165b4ca1d5cc96ef8f1`. The license is distributed at `third-party/xray-LICENSE.txt`. Update the version, digests, generated-configuration tests, and this notice together.

The sing-box license is retained for historical reference only. sing-box is not included in current Aethon releases.

The Android application bundles official Aether v1.9.0 Android cores for ARMv7, ARM64, and x86_64, verified against the GitHub release asset digests recorded in `scripts/aether-pins.json`. Android VPN routing uses HEV Socks5 Tunnel v2.16.0 from https://github.com/heiher/hev-socks5-tunnel under the MIT license. The pinned native-library hashes and license are maintained in `scripts/fetch-android-assets.ps1` and `third-party/hev-socks5-tunnel-LICENSE.txt`.

Psiphon Tunnel Core development work was reviewed from the official
https://github.com/Psiphon-Labs/psiphon-tunnel-core repository at commit
`38148cd835e07d688dbb6b30ae24ad2fd0e5d847`. The project is licensed under
GPL-3.0; its official binaries repository is
https://github.com/Psiphon-Labs/psiphon-tunnel-core-binaries. The Windows
x86_64 ConsoleClient is built from that source with Go 1.26.2 and pinned to
SHA-256 `fc52730ba75425c20125b621ed9889b221d26e82631603501e49f1ce85bd039b`.
The complete upstream license text and source-build provenance are retained at
`third-party/psiphon-LICENSE.txt` and the AETHON_PSIPHON_* reports for future
reactivation. Psiphon is suspended and no Psiphon executable is distributed in
the current production release, so those redistribution obligations do not
apply to current installers.
