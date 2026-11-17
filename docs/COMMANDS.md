# Command allowlist and binaries

LC does **not** bundle BusyBox. The default allowlist is served by Android's
own system binaries (toybox plus a few Android-specific tools), so no GPL
source redistribution is triggered. LC has no native code at all; the APK is
declared for `arm64-v8a` and runs the same system binaries every Android arm64
device ships.

| Command | Default invocation | Served by | Notes |
| --- | --- | --- | --- |
| `ls` | `ls [args]` | toybox (`/system/bin/ls`) | |
| `df` | `df [args]` | toybox (`/system/bin/df`) | |
| `du` | `du [args]` | toybox (`/system/bin/du`) | |
| `free` | `free` | toybox (`/system/bin/free`) | |
| `uptime` | `uptime` | toybox (`/system/bin/uptime`) | |
| `id` | `id` | toybox (`/system/bin/id`) | shows the app UID — this is the no-root proof |
| `uname` | `uname [args]` | toybox (`/system/bin/uname`) | |
| `getprop` | `getprop [key]` | `/system/bin/getprop` | Android-specific |
| `ps` | `ps [args]` | toybox (`/system/bin/ps`) | |
| `top` | `top -n 1` | toybox (`/system/bin/top`) | `-n` is required; endless refresh is refused |
| `logcat` | `logcat -d` | `/system/bin/logcat` | an app UID normally cannot read the system log on modern Android; expect a permission error — the attempt is still audited |
| `ip` | `ip addr` | toybox (`/system/bin/ip`) | |
| `date` | `date` | toybox (`/system/bin/date`) | |
| `ping` | `ping -c 1 <host>` | `/system/bin/ping` | `-c` is required; flood flags are refused |

Rules:

- The first token must be in the allowlist; arguments are free.
- `ping` without `-c` and `top` without `-n` are rejected before any process
  is spawned (the rejection is recorded in the audit log).
- `su`, `sudo`, `setuid`, `doas`, `mount`, `remount` are rejected in every
  mode, including advanced mode.
- Commands are always passed as an argument vector. LC never spawns a shell;
  in advanced mode you can still run an explicit `sh -c '...'` yourself — it
  runs as the app UID with the same timeout and output caps.
