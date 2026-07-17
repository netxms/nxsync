# nxsync: true sync tool with modernized toolchain

## Overview
- Turn nxsync from a one-shot downloader into a real one-way sync tool: files are re-downloaded when size or modification time differs from the agent copy; local files are never deleted.
- Fix known bugs: broken `--ask-password` (`CharArray.toString()`), always-zero exit code, sanitize regex allowing backslashes, whole-file memory buffering, path traversal from agent file names.
- Modernize toolchain: Kotlin 2.2.0, Clikt 5.0.3 (replacing abandoned kotlin-argparser), `com.gradleup.shadow` 8.3.9, netxms-client 5.2.0, kotlinx-coroutines 1.10.2.
- Scheduled-run friendly: `NXSYNC_PASSWORD` env var, exit codes 0/1/2, errors to stderr, `--dry-run`, parallel per-node syncing (`--parallel`, default 4).

## Context (from discovery)
- Single-module Kotlin/Gradle project; all logic in `src/main/kotlin/org/netxms/sync/App.kt` (~140 lines), to be replaced.
- netxms-client API verified against local NetXMS master sources:
  - `NXCSession.listAgentFiles(AgentFile, String, long): List<AgentFile>`
  - `NXCSession.downloadFileFromAgent(long, String, long, boolean, ProgressListener): AgentFileData` (`.getFile(): File` is a local temp file)
  - `AgentFile.getSize(): Long`, `getModificationTime(): Date`, `getFilePath()`, `getFullName()`, `isDirectory`
  - `AbstractObject.getAllChildren(int): Set<AbstractObject>`
  - Concurrency: file transfers are keyed by per-request message ID (`receivedFiles` map, `waitForFile(msg.getMessageId(), …)`), so concurrent `downloadFileFromAgent`/`listAgentFiles` calls on one shared `NXCSession` are supported — `--parallel` design is sound.
- Latest published versions checked on Maven Central: netxms-client 5.2.0, Clikt 5.0.3, Kotlin 2.2.0, coroutines 1.10.2, shadow 8.3.9.
- slf4j-simple stays as the SLF4J binding (netxms-client logs through slf4j); default level warn, info with `-v`.

## Development Approach
- **testing approach**: Regular (code first, then tests in the same task)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - pure logic (decision function, path safety, sanitization, password precedence, exit-code mapping) gets kotlin-test unit tests
  - session-facing plumbing (`SyncEngine` scan/download) stays thin and is verified manually against a real server — no NXCSession mocking (deliberate choice)
- **CRITICAL: all tests must pass before starting next task**
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change

## Testing Strategy
- **unit tests**: table tests for `decide()` (missing/size/mtime → DOWNLOAD/UPDATE/SKIP), path-escape check, node-name sanitization, relative-path computation, password resolution precedence, CliktError→exit-code mapping, node-set selection
- **e2e tests**: none (no UI); end-to-end verification is a manual run against a NetXMS server (Post-Completion)

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview
Stateless one-way sync (brainstormed Option A):
- After each download the local file's mtime is stamped with the agent file's mtime (`Files.setLastModifiedTime`), so the next run detects changes by comparing size and mtime from the directory listing alone — no manifest, no extra round-trips.
- One shared `NXCSession` (concurrent transfers verified safe, see Context); nodes sync concurrently via coroutines bounded by `Semaphore(--parallel)`; files within a node sequential.
- Three failure tiers: file errors recorded in the node report, node errors isolate that node, run errors (args/connect/login) exit immediately with code 2.

## Technical Details
- **CLI** (Clikt): positionals `SERVER SOURCE DESTINATION` (all required; `SOURCE` must be a directory on the agent — documented in README and help text); options `-o/--object` (Long, default 2), `-u/--user` (default admin), `-p/--password` (envvar `NXSYNC_PASSWORD`), `--ask-password` (hidden interactive input via `System.console().readPassword`, `String(chars)` fix), `-v/--verbose`, `--parallel` (Int ≥ 1, default 4), `--dry-run`.
- **Exit codes**: 0 clean (including "container has no nodes" — warned, not an error), 1 any file/node failure, 2 usage/connect/login/object-not-found. Clikt usage errors mapped to 2 by catching `CliktError` in `main` (help → 0). Exit code is computed first, `session.disconnect()` runs, then a single `exitProcess` at top level (never inside `finally`-guarded code — `exitProcess` skips `finally`).
- **Session bring-up order**: slf4j `org.slf4j.simpleLogger.defaultLogLevel` system property is set as the very first statement in `run()` before any netxms-client class loads; then connect → login → `session.syncObjects()` (required — `findObjectById` reads the object cache) → object lookup.
- **Change detection** (pure function): local missing → DOWNLOAD; size differs → UPDATE; mtime differs (compared at second granularity to tolerate coarse filesystem timestamps) → UPDATE; else SKIP.
- **Download**: `downloadFileFromAgent` temp file is `Files.move`d (copy fallback) to `<target>.nxsync-tmp`, then atomically renamed to target; mtime stamped after rename. A crash never leaves a truncated file that looks in-sync.
- **Path safety**: relative path = `filePath.removePrefix(source)`, backslashes normalized to `/`, leading separators stripped; resolved against the node dir and `normalize()`d; must `startsWith` the node dir or the file is reported as an error and skipped.
- **Node dirs**: `<destination>/<objectId>-<sanitized-name>/` with regex `[^a-zA-Z0-9._-]` → `_`, trimmed; blank result falls back to `node`.
- **Pre-check**: top-level `listAgentFiles(null, "/", nodeId)` roots checked against `SOURCE`; miss → per-node error with the FILEMGR configuration hint.
- **Output**: node-name-prefixed progress lines on stdout, errors on stderr, per-node summary table at the end; `-v` adds per-file decisions; `--dry-run` runs the same pipeline printing "would download/update".

