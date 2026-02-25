# Agent Guidelines

## Logging for Debuggability

**Every significant action must be logged** so that behavior can be debugged a posteriori.

- Use `AutoQiqiClient.log("<module>", "<message>")` for in-game / stdout logging
- Use `SessionLogger.get().log*()` for persistent session events (captures, world switches, errors)
- Log state transitions, decisions, command sends, timer updates, and failures
- Include enough context (e.g. world name, target, tick count) to reconstruct what happened from logs alone

## Bug Reports: Always Check Logs First

When the user reports an issue with battle, capture, or any mod behavior, **always check the actual game logs before proposing a fix**.

1. Read `~/Library/Application Support/ModrinthApp/profiles/Cobblemoon1.1.8/auto-qiqi/session-*.log` for the session event log
2. Search `~/Library/Application Support/ModrinthApp/profiles/Cobblemoon1.1.8/logs/latest.log` for detailed stdout logs (grep for relevant timestamps, pokemon names, module prefixes)
3. Trace the **exact sequence of events** from logs — do not assume the cause based on description alone
4. Identify which code path actually executed (look for "Target acquired", "Decide #", mixin logs, scan results, etc.)
5. Only then propose a fix that addresses the **actual root cause** seen in logs, not a hypothetical one
