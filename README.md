# DraftSmith

A **lightweight, server-side building editor** for **Fabric / Minecraft 26.1.2, 26.2, 26.3 & 1.21.1**, built for
**Java + Bedrock crossplay** (Geyser/Floodgate). Nothing is required on the client — Bedrock players
use every screen, brush and command through Geyser. Drop the jar on the server and your ops have a
full GUI-driven build kit on any world.

The current release line is **DraftSmith 1.0.1**. It adds commit/undo permission rechecks,
block-entity data protection and preflight limits for large edits; see [CHANGELOG.md](CHANGELOG.md).

DraftSmith is server-side in behavior and does not need to be installed by connecting clients. It also
initializes on integrated single-player servers so local worlds can be used for testing.

DraftSmith is not a WorldEdit replacement. It's the small, handy toolkit for people who *build*:
paint brushes, parametric shapes, measuring tools, a clipboard and undo — all driven by chest GUIs
that work everywhere, with a hard per-block permission jail underneath.

## Features

- **The editor hub** — `/draft` opens a chest-GUI home screen: corners, copy/cut/paste, undo/redo,
  fill/walls, stack/move, and doors into the Shapes, Brushes and Measure screens.
- **Paint brushes** — `/draft brush` hands you a brush that paints strokes of blocks wherever you aim
  (30 blocks). Sneak + right-click opens its settings; right-click paints. Settings live **on the brush
  itself**, so every brush is its own preset — name them at an anvil and swap like a painter.
- **Eleven brush types.** **Splatter**, **Round**, **Overlay**, **Spray**, **Wall**, **Gradient**
  (palette in order — rings on the ground, bands on walls), **Blend** (re-mixes what's already there
  until seams disappear), **Raise / Lower / Smooth** (terraformers), and **Erase**.
- **Brush dials.** Size (radius up to 15), density, fade (edges thin out), Surface vs Ball mode, and a
  mask ("paint over only X") so a stroke only ever replaces the block you tell it to.
- **9-slot weighted palette.** Load up to nine blocks; duplicates make a block proportionally more
  common — 3× grass + 1× moss paints a mostly-grass mix.
- **9-slot protected list.** The mask in reverse: load blocks the brush must **never paint over** —
  everything else repaints as normal. Re-texture a mixed floor without eating the carpet, terraform
  around a path without touching it. Stored on the brush, works with every brush type including
  Erase and the terraformers.
- **Placement that respects shape.** Painted half-blocks (slabs, carpets, plates…) rest *on top of*
  the ground while full blocks replace the surface — decided by collision shape, so modded blocks work
  too. Buttons and levers lie flat. Splatter/spray are capped at your aim height and only land on open
  surfaces, so strokes never stack pillars upward.
- **Shapes** — circle, square, sphere, cylinder, pyramid and line, filled or hollow, with height,
  thickness, repeat and spacing dials. Shapes build where you **aim** (or on the self-cleaning gold
  center marker); even sizes get a true 2×2 center.
- **Measuring tools** — a live selection-size readout, find-center, and the yellow/black **measuring
  tape** with numbered signs (up to 4 tapes at once). Tapes restore exactly what they covered.
- **Clipboard** — copy/cut, then paste lands where you aim. Stack and move follow your facing.
- **Random texture mode** — every edit *and every hand-placed block* rolls from the block items in
  your hotbar. Duplicate slots weight the mix. Lay a varied path without ever scrolling.
- **Undo/redo** — 10 edits deep, per player; every stroke or build is one undo entry, capped at
  65,536 blocks per edit. Large selections and shapes are rejected before allocation, and history
  rechecks current access before it writes.
- **Data-safe editing** — generic edits never overwrite containers or signs without their NBT data;
  those blocks must be placed by hand. All shared write paths recheck the active `EditAccess` provider
  immediately before committing.
- **Particle previews** — a ring showing the brush's exact radius while you aim, and a green outline
  around your wand selection.

## Install

For a dedicated server, place these in the server's `mods/` folder:

