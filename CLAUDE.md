# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

nxsync is a one-way downloader: it logs into a NetXMS server, walks a source
directory served by each node's agent FILEMGR subagent, and mirrors changed
files into a local destination. It never deletes or uploads — local files are
only (re-)downloaded when missing or when size/mtime differs. See `README.md`
for the full CLI contract, exit codes, and sync semantics.

## Commands

```sh
./gradlew build          # compile + test
./gradlew test           # run all tests
./gradlew shadowJar      # self-contained jar at build/libs/nxsync-<version>-all.jar
```

Run a single test class or method:

```sh
./gradlew test --tests 'org.netxms.sync.SyncLogicTest'
./gradlew test --tests 'org.netxms.sync.SyncLogicTest.decideTable'
```

Kotlin compiles to a Java 17 target (`jvmToolchain(17)`). The Gradle 9.6.1
wrapper runs on the ambient JVM (currently mise-provisioned 17) — no special
`JAVA_HOME` is needed.

## Architecture

The design deliberately splits **pure decision logic** from **session/IO
plumbing** so the former can be unit-tested without a live NetXMS server.

- **`SyncLogic.kt`** — pure, side-effect-free functions and the `Action` enum
  (`DOWNLOAD`/`UPDATE`/`SKIP`). `decide()` is the change-detection rule; the rest
  handle path safety (`resolveTarget` rejects traversal, `isUnderRoot` validates
  the FILEMGR root), name sanitization, and relative-path computation. This file
  has no NetXMS or filesystem dependencies and carries the heaviest test coverage.

- **`SyncEngine.kt`** — the only place that touches `NXCSession` and the disk.
  `sync(node)` lists the agent tree, recursively scans it (bounded by
  `MAX_SCAN_DEPTH` to survive symlink loops), and per file asks `decide()` what
  to do. Downloads are staged to a temp file and atomically renamed
  (`stageAndCommit`), then the local mtime is stamped to the remote's so the next
  run can decide from the directory listing alone — there is no manifest/state
  file. Files land under `<destination>/<objectId>-<sanitizedName>/`.

- **`Main.kt`** — Clikt CLI, argument/option parsing, connect+login, node
  selection (`selectNodes`: a Node syncs itself; any other object contributes its
  Node descendants), and the coroutine fan-out. Nodes sync concurrently over one
  shared session, gated by a `Semaphore(parallel)`; files within a node download
  sequentially. Also owns password precedence (`resolvePassword`) and the
  Clikt-error→exit-code mapping (`exitCodeFor`).

- **`Report.kt`** — `NodeReport` accumulates per-node counts and errors; `ok` is
  simply "no errors". Drives the final summary and the process exit code.

### Error isolation and exit codes

Failures are isolated per node: one node's exception becomes an error on its
`NodeReport` and does not stop the others. The process exit code is a contract
(see README): `2` = nothing ran (usage error, connect/login/object-lookup
failure), `1` = at least one file/node failed, `0` = clean (including a
container with no nodes). Progress and the per-node summary go to stdout; errors
go to stderr.

## Conventions

- Version is single-sourced in `build.gradle.kts` (`netxmsVersion`) and tracks
  the `netxms-client` dependency version; the jar name embeds it.
- Tests are `kotlin-test` on the JUnit Platform, table-driven where the logic is
  a decision function (see `SyncLogicTest.decideTable`).
- When adding logic, prefer putting the testable core in `SyncLogic.kt` as a pure
  function and keeping `SyncEngine`/`Main` as thin callers.
