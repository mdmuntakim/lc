# LC — a visible, authenticated local diagnostics console for Android

LC gives the device owner a small web console for running diagnostic commands
(`ls`, `df`, `uptime`, `id`, …) and reading their output in real time, from the
device browser or a paired client. It is visible, user-started, authenticated
and strictly limited to the app's own privileges.

**This app never uses root, never calls `su`, never installs anything outside
its own sandbox, and never sends anything off the device.**

## Design principles

| Principle | How LC does it |
| --- | --- |
| Visible | Normal launcher icon, name `LC`; a persistent `LC console running` notification with a **Stop** action while the service runs |
| Consented | First-run disclosure must be accepted before the service can start; the service starts only from the **Start** button (or the opt-in boot switch) |
| Local by default | Binds `http://127.0.0.1:6767` only; nothing is reachable from the network |
| Authenticated | Random 256-bit pairing token; wrong/missing token → `401` before any command handling |
| Least privilege | Commands run as the app UID via an argument vector; read-only allowlist by default; advanced mode is opt-in and warned |
| Auditable | Every command, status, exit code and duration in a local JSONL audit log; clearable; no telemetry anywhere |

## Quick start

1. Install the APK (arm64-v8a), launch **LC**.
2. Read and accept the disclosure.
3. Tap **Start** — the token is generated on first start and shown in the app.
4. On the device open `http://127.0.0.1:6767`, enter the pairing token.
5. Run commands. Stop with the in-app **Stop** button or the notification action.

## Security model

- **Token**: 256-bit random, shown in the app, comparable in constant time.
  `Rotate token` invalidates every session immediately.
- **Sessions**: `HttpOnly` `SameSite=Strict` cookie after token login; 12 h
  inactivity expiry; held in memory only (restarting the console logs clients
  out — nothing sensitive about the web layer is persisted).
- **CSRF**: double-submit — state-changing requests with cookie auth need the
  `X-LC-CSRF` header matching the `lc_csrf` cookie. Bearer-token requests are
  CSRF-free by construction. `curl -H "Authorization: Bearer <token>"` works
  for scripted use.
- **Rate limiting / lockout**: 240 requests/min per IP; 5 auth failures lock
  the address out for 5 minutes.
- **Headers**: every response carries `Cache-Control: no-store`,
  `Pragma: no-cache`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`.
- **Command execution**: argument vector only — LC never spawns a shell
  itself. Allowlist mode restricts the first token (see
  [docs/COMMANDS.md](docs/COMMANDS.md)); `su` is refused in every mode. Hard
  limits: 30 s timeout (configurable 5–300 s) and a 1 MiB output cap, both
  enforced by killing the process.
- **LAN mode**: off by default; when enabled the console serves **TLS** with a
  self-signed certificate (BouncyCastle, generated on device) and still
  requires the token. The browser will show a certificate warning — that is
  intentional: it prevents silent impersonation on the LAN.

## Foreground service type

The service uses `specialUse` (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) with the
manifest property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` describing the use case.
Unlike `dataSync` it has no usage-time cap, which matches a console the owner
explicitly keeps running. The service is `START_NOT_STICKY`: it only ever runs
because the user (or the opt-in boot receiver) started it.

## Build

```sh
./gradlew :app:assembleDebug :app:test     # build + 43 unit/integration tests
bash scripts/build-release.sh              # signed release APK into dist/
```

The test suite exercises the whole HTTP/auth/exec stack on the JVM with real
sockets — see [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) for the honest
acceptance matrix (what was tested and what was not).

## Dependencies and licences

| Dependency | Licence | Why |
| --- | --- | --- |
| AndroidX core/appcompat, Material Components | Apache-2.0 | App UI |
| BouncyCastle (bcprov, bcpkix) | MIT-style Bouncy Castle licence | Self-signed TLS certificate generation for LAN mode |

No BusyBox is bundled, so no GPL obligations arise: commands are served by
Android's own toybox/system binaries (mapping in
[docs/COMMANDS.md](docs/COMMANDS.md)). App source is MIT-licensed — see
[LICENSE](LICENSE) and [NOTICE](NOTICE).

## Uninstall

Stopping/uninstalling removes the service, the pairing token, the TLS
keystore, the audit log and the history: they all live inside the app sandbox
and `allowBackup` is disabled. See [docs/PRIVACY.md](docs/PRIVACY.md).