- `draftsmith-<version>.jar`
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [sgui](https://github.com/Patbox/sgui) `1.6.1+1.21.1`

## Permissions — ship safe

On a survival server an unrestricted editor is a grief cannon, so DraftSmith is **ops-only by
default**: ops (and the single-player host) get everything, everyone else gets nothing — the `/draft`
command doesn't even appear for them.

`config/draftsmith.properties`:

| Key | Default | Meaning |
| --- | --- | --- |
| `editors` | *(blank)* | Comma-separated player names allowed to use the editor without being op |
| `worlds` | *(blank)* | Comma-separated dimension ids the editor works in (blank = all worlds) |

`/draft reload` applies changes live (players see command changes immediately).

### Permission nodes (LuckPerms and friends)

With a permissions mod installed (anything implementing
[fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) — LuckPerms, PlayerRoles…),
DraftSmith checks **per-tool nodes**, so tiered servers can grant tools per rank. Nodes a
permissions mod doesn't define fall back to the ops + `editors` behavior above — installing
LuckPerms changes nothing until you actually set a node.

| Node | Grants |
| --- | --- |
| `draftsmith.use` | The gate: see `/draft`, open the hub, set corners, undo/redo |
| `draftsmith.wand` | The selection wand |
| `draftsmith.edit` | Fill/replace/walls + clipboard (copy/cut/paste/stack/move) |
| `draftsmith.shapes` | Shapes screen and shape commands |
| `draftsmith.brush` | Paint brushes — getting one, painting, the config screen |
| `draftsmith.measure` | Measuring tape and find-center |
| `draftsmith.admin` | `/draft reload` + unclamped editing |

Example tiers: give **Member** `draftsmith.use` + `draftsmith.measure`, **Builder** adds
`draftsmith.wand` + `draftsmith.edit`, **Artist** adds `draftsmith.shapes` + `draftsmith.brush`,
staff get `draftsmith.*`. Hub buttons for tools a player can't use simply don't appear, and a
brush or wand in the wrong hands does nothing.

## Commands

Everything also has a GUI button — commands and screens do the same things.

| Command | What it does |
| --- | --- |
| `/draft` | Open the editor hub |
| `/draft help` | List every command in chat (click one to fill it in) |
| `/draft wand` | Get the selection wand (right-click corners 1/2 alternately) |
| `/draft brush` | Get a paint brush |
| `/draft pos1` / `pos2` | Set a corner where you stand |
| `/draft set <block>` | Fill the selection |
| `/draft replace <from> <to>` | Replace only matching blocks |
| `/draft walls <block>` | Perimeter walls around the selection |
| `/draft copy` / `cut` / `paste` | Clipboard (paste lands where you aim) |
| `/draft stack <n>` / `move <n>` | Repeat / shift the selection along your facing |
| `/draft sphere` / `hsphere <block> <r>` | Sphere / hollow sphere at your feet |
| `/draft cyl <block> <r> [h]` | Cylinder |
| `/draft disc` / `ring <block> <size> [h]` | Flat disc / ring |
| `/draft line <block> [thickness]` | Corner-to-corner 3D line |
| `/draft center` | Gold-mark the middle of the corner1→corner2 line |
| `/draft tape` / `tape clear` | Numbered measuring tape between corners |
| `/draft undo` / `redo` | Step edits back / forward |
| `/draft edit` / `measure` | Open the hub / measuring screen |
| `/draft reload` | (ops) Reload the config |

## For mod developers — the `EditAccess` seam

Every block DraftSmith writes is checked through one interface. A host mod can install its own
provider to jail edits to its rules and hang the same commands under its own root:

```java
// fabric.mod.json: "depends": { "draftsmith": "*" } — you initialize after DraftSmith
DraftSmithApi.setAccess(new EditAccess() {
    public boolean isActiveDimension(Level level) { ... }   // where the editor runs
    public boolean isAdmin(ServerPlayer p) { ... }          // unclamped editing
    public boolean canEdit(ServerPlayer p, boolean admin, int x, int y, int z) { ... } // the jail
    public Ground ground(ServerLevel level, int x, int z) { ... } // what Erase restores (optional)
    // plus cosmetic overrides: messagePrefix(), notHereMessage(), editableAreaName()
    // (editor chat always references its own /draft commands, which exist on your server too)
});
```

`DraftCommands.attach(rootBuilder, buildContext)` chains the editor subcommands onto your own
command root; an overload takes a skip-set to leave some out. The selection wand is the one tool
that stays DraftSmith's own — players always get it with `/draft wand`, never from a host root.
[FabricPlots](https://github.com/beachfury/fabricplots) does exactly this — it bundles DraftSmith,
jails it to plot ownership, and serves the same tools as `/plot <subcommand>`.

### Maven repository

Release artifacts are published in Maven layout on this repo's
[`maven` branch](https://github.com/beachfury/draftsmith/tree/maven) — depend on DraftSmith without
building it:

```gradle
repositories {
    maven {
        url = "https://raw.githubusercontent.com/beachfury/draftsmith/maven/"
        content { includeGroup "com.draftsmith" }
    }
}

dependencies {
    implementation "com.draftsmith:draftsmith:1.0.1+26.3" // pick your Minecraft target
}
```

## Building

```
./gradlew build
```

Gradle 9 / Loom 1.17. Branch `main` targets Minecraft 26.1.2 with JDK 25, branches `26.2` and
`26.3` target those versions with JDK 25, and branch `1.21.1` targets Minecraft 1.21.1 with JDK 21
and Mojang mappings (the `fabric-loom-remap` plugin).

Releases also update the [`maven` branch](https://github.com/beachfury/draftsmith/tree/maven):
`tools/publish-maven.ps1` rebuilds every branch, mirrors the released artifacts into the maven
worktree (checksums + `maven-metadata.xml`), and commits — push it alongside the release branches.

## License

MIT