## What Goes Where
- **Implementation Steps**: build file, source files, tests, docs — all in this repo
- **Post-Completion**: manual verification against a live NetXMS server

## Implementation Steps

### Task 1: Modernize build configuration

**Files:**
- Modify: `build.gradle.kts`

- [x] switch to Kotlin 2.2.0 and `com.gradleup.shadow` 8.3.9 plugins
- [x] dependencies: netxms-client 5.2.0, clikt 5.0.3, kotlinx-coroutines-core 1.10.2, slf4j-simple 2.0.17, kotlin-test; drop kotlin-argparser
- [x] set `Main-Class` to `org.netxms.sync.MainKt`; bump project version to 5.2.0
- [x] verify configuration resolves: `./gradlew dependencies --configuration runtimeClasspath -q` (resolution only — `App.kt` won't compile until it is deleted in Task 2, which is fine because this task compiles nothing) — passes with JAVA_HOME=openjdk@21 (see [[gradle-jdk21-daemon]])

### Task 2: Pure sync logic and reports

**Files:**
- Delete: `src/main/kotlin/org/netxms/sync/App.kt` (uses removed kotlin-argparser; must go before anything compiles)
- Create: `src/main/kotlin/org/netxms/sync/SyncLogic.kt`
- Create: `src/main/kotlin/org/netxms/sync/Report.kt`
- Create: `src/test/kotlin/org/netxms/sync/SyncLogicTest.kt`

- [x] delete `App.kt`
- [x] `Report.kt`: `NodeReport` with downloaded/updated/skipped counters, error list, `ok` flag
- [x] `SyncLogic.kt` pure functions: `decide(localExists, localSize, localMtimeMs, remoteSize, remoteMtimeMs): Action`, `sanitizeNodeName(name)` (blank → `node` fallback), `relativePath(filePath, source)`, `resolveTarget(nodeDir, relative): Path?` (null on escape)
- [x] write table tests for `decide()` (all outcomes, mtime second-granularity tolerance)
- [x] write tests for `sanitizeNodeName` (backslash, unicode, all-underscore → fallback), `relativePath` (windows paths, leading separators), `resolveTarget` (`..` escape → null)
- [x] run tests: `./gradlew test` — must pass before task 3

### Task 3: SyncEngine orchestration

**Files:**
- Create: `src/main/kotlin/org/netxms/sync/SyncEngine.kt`

- [x] FILEMGR-root pre-check with configuration hint on miss
- [x] recursive scan keeping `AgentFile` metadata (size/mtime from listing)
- [x] per-file pipeline: decision → temp-file download (`Files.move`, copy fallback) → atomic rename → mtime stamp; dry-run prints decisions without downloading
- [x] per-file/per-node error capture into `NodeReport`; verbose per-file output, node-name-prefixed lines
- [x] session-facing plumbing — covered by existing unit tests of the pure functions it delegates to; no new tests by design (manual server verification in Post-Completion)
- [x] run tests: `./gradlew test` — must pass before task 4

### Task 4: CLI entry point

**Files:**
- Create: `src/main/kotlin/org/netxms/sync/Main.kt`
- Create: `src/test/kotlin/org/netxms/sync/MainLogicTest.kt`

- [x] Clikt command with arguments/options per Technical Details; slf4j level property set first
- [x] pure `resolvePassword(option, envValue, askFlag, promptFn)` implementing `-p` → `NXSYNC_PASSWORD` → prompt precedence
- [x] pure `exitCodeFor(CliktError)` mapping (help → 0, usage → 2); pure `selectNodes(AbstractObject)` (Node direct, container → `OBJECT_NODE` children, empty → warning + empty list)
- [x] connect → login → `session.syncObjects()` → object lookup (exit 2 on failure); coroutine per node bounded by `Semaphore(parallel)` on `Dispatchers.IO`
- [x] end-of-run summary table; compute exit code (empty node set → 0 with warning), disconnect, then single `exitProcess`
- [x] write tests for `resolvePassword` precedence, `exitCodeFor`, `selectNodes` (node/container/empty)
- [x] run tests: `./gradlew test` — must pass before task 5

### Task 5: Verify acceptance criteria
- [x] all Overview bugs fixed: hidden password input works, exit codes per contract, sanitize regex correct, no whole-file buffering, path escapes rejected
- [x] changed-file re-download logic present (size/mtime), never deletes local files
- [x] `--dry-run` and `--parallel` wired through
- [x] run full test suite: `./gradlew build` (compiles + tests) — BUILD SUCCESSFUL
- [x] build fat jar: `./gradlew shadowJar`; smoke-test `java -jar build/libs/nxsync-*-all.jar --help` exits 0 and prints usage — verified (exit 0)

### Task 6: [Final] Update documentation
- [x] write `README.md`: purpose, usage examples (ad-hoc and cron with `NXSYNC_PASSWORD`), exit codes, sync semantics, `SOURCE` must be a directory under a FILEMGR root
- [x] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems*

**Manual verification:**
- run against a real NetXMS server: initial sync populates destination, second run skips everything, touching a file on the agent triggers re-download, unreachable node exits 1 while others sync
- verify `--ask-password` hidden input in a real terminal (not an IDE console)
- verify parallel sync against several nodes (shared-session concurrent transfers)

**External system updates:**
- update any cron/systemd-timer entries to pass the password via `NXSYNC_PASSWORD` instead of `-p`
