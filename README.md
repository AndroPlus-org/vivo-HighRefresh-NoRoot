# vivo-HighRefresh-NoRoot

[日本語](README.ja.md)

Rootless high refresh rate for vivo phones (OriginOS / Funtouch OS). Pins the
panel at 144 Hz — or 165 Hz on panels that have it — over adb. No root, no
Magisk, no bootloader unlock.

## Requirements

- adb (USB or wireless debugging)
- bash (Linux, macOS, WSL, Git Bash or Termux), or PowerShell on Windows
- To rebuild the dex only: JDK 17 and `d8` from Android SDK build-tools.
  A prebuilt `RmsVote.dex` is included.

## Usage

```bash
./vivo-rms.sh modes          # rates the panel offers, and vendor limits
./vivo-rms.sh set 144        # pin 144 Hz
./vivo-rms.sh get            # current rate, e.g. rate=144.0
./vivo-rms.sh reason         # the request the vendor applies, e.g. RmsVote|144
./vivo-rms.sh release 0 900001   # undo
```

On Windows, use `vivo-rms.cmd` with the same commands (it runs
`vivo-rms.ps1` without touching the execution policy):

```bat
vivo-rms.cmd set 144
vivo-rms.cmd release 0 900001
```

The vote stays until it is released or the phone reboots. `hold <fps>` votes
and releases on Ctrl-C instead.

While an app that asks for its own display mode is in front, that app's
request wins. Check with `reason`; it shows `reqFps:<activity>|<fps>` then.

## How it works

vivo's framework (`com.vivo.rms.common.RefreshRateRequester`) asks for a rate
through the `rms` binder service (`com.vivo.rms.IRM`, served from
`vivo-services.jar` in system_server). It sends raw Parcels **without an
interface token**. `service call` always writes one, so every argument lands
shifted; `RmsVote.dex` runs through `app_process` and sends them the
framework's way.

| tx | method | args |
|---|---|---|
| 23 | acquireRefreshRate | long handle, String scene, String reason, int fps, priority, duration, caller, client, states, dfps, extra |
| 24 | releaseRefreshRate | int caller, long handle |
| 26 | getActiveRefreshRate | — (returns float) |
| 31 | acquireRefreshRate (V2) | long handle, String scene, String reason, int display, resolution, fps, dfps, configBits, states, priority, duration, caller, client, flags, arg1, arg2 |

What the server (`com.vivo.services.rrm.RefreshRateAdjuster`) checks:

- **No uid check** on the refresh-rate calls.
- `caller` must be a process AMS tracks; an `app_process` started from shell
  is not, and its vote is dropped silently. **`caller <= 0` makes the server
  stand in for itself** (system_server) — the same thing the vendor's own
  `dumpsys rms --display --set-fps` does. That dump command needs
  `persist.sys.rms.allow_dump`, which shell cannot set.
- 144 Hz is a *private fps* (`persist.sys.private_fps`) and is only picked
  with flag `2` (use private fps). The defaults mirror the vendor's adb path:
  scene `FixRate`, priority 100, flags `15`, display `-1`.
- system_server numbers its own requests from handle 1 under the same caller,
  so the default handle is 900001 to stay clear of them.

## Compatibility

Tested on one vivo phone: iQOO 15 Ultra 1440×3168 panel with 60 / 90 / 120 / 144 Hz,
`RefreshRateAdjuster` SDK 230409. Other models should work if
`./vivo-rms.sh modes` lists the rate and `adb shell service list` shows
`rms: [com.vivo.rms.IRM]`. If not, pull `/system/framework/vivo-services.jar`
and compare `RMNative.onTransact` with the table above.

## Disclaimer

**Use at your own risk.** This drives the display through an undocumented
vendor interface. Expect more battery drain and heat.

## License

[MIT](LICENSE)
