# SyncArenas

Arena regeneration for big Folia / **CanvasMC** networks, built so that restoring hundreds of
thousands of broken blocks is not something your region threads ever feel.

The idea is simple: an arena's pristine state is captured once into a seekable binary snapshot with
one entry per **16x16x16 section**. While players fight, block events do nothing but flip a bit for
the section they happened in. A reset then touches only those sections - if the arena is 5000
sections and two of them were damaged, two sections are read, decompressed and rewritten, and the
other 4998 are never looked at.

## Supported servers

One jar covers every version from **1.21.11** up, because it is compiled against the 1.21.11 API and
declares `api-version: 1.21.11` - a server refuses a plugin whose api-version is *newer* than its
own, never older:

| Server | Status |
| --- | --- |
| CanvasMC / Folia 1.21.11, 26.1.2, 26.2, 26.3 | supported, region threading in use |
| Paper 1.21.11 and newer | supported, one region instead of many |

The jar is Java 21 bytecode, so it loads on a 1.21.11 server (Java 21) and on 26.x (Java 25) alike.
`folia-supported: true` is declared; everything the plugin calls - the region, async and entity
schedulers included - has existed in the Paper API since 1.20.1 and is unchanged through 26.3, so
no version specific code paths are needed.

## Commands

All of them need the `syncarenas.admin` permission (`op` by default).

| Command | What it does |
| --- | --- |
| `/arena wand` | Gives the selection wand. Left click = first corner, right click = second corner. |
| `/arena create <name>` | Snapshots the current selection into `<name>.sa` and starts tracking it. |
| `/arena reset <name>` | Restores every section that was touched since the last reset. |
| `/arena reset <name> full` | Restores every section of the arena, dirty or not. Safety net, not the normal path. |
| `/arena interval <name> <seconds\|off>` | Resets the arena automatically every N seconds (skipped when nothing is dirty). Saved in the arena's `.json`; `off` or `0` disables. |
| `/arena list` | Lists arenas with size, section count, dirty sections and current state. |
| `/arena delete <name>` | Unregisters the arena and deletes its `.json` and `.sa` files. |

Both `create` and `reset` report what they did, including how much region thread time the whole
operation actually cost:

```
[SyncArenas] Reset CrystalPvP: 37 sections in 14 chunks, 24,113 blocks rewritten of 151,552 checked
[SyncArenas] Took 212.4ms wall clock, 38.7ms of region thread time in total.
```

## How it stays off the region threads

| Step | Where it runs |
| --- | --- |
| Reading/writing `.json` and `.sa` files | async scheduler |
| Deflate / inflate, palette building, bit packing | async scheduler |
| `Bukkit.createBlockData` palette resolution | async scheduler (immutable block registry) |
| `Chunk#getChunkSnapshot` during capture | region thread that owns the chunk, one cheap call |
| The block writes themselves | region thread that owns the chunk, in budgeted slices |

A reset is planned as one work item per chunk. Each item is handed to
`RegionScheduler#execute` for *that* chunk, so a reset of a large arena is spread across every
region thread that owns a piece of it rather than running on one thread. Inside a slice the plugin
writes blocks until `restore.region-budget-micros` (400us by default, under 1% of a 50ms tick) is
spent, remembers its cursor, and resumes on that region's next tick. Nothing is ever restored
"all at once", and no tick pays more than its budget no matter how much damage there is.

Three more things keep the cost down:

* **`skip-unchanged-blocks`** - the snapshot is compared against the world before writing. A read
  is far cheaper than a write (no light update, no block change packet), and for palette entries
  with no block properties the comparison is a single `Block#getType()` call that allocates
  nothing. In a section where ten blocks were broken, only those ten are written.
* **`applyPhysics = false`** - restored blocks do not trigger neighbour updates.
* **Chunk tickets** - a chunk being restored is held loaded for the duration, so a multi-tick
  restore cannot be interrupted by an unload.

The tracking side is equally deliberate: the listeners are the only code that runs on every block
change of every region thread, so they do one reference comparison per loaded world that has
arenas, one box test against the union of that world's arenas, and only then add a packed `long`
to a concurrent set. They allocate nothing per event, never block, and never cancel or modify an
event (everything is `MONITOR` + `ignoreCancelled`).

### What marks a section dirty

Block break/place (including multi block placements), bucket empty/fill, block and entity
explosions, TNT priming, burning, igniting, fading, forming, spreading, growing, leaf decay,
structure growth, sponge absorption, cauldron and fluid level changes, moisture changes, and
entity caused block changes (endermen, falling blocks, ravagers, ...).

Fluid flow and piston movement are tracked too, but each sits in its own listener behind
`tracking.track-fluid-flow` and `tracking.track-pistons`. Setting either to `false` means that
listener is never registered, so the plugin does not appear in those events' handler lists at all -
worth doing for fluid flow in particular, which is one of the most frequent block changes in the
game. Both need a server restart to apply.

Unrestored sections are never lost: they are written into the arena's `.json` (every
`tracking.autosave-minutes` and on shutdown), and if a reset cannot finish a chunk - world
unloaded, server stopping - those sections go straight back into the dirty set for the next reset.

