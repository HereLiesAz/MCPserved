# MCPserved guide

MCPserved lets an authorized model observe and control an Android phone. The
Android app is itself an MCP server — a host can connect straight to the device
over HTTP — and a desktop bridge is available for the no-app `adb` quick-connect
and for hosts that only launch stdio servers. By default, everything is local:
there is no relay, no cloud, and no account in the path. Control travels a USB
cable or an adb-over-Wi-Fi session the user set up themselves. The desktop
server drives the device either straight over `adb` (a quick connect that needs
no app installed) or, once the on-device app is installed and paired, through a
richer accessibility-backed surface with per-package grants. The desktop server
holds no authority of its own — enforcement lives on the device, because the
server sits downstream of a language model's output and is the component least
suited to being the thing that says yes. A host with no local network path to
the phone at all can reach it too, on explicit opt-in — see
[remote-access](remote-access.md).

## Contents

| Page | What it covers |
| --- | --- |
| [overview](overview.md) | What MCPserved is, the local-first thesis, the two-part system, and the two backends. |
| [quickstart](quickstart.md) | The fastest path to a working setup: adb quick-connect, then the optional paired-app upgrade. |
| [connect](connect.md) | One-click setup: deep links and the `mcpserved install` command for every supported MCP host. |
| [desktop-server](desktop-server.md) | The `mcp/` package: install, build, run, environment variables, backend selection, pairing. |
| [android-app](android-app.md) | The app: accessibility service, disclosure gate, arming, grants, sessions, revocation, boot behavior. |
| [backends](backends.md) | Control layers — Accessibility, root, Shizuku, pure-adb — and the per-operation resolver. |
| [mcp-tools](mcp-tools.md) | The MCP tool surface exposed to the model, tool by tool, with inputs and availability. |
| [security](security.md) | The pairing, sealed-frame, loopback, and key-storage model, and the trust boundaries. |
| [protocol](protocol.md) | The loopback wire protocol: framing, handshake, envelopes, request/response types, enums. |
| [remote-access](remote-access.md) | Opt-in, off-by-default paths for a host with no local network path to the phone: a wider bind for a private mesh, listening directly on the phone's own IPv6 address, a UPnP port mapping, and a relay for the sealed-frame protocol. |
| [troubleshooting](troubleshooting.md) | Common failures and their fixes. |
