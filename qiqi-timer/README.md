# Qiqi Timer

A minimal Fabric mod that polls the `/nextleg` command periodically and displays the legendary spawn timer in a HUD in the **bottom-right** corner of the screen.

## Features

- **Automatic polling**: Sends `/nextleg` (or a configurable command) at a configurable interval (default 60 seconds).
- **Chat parsing**: Listens for the server’s timer response in chat and parses it (supports “X minutes and Y seconds” and “X seconds” formats; configurable regex).
- **HUD**: Shows `Next leg: XXm XXs` or `Next leg: ?? : ??` when the timer is unknown.
- **Send J at 30 sec**: When the timer reaches the 30-second mark (or a configurable threshold), sends key **J** once (e.g. to open a menu or trigger another mod). Optional; can be disabled in config.

## Requirements

- Minecraft 1.21.1
- Fabric Loader ≥ 0.16.0
- Fabric API

No Cobblemon or other mods required.

## Config

Config file: `config/qiqi-timer.json` (created on first run).

| Option | Default | Description |
|--------|---------|-------------|
| `nextlegCommand` | `"/nextleg"` | Command to run for the timer (without or with leading `/`). |
| `pollIntervalSeconds` | `60` | Seconds between polls. |
| `hudVisible` | `true` | Whether to show the HUD. |
| `timerPattern` | regex for “X minutes and Y seconds” | Used to parse the server’s timer message (group 1 = minutes, group 2 = seconds). |
| `timerPatternSecondsOnly` | regex for “X seconds” | Fallback pattern (group 1 = seconds). |
| `sendJAt1MinLeft` | `true` | When true, send key J once when remaining time is ≤ `sendJThresholdSeconds`. |
| `sendJThresholdSeconds` | `30` | Send J when remaining is in [0, this] (default 30 = 30 seconds before). |

If the server message format differs, adjust the regex patterns in the config. The mod also has a generic fallback that recognises `Xm Ys` and `Xs` style text.

## Build

From the `qiqi-timer/` directory:

```bash
./gradlew build
```

Output: `build/libs/qiqi-timer-<version>.jar`. Copy it to your mods folder.

## Compatibility

- Can be used **with** auto-qiqi (they are independent; both can poll `/nextleg` and show timers).
- Can be used **without** auto-qiqi as a standalone “nextleg timer HUD” mod.