## Files

```
plugins/SyncArenas/
├── config.yml
├── arenas/
│   └── CrystalPvP.json      # metadata + the dirty section set
└── snapshots/
    └── CrystalPvP.sa        # the pristine blocks
```

### `CrystalPvP.json`

```json
{
  "name": "CrystalPvP",
  "world": "arena_world",
  "worldId": "8f1c...",
  "min": { "x": -64, "y": 60, "z": -64 },
  "max": { "x": 63, "y": 123, "z": 63 },
  "snapshot": "CrystalPvP.sa",
  "snapshotSections": 256,
  "snapshotBytes": 1483264,
  "blocks": 1048576,
  "createdAt": 1760000000000,
  "lastReset": 1760000600000,
  "dirtySections": [ 70368744177668, 70368744177669 ]
}
```

### `CrystalPvP.sa`

A custom format, because none of the usual ones let you read a single section without paging in
the whole region. Every section is deflated on its own and reachable through an index, so a reset
reads exactly the sections it needs:

```
i32  magic "SYNA"
i32  format version (1)
i32  flags (reserved)
utf  world name
i32  minX, minY, minZ, maxX, maxY, maxZ
i32  section count
--- index: one 24 byte entry per section, sorted by key ---
i64  section key          (22 bits x | 22 bits z | 20 bits y, packed)
i64  absolute file offset of the payload
i32  compressed length
i32  uncompressed length
--- payloads, each deflated on its own ---
u16  palette size
utf  palette entries      (BlockData#getAsString())
u8   bits per entry       (0 when the whole section is one state)
i32  packed length in longs
i64  packed palette indices
```

Indices are packed like a vanilla paletted container - a fixed number of entries per `long`, never
straddling a word boundary - so reading one is a shift and a mask. A section that is entirely air
or entirely one block stores a single palette entry and no index array at all, which is what most
of a large arena looks like.

## Configuration

See [`src/main/resources/config.yml`](src/main/resources/config.yml) for the commented defaults.
The knobs that matter:

| Key | Default | Meaning |
| --- | --- | --- |
| `restore.region-budget-micros` | `400` | Wall clock budget of one region thread restore slice. |
| `restore.blocks-per-budget-check` | `256` | Block writes between two clock reads. |
| `restore.max-concurrent-chunks` | `8` | Chunks restored at the same time; this is what spreads a reset over region threads. |
| `restore.skip-unchanged-blocks` | `true` | Compare before writing. Keep it on. |
| `restore.hold-chunk-tickets` | `true` | Keep a chunk loaded while it is being restored. |
| `capture.max-concurrent-chunks` | `4` | Chunks snapshotted at once during `/arena create`. |
| `capture.compression-level` | `4` | Deflate level for section payloads (0-9). |
| `tracking.autosave-minutes` | `5` | How often the dirty sets are persisted. |
| `tracking.track-fluid-flow` | `true` | Treat fluid spread as damage; `false` registers no handler for it. |
| `tracking.track-pistons` | `true` | Treat piston movement as damage; `false` registers no handler for it. |
| `limits.max-arena-blocks` | `0` | Refuse larger arenas (0 = no limit). |
| `wand.material` | `GOLDEN_AXE` | The selection item. |

Want faster resets and don't mind a bigger footprint on the region threads? Raise
`max-concurrent-chunks` first, then `region-budget-micros`. Want resets to be completely invisible?
Lower the budget - a reset simply takes more ticks.

## Limitations

* Only block states are restored. Block entity contents (chest inventories, sign text, skull
  textures) are not part of the snapshot.
* Entities are not touched: dropped items, end crystals and the like are left to your arena logic.
* Blocks are restored without physics, which is what you want for an arena but means a restored
  state is not re-validated against its neighbours.
* `/arena create` assumes the selection is currently pristine - it captures what is there.

## Building

```bash
mvn clean package      # -> target/SyncArenas-1.0.3.jar
mvn test               # format and geometry tests, no server needed
```

Releases are built by [`.github/workflows/release.yml`](.github/workflows/release.yml): it resolves
the newest `paper-api` build matching `plugin.yml`'s `api-version`, runs `mvn verify` so the tests
gate the release, and publishes the jar. Push a `v*` tag or run the workflow manually.

Paper is not published to Maven Central, so the build resolves `paper-api` from
`https://repo.papermc.io/repository/maven-public/`. The pom pins the **oldest** supported API
(`1.21.11-R0.1-SNAPSHOT`) on purpose: that is what makes a single jar load on everything newer. A
JDK 21 is enough to build it.

Compiling against a newer API also works, but the resulting jar only loads on servers at least that
new - and since Minecraft 26.3 the artifact version is `<mcVersion>.build.<number>-<channel>`, which
needs a JDK 25:

```bash
mvn -Dpaper.api.version=26.3.build.199-stable -Dmaven.compiler.release=25 clean package
```

Building against `io.canvasmc.canvas:canvas-api` from `https://maven.canvasmc.io/public/` works as
well - the plugin only uses API that Canvas inherits from Paper.
