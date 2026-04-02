# Qiqi Timer

A minimal Fabric mod that polls the `/nextleg` command periodically and displays the legendary spawn timer in a HUD in the **bottom-right** corner of the screen.

## Features

- **Automatic polling**: Sends `/nextleg` (or a configurable command) at a configurable interval (default 60 seconds).
- **Chat parsing**: Listens for the server’s timer response in chat and parses it (supports “X minutes and Y seconds” and “X seconds” formats; configurable regex).
- **HUD**: Shows `Next leg: XXm XXs` or `Next leg: ?? : ??` when the timer is unknown.
- **Send J at 30 sec**: When the timer reaches the 30-second mark (or a configurable threshold), sends **K** (reload legendary percentages) then **J** once. **15 seconds later** sends **J** again so the recomputed probs are used. Optional; can be disabled in config. When **auto-qiqi** is present, the J (legendary hop) action is invoked directly so it runs **even when the game window is not focused**.

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
| `sendJAt1MinLeft` | `true` | When true, send K then J when remaining ≤ threshold; 15s later send J again (probs recomputed). (Name is legacy; timing is controlled by `sendJThresholdSeconds`.) |
| `sendJThresholdSeconds` | `30` | Send K+J when remaining is in [0, this] seconds. Default **30** = 30 seconds before next leg. If autohop triggers at 1 min, set this to `30` in `config/qiqi-timer.json`. |
| `toggleAutohopKeyCode` | `79` | GLFW key code for the "Toggle Autohop" keybinding default (79 = O). You can also rebind it in **Options → Controls → Qiqi Timer**. |

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
