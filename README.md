# nxsync

One-way sync of agent files from NetXMS nodes into a local directory.

nxsync connects to a NetXMS server, walks a source directory served by the
agent's file manager on one or more nodes, and mirrors changed files into a
local destination. It is a downloader, not a two-way sync: **local files are
never deleted or uploaded**. A file is (re-)downloaded only when it is missing
locally or its size or modification time differs from the agent's copy, so
repeated runs are cheap and safe to schedule.

## Requirements

- Java 17 or newer.
- A NetXMS server (6.2.x) with credentials that can read the target objects.
- On each node, the agent's **FILEMGR** subagent must be configured with a root
  that contains `SOURCE` (see [Source directory](#source-directory)).

## Build

```sh
./gradlew shadowJar
```

This produces a self-contained jar at `build/libs/nxsync-6.2.1-all.jar`.

## Usage

```
nxsync [OPTIONS] SERVER SOURCE DESTINATION
```

| Argument      | Description                                                        |
|---------------|-------------------------------------------------------------------|
| `SERVER`      | NetXMS server address.                                            |
| `SOURCE`      | Directory on the agent, under a FILEMGR root, to sync from.       |
| `DESTINATION` | Local directory to sync into.                                    |

| Option              | Default   | Description                                              |
|---------------------|-----------|----------------------------------------------------------|
| `-o`, `--object`    | `2`       | Root object id: a Node, or a container/subnet whose Node descendants are synced. `2` is the `Entire Network` object. |
| `-u`, `--user`      | `admin`   | Login name.                                              |
| `-p`, `--password`  |           | Password. Prefer `NXSYNC_PASSWORD` for scheduled runs.  |
| `--ask-password`    |           | Prompt for the password interactively (real terminal only). |
| `-v`, `--verbose`   |           | Per-file decisions on stdout; INFO-level client logging. |
| `--parallel`        | `4`       | Number of nodes to sync concurrently (minimum `1`).      |
| `--dry-run`         |           | Report what would happen without downloading anything.   |

### Password precedence

The password is resolved in this order:

1. `-p` / `--password`
2. `NXSYNC_PASSWORD` environment variable
3. Interactive prompt, only if `--ask-password` is given
4. Empty password

### Examples

Ad-hoc sync of `/opt/reports` from a single node (object id `1234`) into `./out`:

```sh
java -jar nxsync-6.2.1-all.jar -o 1234 -p secret \
  netxms.example.com /opt/reports ./out
```

Preview what a container-wide sync would do, without downloading:

```sh
java -jar nxsync-6.2.1-all.jar -o 42 --dry-run -v \
  netxms.example.com /opt/reports ./out
```

Cron entry syncing every node under container `42`, password from the
environment, 8 nodes at a time:

```cron
0 * * * * NXSYNC_PASSWORD='secret' java -jar /opt/nxsync/nxsync-6.2.1-all.jar \
  -o 42 -u sync --parallel 8 netxms.example.com /opt/reports /srv/nxsync 2>>/var/log/nxsync.err
```

## Sync semantics

- **Object selection.** If `--object` points at a Node, that node is synced. If
  it points at a container, subnet, or any other object, all of its Node
  descendants are synced. An object with no nodes is a warning, not an error.
- **Change detection.** For each remote file: missing locally → download; size
  differs → update; modification time differs (compared at one-second
  granularity) → update; otherwise skip. After a download the local file's
  modification time is stamped to match the agent's, so the next run can decide
  from the directory listing alone — there is no manifest or state file.
- **Layout.** Each node's files land under
  `<DESTINATION>/<objectId>-<sanitized-node-name>/`, preserving the directory
  structure below `SOURCE`.
- **Safety.** Downloads are staged to a temporary file and atomically renamed
  into place, so an interrupted run never leaves a truncated file that looks
  in-sync. Remote paths that would escape the destination directory are rejected
  and reported as errors. Local files are never deleted.
- **Concurrency.** Nodes sync concurrently (bounded by `--parallel`) over one
  shared session; files within a node are downloaded sequentially. A failure on
  one node isolates that node and does not stop the others.

## Source directory

`SOURCE` must be a directory that lives under a root exposed by the agent's
FILEMGR subagent. If it is not, nxsync reports the node as failed with a hint to
check the `FILEMGR` section of the agent configuration. Configure a root in
`nxagentd.conf`, for example:

```
[FILEMGR]
RootFolder = /opt/reports
```

## Exit codes

| Code | Meaning                                                                 |
|------|-------------------------------------------------------------------------|
| `0`  | Clean run (including a container with no nodes).                        |
| `1`  | At least one file or node failed; other nodes may have succeeded.       |
| `2`  | Usage error, or connect / login / object-lookup failure — nothing ran. |

Progress and the per-node summary go to stdout; errors go to stderr.
