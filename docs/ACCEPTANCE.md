# Acceptance matrix — what was actually tested

Honesty note: LC was built and verified on a CI runner. Rows marked
**verified (JVM)** ran as real, passing tests over actual sockets and real
processes as part of `./gradlew :app:test` (43 tests, all passing). Rows
marked **not tested** require a physical Android device/emulator, which this
build environment does not have; they were **not** executed and must not be
treated as passed.

| # | Check | Pass condition | Status |
| --- | --- | --- | --- |
| 1 | Install | Signed APK installs on arm64-v8a; launcher shows **LC** | **not tested** (no device); APK is built, signed, targets arm64-v8a, `application-label: LC` (aapt-verified) |
| 2 | First run | Disclosure shown; service not running until Start | **partially verified**: disclosure/start-gating logic is in the app; interactive flow **not tested** on device |
| 3 | Notification | Persistent notification visible; Stop stops the service | **not tested** (no device) |
| 4 | Boot | No service after reboot by default; starts only after opt-in | **not tested** (no device); receiver is disabled in the manifest and only enabled with the switch |
| 5 | Localhost | Console loads at `http://127.0.0.1:6767` | **not tested** on-device; the server serving the page is **verified (JVM)**: `GET /` returns the console HTML at 200 with the session cookie, 401 + login page without |
| 6 | Auth | Missing/wrong token → 401; correct token → console | **verified (JVM)** — integration tests |
| 7 | Allowlist | `ls`, `df`, `uptime` run; non-allowlisted blocked by default | **verified (JVM)** — `uname`/`uptime` executed end-to-end over HTTP; `touch` blocked with 403 and audited; `ls`/`df`/`top -n`/`ping -c` unit-tested |
| 8 | Advanced | Toggle warns; runs as app UID; timeout and output cap enforced | **verified (JVM)** for warning-gate logic (unit), non-allowlisted execution, `su` refusal, 500 ms timeout enforcement, 64 KiB cap truncation; 1 MiB cap constant used in the app. Device UID behavior **not tested** on device |
| 9 | LAN off | Nothing reachable from another device by default | **verified (JVM)** that the server binds `127.0.0.1` only (constructors/tests bind loopback); external reachability **not tested** on-device |
| 10 | LAN on | Token + TLS required; wrong token rejected; interface shown | **partially verified (JVM)**: token enforcement verified; TLS server socket path implemented with self-signed cert (BouncyCastle) — TLS handshake **not tested** end-to-end, LAN reachability **not tested** |
| 11 | No root | `id` reports app UID; app never requests root | **verified (code)**: no root APIs, no `su` invocation anywhere; `su`/`sudo`/`setuid`/`doas`/`mount` rejected in all modes (unit + integration tests). `id` output on-device **not tested** |
| 12 | Audit | Commands, timestamps, exit codes recorded and clearable | **verified (JVM)** — audit written/blocked entries/clear via HTTP and directly |
| 13 | Uninstall | Service stops, app data removed | **verified (design)**: all state in the app sandbox, `allowBackup=false`; actual device uninstall **not tested** |
| 14 | Release | Signed APK, source archive, SHA256SUMS, test matrix published | **verified** in this release: signed APK + source archive + checksums attached, matrix in the release notes |

## Test inventory (43 tests, all passing)

- `AllowlistTest` — tokenizer, allowlist, `-c`/`-n` guards, forbidden commands
- `TokenTest` — token generation, constant-time verification, rotation, rate
  limiter windows, lockout expiry
- `SessionAuditTest` — session TTL sliding expiry, CSRF derivation, audit
  record/truncate/clear, history dedupe/cap/clear
- `CommandRunnerTest` — argv execution, stdout/stderr separation, exit codes,
  timeout enforcement, cancel enforcement, output cap enforcement, spawn
  errors
- `ServerIntegrationTest` (17 tests over real sockets) — 401 without/wrong
  token, login, no-store headers, CSRF rejection, bearer auth, allowlisted
  run end-to-end, 403 + audit for blocked commands, history/audit endpoints,
  SSE streaming of stdout and the done event, cancel, advanced mode, `su`
  refusal, end-to-end timeout, end-to-end output cap, 429 hammering, lockout
  after 5 failures, 404s

## Known limitations (documented, not hidden)

- `logcat -d` is in the allowlist but modern Android denies log reads to app
  UIDs; the command will report a permission error. Kept because the attempt
  itself is informative and audited.
- Sessions are in-memory: restarting the console (Stop/Start, reboot) logs
  browsers out; they re-pair with the token.
- Starting a foreground service from `BOOT_COMPLETED` is permitted for the
  `specialUse` type, but actual boot behavior was not observed on a device.
