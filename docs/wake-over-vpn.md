# Wake over a VPN (WireGuard): investigation and recommendation

Date: 2026-09-30. Status: **slices 1–3 implemented; slice 4 (physical acceptance) open**. Nothing here has been run on a phone through a tunnel.

## Finding

Wake did not work over WireGuard (or any VPN) before this change.

1. `AndroidNetworkAccess.activeLanPaths()` kept only networks with `TRANSPORT_WIFI` or `TRANSPORT_ETHERNET`. A WireGuard network reports `TRANSPORT_VPN`, so it was dropped. Discovery, pairing, status polling and Wake all share that list, so a paired host was also unreachable through the tunnel while awake. `PairedNetworkMonitor` had the same filter, and its default `NetworkRequest` also carries `NET_CAPABILITY_NOT_VPN`.
2. `WakeSender` sends the magic packet (UDP 9) to the interface's subnet broadcast address plus `255.255.255.255`. WireGuard is L3 and carries no broadcast. A tunnel address is normally a `/32`, which the broadcast derivation rejects (prefix outside 0..30), and `255.255.255.255` is dropped at the tunnel or is not forwarded onto the host's LAN.
3. `Phase08ActionViewModel.wake()` matched the path by `wakeTarget.interfaceName` (the host's LAN interface recorded at pairing) and fell back to `paths.firstOrNull()`.

## Recommendation

Use a **relay on the LAN**: an always-on machine on the host's LAN (the WireGuard server itself, the router, or a Raspberry Pi) that receives a packet over the tunnel and re-broadcasts it on the LAN. The app sends the same 102-byte magic packet, unicast over the tunnel to a configured relay address.

Relay-side, on the WireGuard server (LAN broadcast `192.168.1.255` shown; substitute the real one):

```sh
socat -u UDP4-RECVFROM:9,reuseaddr,fork UDP4-DATAGRAM:192.168.1.255:9,broadcast
```

Router-native WoL (OPNsense, pfSense, OpenWrt) that accepts a magic packet or an HTTP call also fits, but it needs a client-side mode that this repo doesn't have yet.

Alternatives, not recommended:

- **Directed broadcast through the tunnel:** add the LAN broadcast `/32` to the peer's `AllowedIPs` and enable directed-broadcast forwarding on the server. Most routers block this by default, and it silently fails when they do.
- **Unicast to the sleeping host:** works only when the router keeps a static ARP entry for the host. Fragile.

Wake confirmation is unchanged: `WakeAttempt` waits for an authenticated status read, so status polling must also work over the tunnel (`AllowedIPs` must include the host's LAN address). The packet send is never treated as host readiness.

## Slices

| # | Slice | Status |
| --- | --- | --- |
| 1 | Core + network: VPN networks appear as tunnel `LanPath`s (ordered after Wi-Fi/Ethernet, skipped by discovery); `PairedNetworkMonitor` watches VPN; `WakeSender` sends to a `WakeRelay` on tunnel paths and refuses (`RELAY_REQUIRED`) without one | implemented, JVM tests added |
| 2 | Persist the relay per paired host. **Decision:** stored in `SharedPreferences` (`WakeRelayStore`, keyed by a hash of host ID and client ID, like `LocalAliasStore`) instead of the encrypted state. It is a LAN-side IP address, not a credential, and the state codec is exact-key with a six-step migration chain, so a schema bump was more risk than the data warrants. `WakeRelay.parse` accepts only an IPv4 literal with an optional port, so saving or waking never does DNS | implemented, JVM tests added |
| 3 | UI: "Wake relay (VPN)" field and save action under Device in Connection details; the Wake screen shows a specific message for `RELAY_REQUIRED`. `Phase08ActionViewModel.wake()` passes the stored relay to `WakeSender` | implemented, compiles; no UI test |
| 4 | Physical acceptance: Deck asleep, phone on cellular + WireGuard, relay running; capture packet, authenticated-response and Steam-ready timestamps as in the Phase 11 Wake cycles | open |

## Risks to check in slice 4

- Whether the WireGuard Android VPN network reports `TRANSPORT_WIFI` as well as `TRANSPORT_VPN` (depends on `setUnderlyingNetworks`). Slice 1 treats any network with `TRANSPORT_VPN` as a tunnel, so the outcome is the same either way.
- API 36+ local-network permission behaviour for a socket bound to the VPN network is not covered by the existing LAN matrix.
- Split-tunnel configs: if the host's LAN is not in `AllowedIPs`, status reads fail and Wake will time out after 30 s even when the relay woke the host.

## Known gaps

- Removing or re-pairing a host leaves its relay entry in `SharedPreferences`. It is inert (keyed by host/client identity) but is not deleted, and the same is true of aliases.
- `WakeRelayStore` and the relay editor have no instrumented or Compose test. The parser, sender and discovery behaviour are covered by JVM tests.
- `:app:check` fails only on `CompanionComponentGalleryCaptureTest` ("Unable to load Robolectric native runtime library") on this machine. It fails identically without these changes.

## Relay suggestion

When no relay is saved, the field is pre-filled with the LAN default gateway (`WakeRelay.suggestionFrom`), and Save stores it. It is not used until saved. See [`phase-11-progress.md`](phase-11-progress.md).

## EPERM and path choice

`binding socket to network N failed: EPERM` means Android refused an explicit bind to that network. Wake now picks networks by the host's address (`WakePaths`), tries them in order, and on a tunnel path falls back to an unbound socket that follows the default network. Details and the trade-off are in [`phase-11-progress.md`](phase-11-progress.md).

## Packet sent but the device does not wake

"Sent" only means the packet left the phone. The Wake screen now shows where it went (LAN broadcasts, or the relay), the MAC it was for, and the device's reported interface. Work down this list; each step narrows it to one side:

1. **Did the packet reach the LAN?** On a machine on the device's LAN (or the relay) run `sudo tcpdump -ni any 'udp port 9'` and press Wake. Nothing seen means the phone, tunnel or relay dropped it. Over a VPN, "sent to the relay" says nothing about forwarding: the relay must run something like `socat -u UDP4-RECVFROM:9,reuseaddr,fork UDP4-DATAGRAM:<LAN broadcast>:9,broadcast`. A router gateway that does not forward is the usual cause when the relay field holds the suggested gateway.
2. **Is it the right MAC?** The host reports the MAC of its default-route interface (`_active_wake_target` in Decky). If that is Wi-Fi and the host uses a randomised or per-network MAC, it can differ from what the router knows. Compare the MAC shown on the Wake screen with `cat /sys/class/net/<interface>/address` on the device.
3. **Can the device wake from that interface at all?** On the device: `ethtool <eth> | grep Wake-on` should show `g`; set it with `sudo ethtool -s <eth> wol g` (make it persistent in NetworkManager with `802-3-ethernet.wake-on-lan magic`). For Wi-Fi use `iw phy0 wowlan show`; many Wi-Fi chips and suspend modes do not support magic-packet wake, and a fully powered-off device cannot be woken without BIOS "Wake on LAN/PCIe" enabled.
4. **Does the same MAC wake it from the Omarchy client on the LAN?** `steamos-companion-ctl wake` sends the same packet. If that fails too, the device side (steps 2–3) is the problem, not the app.

## Setting up a relay

The relay receives the magic packet the app sends over the tunnel and re-broadcasts it on the LAN. Use `UDP4-RECVFROM ... fork`: plain `UDP4-RECV` handles one packet and exits. Put the relay's LAN address in the app's "Wake relay (VPN)" field, and keep that LAN inside the peer's `AllowedIPs`.

**Unraid (recommended).** It is always on and already on the LAN. In a terminal (or as a Docker template with host networking and a restart policy):

```sh
docker run -d --name wol-relay --restart unless-stopped --network host alpine/socat \
  -u UDP4-RECVFROM:9,reuseaddr,fork UDP4-DATAGRAM:192.168.1.255:9,broadcast
```

Replace `192.168.1.255` with your LAN's broadcast address. Relay address in the app: Unraid's LAN IP. If Unraid's own WireGuard ("VPN Manager", peer type "Remote access to LAN") is the tunnel, that IP is reachable through it already. Check with `tcpdump -ni br0 udp port 9` on Unraid while pressing Wake.

**TP-Link.** Stock Archer firmware does not forward a magic packet that arrives over a VPN to the LAN, and has no setting for it. Omada gateways (ER605, ER7206) can send Wake-on-LAN from the controller's tools, but they do not accept a packet from the phone and re-broadcast it. Use the Unraid relay above, or another always-on LAN machine (a Raspberry Pi), or OpenWrt if the router model supports it. The suggested gateway address in the app is only useful if the gateway actually runs a relay.

**Raspberry Pi (or any Debian/Ubuntu box).** Wired Ethernet is best. Install `socat` and run the relay as a service:

```sh
sudo apt install socat
sudo tee /etc/systemd/system/wol-relay.service >/dev/null <<'EOF'
[Unit]
Description=Wake-on-LAN relay
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/socat -u UDP4-RECVFROM:9,reuseaddr,fork UDP4-DATAGRAM:192.168.1.255:9,broadcast
Restart=always
DynamicUser=yes
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now wol-relay
```

Replace `192.168.1.255` with the LAN's broadcast address. Relay address in the app: the Pi's LAN IP. `CAP_NET_BIND_SERVICE` lets the unprivileged service bind port 9; to avoid it, listen on a high port such as `UDP4-RECVFROM:9009` and enter `PI-IP:9009` in the app. If `ufw` is active, run `sudo ufw allow 9/udp`. Check with `sudo systemctl status wol-relay` and `sudo tcpdump -ni eth0 udp port 9` while pressing Wake. If the Pi is also the WireGuard server (for example PiVPN), the LAN must be in the peer's `AllowedIPs`, which a full-tunnel `0.0.0.0/0` config already includes.
