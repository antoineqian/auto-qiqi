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

**Every significant action must be logged** so that behavior can be debugged a posteriori.

- Use `AutoQiqiClient.log("<module>", "<message>")` for in-game / stdout logging
- Use `SessionLogger.get().log*()` for persistent session events (captures, world switches, errors)
- Log state transitions, decisions, command sends, timer updates, and failures
- Include enough context (e.g. world name, target, tick count) to reconstruct what happened from logs alone

## Bug Reports: Always Check Logs First

When the user reports an issue with battle, capture, or any mod behavior, **always check the actual game logs before proposing a fix**.

### Discovering log locations

Log paths are **not fixed**: they depend on the Minecraft launcher, profile, and install. Use the paths documented in [.cursor/rules/project-context.mdc](.cursor/rules/project-context.mdc) as the **current reference**. The mod resolves paths at runtime via `FabricLoader.getInstance().getGameDir()` (session logs under `auto-qiqi/`, game stdout under `logs/latest.log`).

1. **Try the documented paths first** — read session log and `logs/latest.log` from the paths in project-context.
2. **If logs are missing, not recent, or don’t match what the user says happened** (e.g. no recent entries, different profile, different launcher):
   - Treat the documented paths as wrong or outdated.
   - **Discover the real location**: e.g. search for `auto-qiqi/session-*.log` and `logs/latest.log` under common roots (e.g. `~/Library/Application Support/ModrinthApp/profiles/`, or other launcher data dirs). Use the profile/instance whose logs are most recent and consistent with the user’s report.
   - **Update the documentation**: once you’ve found the correct instance/log paths, update [.cursor/rules/project-context.mdc](.cursor/rules/project-context.mdc) (and any log path mentions in this file) so future runs use the correct paths.
3. Trace the **exact sequence of events** from the logs you found — do not assume the cause from the user’s description alone.
4. Identify which code path actually executed (look for "Target acquired", "Decide #", mixin logs, scan results, etc.).
5. Only then propose a fix that addresses the **actual root cause** seen in logs, not a hypothetical one.

## Versioning

The mod version lives in **`auto-qiqi/gradle.properties`** (`mod_version=X.Y.Z`). This is the single source of truth — `build.gradle` reads it, and a build-time task generates `BuildConstants.VERSION` so Java code can reference it at runtime.

**When an agent ships a feature, bug fix, or behavioral change**, it must bump the version in `gradle.properties`:

- **Patch** (Z): bug fixes, logging improvements, config tweaks
- **Minor** (Y): new features, new commands, new config options, behavioral changes
- **Major** (X): breaking changes, large refactors, architecture overhauls

The user can check the running version in-game with `/pk version`.

## Documentation: Keep README Up to Date

**The [auto-qiqi/README.md](auto-qiqi/README.md) should always be up to date.** When adding features, changing behavior, adding config options, or refactoring modules, update the README to reflect the current state of the mod (commands, keybinds, config, modules, build/run instructions). Do not leave the README stale after code changes.
