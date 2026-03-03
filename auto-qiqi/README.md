# Auto-Qiqi — Mod Code Overview

This document explains the **Auto-Qiqi** Fabric mod codebase for human review. The mod provides automation for the Cobblemoon (Cobblemon) Minecraft pack: auto-battle, legendary world tracking, capture flow, tower NPCs, auto-fish, gold mining, and walking/pathfinding.

---

## 1. Overview

- **What it is:** A client-side Fabric mod for Minecraft 1.21.1 that automates Cobblemon-related gameplay (battles, captures, legendary spawn tracking, tower runs, fishing, mining).
- **Entrypoint:** `com.cobblemoon.autoqiqi.AutoQiqiClient` (Fabric `client` entrypoint).
- **Config:** `config/auto-qiqi.json` (loaded via `FabricLoader.getInstance().getConfigDir()`).
- **Logging:** Stdout lines are prefixed with `[Auto-Qiqi/<module>]`; persistent session events go to `auto-qiqi/session-YYYY-MM-DD.log` and `session-stats.json` (see [Logs & paths](#6-logs--paths)).

---

## 2. Build & run

- **Java:** 21.
- **Build:** From `auto-qiqi/`: `./gradlew build`.
- **Deploy:** The `build` task copies the remapped JAR into the Modrinth profile `Cobblemoon1.1.8` (`deploy` task). Set `JAVA_HOME` to Java 21 if needed:  
  `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **Dependencies:** Fabric API, Fabric Loader; **Cobblemon** is `modCompileOnly` (optional at runtime but required for full functionality).

---

## 3. Code architecture

### 3.1 Entry point and tick loop

**`AutoQiqiClient.java`** is the main client initializer. It:

- Loads config, sets initial battle mode and world list, optionally enables `AutoReconnectEngine`.
- Registers **keybinds** (K = config/battle screen, H = legendary HUD, J = legendary auto-switch, U = force poll, L = legendary on/off, I = tower start, **O = stop all**).
- Registers **screen events** for legendary GUI world switching and tower GUI handling.
- Registers **client commands** under `/pk` (see below).
- Registers a **client tick callback** that drives all engines in a fixed order.

**Tick order (each client tick):**

1. Blocked-state tracking (disconnect / open screen).
2. `AutoReconnectEngine` (reconnect flow if enabled).
3. First-tick init: disable walk/capture, release movement keys, schedule session recap in chat.
4. **Keybind handling** (opens config screen, toggles HUD/legendary/poll/mod/tower; **Stop All** key works even with a screen open so automation is always cancelable).
5. **CaptureEngine** (if active): tick walk/engage/ball, ball throw/wait, and battle-end detection (debounced when `getBattle()` becomes null).
6. **AutoBattleEngine** (if battle mode ≠ OFF and capture not active): roam, engage wild, fight or hand off to capture.
7. **Legendary:** If paused for capture, optionally auto-resume after capture finishes and min pause time.
8. **AutoSwitchEngine** (legendary world rotation and timer polling).
9. **PokemonWalker** (pathfinding toward a target entity).
10. **TowerNpcEngine** (tower entrance + floor NPCs + healer).
11. Circle walk (if enabled).
12. Hunt timer (stops hunt when duration expires).
13. **GoldMiningEngine** (Nether gold when idle).
14. Periodic Pokedex scan (e.g. every 30s) for uncaught count.

So: **capture has priority over roaming battle**; legendary and tower run in parallel with battle/capture; mining is lowest priority.

---

## 4. Modules (by package)

### 4.1 Battle (`battle/`)

| Class | Role |
|-------|------|
| **CaptureEngine** | Full capture flow: walk to target, aim, simulate send-out key, then in-battle sequence (False Swipe, Thunder Wave, ball throws, switch to tank if needed). **Move-based team selection:** the engine picks Pokemon by checking their move set (via reflection on Cobblemon's `MoveSet`), not by species name. For False Swipe, **Gallade** is preferred over **Marowak** over any other Pokemon that knows the move. For Thunder Wave, any Pokemon with the move is chosen. If no valid choice is found, the mod **does nothing** and logs the reason (user intervention needed). For **whitelisted legendaries** (config `legendaryCaptureWhitelist`): after applying False Swipe/Thunder Wave (or when unable to), the mod throws 5 Ultra Balls, then one **Master Ball** (if in hotbar); if Master Ball is not in hotbar, it continues with Ultra Balls. Holds a single **CaptureSession**; state is in the session, engine ticks it. Phases: `IDLE`, `WALKING`, `ENGAGING`, `IN_BATTLE`. |
| **CaptureSession** | Holds all mutable state for one capture run (target, phase, walking/engagement/ball counts, etc.). Created on start, cleared on stop/success/fail. |
| **AutoBattleEngine** | Roaming wild battles: scan for Pokemon, walk/aim, simulate send-out. Modes: **OFF**, **BERSERK**, **ROAMING**, **TRAINER**. Loot pickup; engage blacklist. When not in capture mode, battle decisions go through **BattleDecisionRouter**. |
| **BattleDecisionRouter** | Central router for in-battle decisions when not in capture mode: general action (FIGHT/SWITCH), move, switch. TRAINER/BERSERK use TrainerBattleEngine; ROAMING uses random. Called from battle GUI mixins. |
| **TrainerBattleEngine** | Smart trainer battles: type effectiveness (TypeChart), STAB, base power; no voluntary switch; on forced switch picks strongest attacker. Used when battle mode is TRAINER. |
| **TypeChart** | Gen 6+ type effectiveness lookup. Used by TrainerBattleEngine (and any move/target scoring). |
| **BattleMode** | Enum: `OFF`, `BERSERK`, `ROAMING`, `TRAINER`; persisted in config as `battleMode`. |

Battle **decisions** (Fight / Switch / Capture, move, switch target) are **injected via mixins** into Cobblemon’s battle GUI (see [Mixins](#5-mixins)).

---

### 4.2 Legendary (`legendary/`)

| Class | Role |
|-------|------|
| **AutoSwitchEngine** | State machine for legendary lifecycle: IDLE → open menu → switch world (GUI or `/home`) → poll timer (`/nextleg`) → wait for response. On “legendary appeared” event, switches to event world. Can pause for capture (`PAUSED_FOR_CAPTURE`) and auto-resume after capture + min pause time. |
| **GuiWorldSwitcher** | Learns world list from `/monde` (or configured command) GUI and performs world switches by clicking buttons. Used for “GUI” worlds; “home” worlds use `/home` + optional dimension check. |
| **WorldTracker** | Tracks current world name and per-world timer state (from chat). `getSoonestRemainingSeconds()` returns the soonest event time across all worlds (used for boss-priority rule). |
| **WorldTimerData** | Holds timer state for a world (e.g. minutes/seconds until next legendary). |
| **ChatMessageHandler** | Parses chat for: timer lines (regex from config), “legendary appeared” events, spawn messages (pokemon + location). Updates WorldTracker and triggers AutoSwitchEngine. |
| **PokemonWalker** | A* pathfinding (using common `PathFinder`) to walk to an entity (e.g. after `/pk walk <index>`). Uses `MovementHelper` for rotation; waypoint reach, stuck detection, timeout. |
| **DirectionGuide** | Renders arrow/guide toward a target (e.g. for `/pk guide <index>`). |
| **AutoReconnectEngine** | On disconnect, clicks “Rejoindre” (or configured button) after a delay and optionally retries; can click “Back to server list” if configured. |

---

**Boss vs legendary:** Boss hunt is prioritized over world switching only when the soonest legendary timer has **≥ 1m30s** remaining; with less time, the engine proceeds with the switch so the legendary is not missed.

### 4.3 NPC / Tower (`npc/`)

| Class | Role |
|-------|------|
| **TowerNpcEngine** | Tower flow: find “Directeur de la tour” (entrance), interact → open floor menu → click “Accès à l’étage 1” to teleport; find floor combat NPC (EasyNPC Humanoid), walk and interact to start battle. After defeat, can walk to healing machine (Cobblemon block), interact, then restart. States: IDLE, WALKING_TO_ENTRANCE, WALKING_TO_FLOOR_NPC, WALKING_TO_HEALER. |
| **TowerGuiHandler** | Handles tower-specific screens (chest/menu and EasyNPC dialog): detect floor menu, “Accès à l’étage”, and dialog buttons so TowerNpcEngine can drive clicks. |

Tower is mutually exclusive with legendary auto-switch: starting tower disables legendary hop.

---

### 4.4 Fish (`fish/`)

| Class | Role |
|-------|------|
| **AutofishEngine** | Cast, wait for bite (entity or sound detection), reel. Options: multi-rod, no-break, persistent mode, auto-aim, ClearLag regex. |
| **AutofishScheduler** | Schedules cast/recast with delay. |
| **WaterScanner** | Finds water blocks for casting. |
| **FishMonitorMP**, **FishMonitorMPMotion**, **FishMonitorMPSound** | Bite detection (motion vs sound). |
| **Action** / **ActionType** | Internal action types for the fish state machine. |

Paused when AutoBattleEngine starts a battle (`pausedFishingForBattle`).

---

### 4.5 Mine (`mine/`)

| Class | Role |
|-------|------|
| **GoldMiningEngine** | In Nether, when idle (no capture, no battle, etc.), pathfinds to nether gold ore (NethergoldScanner), mines, picks up. Durability safety and repair cooldown from config. |
| **NethergoldScanner** | Finds nether gold ore blocks in range. |

---

### 4.6 Common (`common/`)

| Class | Role |
|-------|------|
| **PokemonScanner** | Scans for wild Pokemon entities in range (80 blocks). `scan()` for periodic/quick scan; `manualScan()` for `/pk scan` (results kept for `/pk capture <index>`). Helpers: boss/legendary/uncaught detection, `countUncaught()`, `getFromLastScan(index)`. |
| **SessionLogger** | Writes session log (`session-YYYY-MM-DD.log`) and `session-stats.json`. Events: CAPTURE, CAPTURE_FAIL, KILL, LEGENDARY_SPAWN, WORLD_SWITCH, BALL, BATTLE, ERROR, INFO. On next launch, recap is read and shown in chat, then stats reset. |
| **MovementHelper** | Shared movement: forward/back/strafe, release keys, rotation (yaw/pitch) toward target. **Ball throw:** detects entity blocking the throw line; when the blocker is our own Pokemon, computes preferred strafe direction from player/target/blocker positions so we move to clear the line (avoids "pas un pokémon sauvage" errors). Used by CaptureEngine, AutoBattleEngine, PokemonWalker, TowerNpcEngine. |
| **PathFinder** | A* pathfinding in the world (block collision). Used by PokemonWalker and GoldMiningEngine. |
| **HumanDelay** | Random delay in a range (ms) for “human-like” timing (e.g. command delays). |
| **TimerParser** | Parses timer strings (e.g. “X minutes and Y seconds”) from config regex. |

---

### 4.7 Config & HUD

| Class | Role |
|-------|------|
| **AutoQiqiConfig** | All options (battle, legendary, reconnect, mining, fish, tower, etc.). JSON in `config/auto-qiqi.json`. **Legendary capture:** `legendaryCaptureWhitelist` — list of Pokémon names; when a legendary is in this list, after setup (False Swipe/Thunder Wave) and 5 Ultra Balls, the mod throws one Master Ball (if in hotbar); otherwise it continues with Ultra Balls. |
| **AutoQiqiConfigScreen** | In-game config screen (opened by K key). |
| **AutoQiqiHud** | Renders legendary HUD (timer, state, world list, etc.) via `HudRenderCallback`. |

---

## 5. Mixins

Defined in `auto-qiqi.mixins.json`; package `com.cobblemoon.autoqiqi.mixin`. They inject into Cobblemon/Fabric/Minecraft to drive battle choices and chat.

| Mixin | Target | Purpose |
|-------|--------|---------|
| **BattleGeneralActionSelectionMixin** | Cobblemon battle general action (Fight/Switch/Capture) | Routes decision to CaptureEngine (capture flow) or AutoBattleEngine/TrainerBattleEngine (fight/switch). |
| **BattleMoveSelectionMixin** | Cobblemon move selection | Picks move (TrainerBattleEngine or CaptureEngine move choice). |
| **BattleSwitchPokemonSelectionMixin** | Cobblemon switch Pokemon selection | Picks which Pokemon to switch to. |
| **BattleTargetSelectionMixin** | Cobblemon target selection | Picks target in double battles. |
| **BattleCaptureEndHandlerMixin** | Cobblemon capture end handler | Hook when capture sequence ends (e.g. for ball result). |
| **ChatHudMixin** | Minecraft ChatHud | Intercepts chat lines and forwards to ChatMessageHandler for timer/legendary/spawn parsing. |
| **ClientPlayNetworkHandlerMixin** | Client play network handler | Used for connection/disconnect handling (e.g. for reconnect). |
| **FishHookEntityMixin** | Fish hook entity | Hook for bite detection (autofish). |

---

## 6. Logs & paths

- **Game log (stdout):** `logs/latest.log`. All `AutoQiqiClient.log("Module", "message")` appear as `[Auto-Qiqi/Module] message`. Rotated on each launch.
- **Session log:** `auto-qiqi/session-YYYY-MM-DD.log` — timestamped events (CAPTURE, KILL, WORLD_SWITCH, etc.). Primary for reviewing AFK sessions.
- **Session stats:** `auto-qiqi/session-stats.json` — counters and lists; read on launch for recap, then reset.
- **Config:** `config/auto-qiqi.json`.

Paths are under the game dir (Modrinth profile: `~/Library/Application Support/ModrinthApp/profiles/Cobblemoon1.1.8/`).

---

## 7. Commands (`/pk`)

- **`/pk scan`** — Run PokemonScanner manual scan; results used by `/pk capture <index>` and `/pk walk <index>`.
- **`/pk walk <index>`** — Start PokemonWalker toward the `<index>`-th entity from last manual scan.
- **`/pk guide [stop]`** — Start or stop direction guide toward scan index.
- **`/pk stop`** — Stop all automation: capture, walk, guide, legendary world-switch, auto-battle, mining. Closes the world-switch menu if open. Automation is **always cancelable** (walking, in battle, or during world switching) via `/pk stop` or the **Stop All** keybind (O), which works even when a screen is open.
- **`/pk debug <index>`** — Debug entity at index from last scan.
- **`/pk capture [stop]`** — In battle: trigger capture flow; with index: start capture on that scan target. `capture stop` stops CaptureEngine.
- **`/pk hunt [stop]`** — Start hunt timer (hours) to enable legendary + roaming for a duration; `hunt stop` or status.
- **`/pk tp`** — Show TP status; `tp default <last|random>`; `tp <worldIndex> <last|random>` for world teleport mode.
- **`/pk reconnect`** — Toggle auto-reconnect on/off.

---

## 8. Design notes

- **Cobblemon is Kotlin:** Some types (e.g. MoveSet, ClientParty) use `KMappedMarker` and are not directly visible to Java. The code uses reflection (e.g. `Object` cast + `getClass().getMethod(...)`) where needed.
- **Battle ownership:** Only battles started by the mod (simulated send-out within a short tick window) are auto-fought; manually started battles are not driven by the mod (to avoid hijacking player-initiated fights).
- **Capture vs roaming:** When CaptureEngine is active, AutoBattleEngine is not ticked. So targeted capture (e.g. `/pk capture 1`) takes precedence over roaming.
- **Legendary pause:** When a legendary spawns, the mod can pause world switching and optionally start capture; after capture ends and a minimum pause time, it can auto-resume switching.

---

## 9. Domain-Driven Design (DDD) assessment

The codebase is **partially aligned** with DDD ideas; it is organized by feature rather than by technical layer, but it does not follow a strict domain/application/infrastructure split or explicit aggregates.

### What aligns with DDD

- **Feature-oriented packages as bounded contexts (loose):** `battle/`, `legendary/`, `npc/`, `fish/`, `mine/`, `walk/` each group a distinct capability. That resembles bounded contexts or at least clear feature slices, with a shared vocabulary (Capture, Battle, Legendary, Walker, etc.).
- **Ubiquitous language:** Domain terms are used consistently in class and method names (CaptureEngine, Phase, BattleMode, WorldTracker, AutoSwitchEngine, etc.).
- **Some pure domain logic:** `TypeChart` is effectively a domain service (type effectiveness, no I/O). `BattleMode` and enums like `Phase` act as domain value concepts.
- **Anti-corruption at the boundary:** Integration with Cobblemon is isolated in mixins and reflection; the rest of the code does not depend on Cobblemon's internals directly. Mixins + engines form an application-level "port" toward the Cobblemon/Minecraft "adapters."

### Where it diverges from classic DDD

- **No explicit domain layer:** "Engines" mix orchestration (tick, state machines), decision logic (which move, fight vs switch), and infrastructure (config, logging, keybinds, commands). In strict DDD you would separate domain services (e.g. "decide next move") from application services (e.g. "tick capture flow") and infrastructure (file I/O, Minecraft API).
- **Session/run state objects (recent):** `CaptureSession` and `LegendaryRunState` now hold per-run state; engines are thinner. There are still no full aggregates (e.g. `BattleSession`) with explicit consistency boundaries; battle state remains Cobblemon’s.
- **"Common" as a catch-all:** `common/` mixes cross-cutting concerns: scanning (closer to domain/application), logging and persistence (infrastructure), pathfinding and movement (shared application/infrastructure). In DDD terms, these would often be split (e.g. domain services vs shared kernel vs infrastructure).
- **Legendary package mixes several concerns:** One package contains world switching (application), timer state (domain-like), chat parsing (infrastructure), pathfinding/walker (could be its own context), and reconnect (separate capability). That weakens a single, well-defined bounded context.
- **Dependency direction:** `AutoQiqiClient` depends on all engines and config; engines depend on each other (e.g. CaptureEngine → PokemonWalker, AutoBattleEngine → CaptureEngine) and on config/logger. There is no "domain at the center, dependencies pointing inward" layering; the design is more a web of feature engines coordinated by the client.

### Summary

The structure is **good for feature locality and navigation**: each package owns a clear slice of behavior and the naming reflects the domain. It is **not** a full DDD implementation: there are no aggregates, no explicit domain/application/infrastructure layers, and some packages (notably `legendary/`, `common/`) mix multiple DDD-style concerns. For a mod of this size, the current organization is a pragmatic **feature-slice / bounded-context-lite** approach rather than strict domain-driven design.

---

## 10. Key refactors (highest impact, fewest changes)

**Update:** Refactor 1 (session objects) is **done** for Capture and Legendary — see §12. Refactor 2 remains the main P0.

### 1. Explicit session/context objects instead of engine state

**Problem:** `CaptureEngine` and `AutoSwitchEngine` hold 50+ fields of mutable state each. There is no first-class concept of “a capture” or “a legendary run”; state is hidden inside singletons, hard to reason about and untestable.

**Refactor:** Introduce **session objects** (e.g. `CaptureSession`, `LegendaryRunState`) that hold all state for one capture or one legendary cycle. Engines become thin: “load or create session → tick(session) → optionally persist.” Session can be inspected, passed, and tested without the engine. Apply the same idea to `PokemonWalker` (e.g. `WalkSession`) and optionally `AutoSwitchEngine` (state object for the state machine).

**Impact:** Clear ownership of state, testable behavior, easier debugging and future features (e.g. pause/resume a capture).

### 2. Extract domain logic from engines into domain services

**Problem:** “What to do next” in capture (False Swipe count, Thunder Wave cadence, ball cycle, switch decisions) and battle (move/switch choice) is embedded inside engine `tick()` and mixin code. Domain rules are mixed with orchestration and I/O.

**Refactor:** Extract **domain services** (or strategy types) that depend only on domain inputs and return decisions:
- **Capture:** `CaptureStrategy.decide(sessionSnapshot, battleSnapshot)` → `CaptureAction` (and update session). Rules like “min False Swipes for level”, “re-apply TW every 8 balls”, “cycle ball/FS” live here. No Minecraft/Cobblemon references.
- **Battle:** Move selection and switch selection already partly in `TrainerBattleEngine`/`TypeChart`; ensure capture and roaming both use a single, testable decision layer.

Engines then only: read current state (from session + Cobblemon adapter), call domain service, apply the chosen action via existing mixins/key simulation.

**Impact:** Domain logic becomes unit-testable; engines become obvious “orchestrate and apply”; DDD alignment and maintainability improve.

---

Optional third, if you want to reduce global coupling: **inject config and logging** (e.g. `CaptureConfig`, `SessionRecorder` interfaces) into engines instead of `AutoQiqiConfig.get()` / `SessionLogger.get()` everywhere. Enables testing with mocks and clarifies boundaries.

---

## 11. Current structure vs design (Engine / Action / Battle / BattleDecision)

How the codebase lines up with the target design (Engines = high-level task + iteration; Actions + executors; Battle + BattleIntent; BattleDecisionMaker).

### Engines

| Design concept | Current code | Alignment |
|----------------|--------------|-----------|
| Engine = high-level task + iteration | `CaptureEngine`, `AutoBattleEngine`, `AutoSwitchEngine`, `TowerNpcEngine`, etc. | **Partial.** Each engine owns a high-level goal and a loop/run. Iteration is inside the engine (e.g. legendary cycle, tower loop). ✓ |
| | | Engines also do low-level work: walking, aiming, engage logic are **inlined** in CaptureEngine and AutoBattleEngine instead of calling discrete "actions." ✗ |

### Actions and executors

| Design concept | Current code | Alignment |
|----------------|--------------|-----------|
| Action = discrete step (WalkTo, Engage, ThrowBall, …) | No first-class **Action** type. | **Missing.** Walk/engage/throw are logic blocks inside engines, not named actions. |
| Executor for WalkTo | `PokemonWalker` — `startWalking(entity)`, `tick()`, `stop()`. | **Present.** Walker is the component that "runs" walk-to-entity. ✓ |
| Executor for Engage | Inline in `CaptureEngine` and `AutoBattleEngine`: aim (MovementHelper), send-out key, LOS strafing. | **No separate executor.** Engage is not a reusable action; each engine reimplements aim + key. ✗ |
| MovementHelper / PathFinder | `MovementHelper`, `PathFinder` used by Walker and engines. | **Shared infra** for "how to move/look." Not exposed as "run this action." ✓ for reuse, ✗ for action abstraction. |

So: **Walker** matches "executor for WalkTo." **Engage** (and ThrowBall as a multi-step) have no dedicated action or executor; they live inside the engines.

### Battle and intent

| Design concept | Current code | Alignment |
|----------------|--------------|-----------|
| Battle = current encounter | `CobblemonClient.INSTANCE.getBattle()` — no first-class **Battle** object in our code. | **Implicit.** Battle state is "whatever Cobblemon exposes." No Battle aggregate. ✗ |
| BattleIntent = win / kill / capture | `BattleMode` (OFF, BERSERK, ROAMING, TRAINER) + `targetForCapture` in AutoBattleEngine. | **Implicit.** Intent is "mode + targetForCapture," not a dedicated type. TRAINER ⇒ win; BERSERK ⇒ kill; ROAMING ⇒ kill or capture per target. ✗ |
| Success criteria per intent | Not modeled. | Engines "know" when they're done (battle ended, captured, etc.) but there is no explicit "intent satisfied" concept. ✗ |

### Battle decisions

| Design concept | Current code | Alignment |
|----------------|--------------|-----------|
| BattleDecisionMaker = (state, intent) → decision | **Capture:** logic inside `CaptureEngine` (next action: False Swipe, Thunder Wave, ball, switch). **Non-capture:** `BattleDecisionRouter` + `TrainerBattleEngine` (general action, move, switch). | **Split and mixed.** Two decision paths (capture vs non-capture), no single goal-aware decision maker. Capture decisions are embedded in the engine, not a separate domain service. ✗ |
| BattleDecision = FIGHT / SWITCH / CAPTURE / RUN | `CaptureEngine.CaptureAction`, `TrainerBattleEngine.GeneralChoice`; mixins call router or capture engine. | **Present but not unified.** Capture uses CaptureAction; others use GeneralChoice + move/switch. No single BattleDecision type. Partial ✓ |

### Summary

- **Session objects:** **Done** for Capture (`CaptureSession`) and Legendary (`LegendaryRunState`). State is first-class and inspectable; engines are thinner.
- **Engines:** Own tasks + iteration; still implement walk/engage/throw inline (no discrete Action types).
- **Actions:** Only "walk to entity" has a clear executor (Walker). Engage and throw are in-engine logic.
- **Battle / BattleIntent:** No Battle or BattleIntent types; intent is mode + targetForCapture.
- **BattleDecisionMaker:** Split (BattleDecisionRouter + TrainerBattleEngine for non-capture; CaptureEngine internals for capture). Capture decision logic not yet extracted to a domain service.

Remaining to align with design: extract capture domain logic (P0, see §12); optional BattleIntent, single BattleDecisionMaker interface, Action/executor types.

---

## 12. Reassessment (post–session refactor) and P0 critical steps

### What changed recently

- **CaptureSession:** All capture state lives in `CaptureSession`; `CaptureEngine` holds `session`, creates it in `start()`, passes it to `tickWalking(s, client)`, `tickEngaging(s, client)`, and all in-battle/ball logic, and clears it on stop/success/fail. Engine is thinner; state is inspectable via `getCurrentSession()`.
- **LegendaryRunState:** All legendary state lives in `LegendaryRunState`; `AutoSwitchEngine` holds `run`, uses `getOrCreateRun()`, and uses `setState(run, ...)` throughout. State is inspectable via `getCurrentRun()`.

So **refactor 1 (session/context objects)** from §10 is **done** for Capture and Legendary. §11 "Current structure vs design" still applies for Actions, Battle/BattleIntent, and BattleDecisionMaker — and for the fact that capture **decision logic** remains inside `CaptureEngine`, not in a separate domain service.

### P0 critical steps (in order)

1. ~~**Extract capture domain logic (P0)**~~  
   **Done.** `CaptureStrategy.decide()` is now the pure domain service. It uses `BattleSnapshot.activeHasFalseSwipe()` / `activeHasThunderWave()` (move-based, not species-based). `CaptureEngine` builds the snapshot and applies decisions. Switch targets are chosen by move set (Gallade preferred for False Swipe). When no valid option is found, the mod does nothing and logs the reason.

2. **Keep README and design docs in sync**  
   When adding new session types, domain services, or intent types, update §4 (Modules), §10, and §11 so agents and reviewers see the current design.

Optional (not P0): WalkSession for PokemonWalker; explicit BattleIntent type; inject config/logging (interfaces); unified BattleDecision type.

This README is intended for human reviewers to navigate and understand the mod’s structure and behavior without running the game.
