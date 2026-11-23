# nxsync review profile

## What it is
- Single-purpose Kotlin CLI (fat jar, Java 17) run by an operator or from cron.
- Logs into a NetXMS server via `netxms-client`, walks a directory served by each
  node's agent FILEMGR subagent, and downloads new/changed files locally.
- One-way only: never uploads, never deletes local files. No manifest/state file —
  local mtime stamped to the remote's is the only sync state.

## What a real failure looks like
- A remote path that writes outside `<destination>/<objectId>-<name>/` (traversal).
- A local file deleted, truncated, or left half-written so it looks in-sync.
- A changed remote file skipped, or an unchanged one re-downloaded every run
  (mtime/size decision or mtime stamping wrong).
- Exit code contract broken: `2` = nothing ran (usage, connect, login, object lookup),
  `1` = some file/node failed, `0` = clean. Cron and scripts branch on it.
- One node's failure stopping or corrupting other nodes' syncs.
- Errors to stdout instead of stderr, or a credential leaked into output.

## Blast radius
- Local destination tree of whoever runs it; read-only against the server/agents.
- Small user base, internal/ops tool. Not a library — no API consumers.

## Reporting bar
- Report: anything above, wrong behaviour on realistic inputs, CLI contract drift
  between code and README.
- Do not report: style preferences, hypothetical states no caller reaches, missing
  defensive checks for impossible inputs, abstractions for a second use that does
  not exist.

## Deliberate conventions
- Pure decision logic lives in `SyncLogic.kt` (no NetXMS/filesystem imports) and is
  unit-tested table-driven; `SyncEngine`/`Main` are thin callers with no tests of
  their own. Don't flag missing tests for session/IO plumbing.
- Comments only where something is unexpected; absence of KDoc is not a finding.
- Version tracks the `netxms-client` version (`netxmsVersion` in `build.gradle.kts`).
- Nodes sync concurrently over one shared `NXCSession`; files per node sequentially.
