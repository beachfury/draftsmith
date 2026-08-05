# Changelog

All notable changes to DraftSmith.

## [0.1.0] — 2026-08-04

The first release: the FabricPlots build editor, now its own mod.

DraftSmith is the GUI building toolkit that grew inside
[FabricPlots](https://github.com/beachfury/fabricplots) over its 0.3.0–0.4.0 releases, extracted so
any Fabric server can use it on any world — no plot world required. The code is the shipped, proven
0.4.0 editor; what's new is the packaging and the permission gate.

### Added
- **Everything the FabricPlots 0.4.0 editor had**, working on any world:
  - **Selection wand + corner commands**, with a green particle outline preview.
  - **Eleven paint brushes** (Splatter, Round, Overlay, Spray, Wall, Gradient, Blend, Raise, Lower,
    Smooth, Erase) with per-brush settings stored on the item, 9-slot weighted palette, mask,
    size/density/fade dials, Surface/Ball mode, and a radius particle preview.
  - **Shapes** — circle, square, sphere, cylinder, pyramid, line; filled or hollow; height, thickness,
    repeat and spacing; aim-centered with the gold marker override.
  - **Measuring tools** — selection readout, find-center, and up to 4 numbered measuring tapes.
  - **Clipboard** (copy/cut/aim-paste), **stack/move**, **fill/replace/walls**.
  - **Random texture mode** for edits *and* hand placement (hotbar-weighted).
  - **Undo/redo**, 10 deep, one entry per stroke, 65,536-block cap per edit.
- **`/draft` command root** — every editor subcommand from FabricPlots, same names, plus bare
  `/draft` opening the hub GUI and `/draft reload` for the config.
- **Ops-only by default.** Non-ops see nothing until an admin adds them to `editors` in
  `config/draftsmith.properties` (or a host mod supplies its own rules). A `worlds` list can restrict
  the editor to specific dimensions.
- **NEW: the protected list.** A second 9-slot row in the Brushes screen — the mask in reverse. Load
  it with blocks the brush must **never paint over** (up to nine), and everything else repaints as
  normal. The existing mask answers "only repaint X"; the protected list answers "repaint anything
  *except* these" — re-texture a mixed floor without eating the carpet, or terraform around a path
  without touching it. Applies to every brush type: strokes, Wall, Gradient, Blend, Erase (protected
  blocks survive the ground restore) and the Raise/Lower/Smooth terraformers (a protected surface
  freezes its whole column). Stored on the brush item like every other setting.
- **The `EditAccess` API** — one interface a host mod implements to decide where the editor runs, who
  may use it, and whether each individual block write is allowed. FabricPlots 0.5.0 uses it to jail
  the editor to plot ownership, unchanged from 0.4.0.
- **FabricPlots compatibility** — wands and brushes made by FabricPlots ≤0.4.0 (including pre-0.4.0
  named wands) are recognized and keep working; legacy brushes migrate their settings to the new tag
  the first time they're edited.
