# Agent Guidelines

## Do Not Build

**Agents must never run the build** (e.g. `./gradlew build` or any Gradle build command). Building is the user’s responsibility. Do not suggest or execute build steps to “verify” changes.

## Design-Driven Approach (DDD)

**The codebase should follow a clear design-driven approach** so that structure and intent are easy for both humans and agents to understand and change.

- Prefer **feature-oriented packages** as bounded contexts (`battle/`, `legendary/`, `npc/`, etc.); avoid mixing unrelated concerns in one package.
- Use **ubiquitous language**: name classes and methods with domain terms (Capture, Battle, Phase, WorldTracker, etc.) consistently.
- Keep **domain logic** (e.g. type effectiveness, timer semantics) separable from orchestration and infrastructure where practical; isolate integration with Cobblemon/Minecraft (mixins, reflection) at the boundary.
- When adding or refactoring code, consider whether it strengthens or weakens bounded contexts, naming clarity, and dependency direction.

**In summaries:** When your changes touch architecture, new modules, or refactors, **include a short note on how the change is DDD-aligned or not** (e.g. “New class lives in `battle/` and keeps capture logic in one bounded context” or “Moved X to `common/` for reuse; weakens legendary context”). If the change is purely local (e.g. bug fix in one method with no structural impact), you can omit the DDD note.

## Logging for Debuggability

**The mod does not write log files** (chat-only). Significant actions should still be reported via chat so behavior can be debugged.

- Use **`AutoQiqiClient.log("<module>", "<message>")`** for all output — messages appear in **in-game chat** only (no file or stdout).
- `SessionLogger.get().log*()` is a **no-op** (no session files, no session-stats).
- Log state transitions, decisions, command sends, timer updates, and failures via `AutoQiqiClient.log`.
- Include enough context (e.g. world name, target, tick count) so the user can reconstruct what happened from chat.

## Bug Reports

When the user reports an issue: **there are no log files**. Rely on the user's description and what they saw in chat; trace code paths and propose fixes from steps-to-reproduce.

## Versioning

The mod version lives in **`auto-qiqi/gradle.properties`** (`mod_version=X.Y.Z`). This is the single source of truth — `build.gradle` reads it, and a build-time task generates `BuildConstants.VERSION` so Java code can reference it at runtime.

**When an agent ships a feature, bug fix, or behavioral change**, it must bump the version in `gradle.properties`:

- **Patch** (Z): bug fixes, logging improvements, config tweaks
- **Minor** (Y): new features, new commands, new config options, behavioral changes
- **Major** (X): breaking changes, large refactors, architecture overhauls

The user can check the running version in-game with `/pk version`.

## Documentation: Keep README Up to Date

**The [auto-qiqi/README.md](auto-qiqi/README.md) should always be up to date.** When adding features, changing behavior, adding config options, or refactoring modules, update the README to reflect the current state of the mod (commands, keybinds, config, modules, build/run instructions). Do not leave the README stale after code changes.
