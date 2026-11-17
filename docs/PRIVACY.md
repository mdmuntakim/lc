# Privacy

What LC stores, where, and what happens on uninstall.

## On-device data (app sandbox only, `/data/data/dev.lc.console/`)

| Data | File | Removed on uninstall |
| --- | --- | --- |
| Pairing token | `shared_prefs/lc.xml` | yes |
| Session tokens | memory only (not persisted) | yes (also lost on service restart) |
| TLS keystore for LAN mode | `files/lc-tls.p12` + keystore password in prefs | yes |
| Audit log | `files/audit.jsonl` | yes |
| Command history | `files/history.jsonl` | yes |
| Settings | `shared_prefs/lc.xml` | yes |

`android:allowBackup="false"` is set in the manifest, so none of this is
included in Android's cloud backup or device transfer.

## What Android may retain after uninstall

- The app sandbox itself is wiped on uninstall. Nothing in it survives.
- Android retains no leftovers of this app outside its sandbox: no external
  storage is used, no content providers are published, no downloads or media
  are written.

## Network behavior

- The console binds `127.0.0.1:6767` (or `0.0.0.0:6767` over TLS in opt-in LAN
  mode). It makes **no** outbound connections, includes **no** third-party
  assets, fonts, analytics, crash uploaders or CDN references in the app or in
  the served pages.
- A command can use the network only because you ran it (e.g. `ping -c 1
  example.com`) — the command runs as the app UID and its traffic is the app's.

## Telemetry

None. There is no code path in LC that contacts anything other than the local
socket it binds.
