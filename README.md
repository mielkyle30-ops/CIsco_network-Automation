# Cisco Network Automation — Switch Collector

A Java tool I built to automate configuration backup and inventory across a
~190-switch Cisco fleet in a manufacturing environment — cutting manual CLI
work and keeping records audit-ready.

> All credentials and device data are supplied at runtime and never committed.

## What it does
- Reads a device list, connects to each switch over Telnet (Apache Commons Net),
  and handles multiple login flows (RADIUS / line / enable-secret).
- Runs a batch of `show` commands per device — `show run`, `show version`,
  `show cdp neighbors [detail]`, `show ip interface brief`, `show interfaces
  status`, `show mac address-table`, `show vlan`, `show ip route`, and more —
  and saves each to a timestamped `.txt` per switch IP.
- Pre-pings hosts in parallel to skip unreachable devices before connecting.
- Parses `show cdp neighbors detail` into a CSV topology map
  (Device ID, IP, platform, local/remote port).
- Archives each day's outputs into a zip and cleans up.

## Files
| File | Role |
|---|---|
| `Ciscosetting.java` | Main — login flows, batch show-command runner, per-device output |
| `CollectorSwitchData.java` | Data model + parsers (hostname, version, serial, NTP) |
| `CDPParser.java` | Parses CDP neighbor detail into a CSV topology map |
| `DeviceInfo.java` | CDP neighbor record |
| `ZipTxtFiles.java` | Archives daily `.txt` outputs |

## Tech
Java · Apache Commons Net (Telnet) · parallel pre-ping · text parsing · CSV / ZIP I/O

## Why I built it
As the sole on-site network engineer, I automated the repetitive CLI work so
config backups and topology docs stay current across the whole fleet — freeing
time for actual troubleshooting.
