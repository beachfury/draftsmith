package com.draftsmith.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one seam between the editor and the world it runs in. Every block the editor writes is
 * checked through this interface, so the host decides where the editor works, who may use it,
 * and how far an edit may reach. DraftSmith ships a conservative ops-only default; a host mod
 * (e.g. a plot-world mod) replaces it via {@link DraftSmithApi#setAccess} to jail edits to its
 * own ownership rules.
 */
public interface EditAccess {

    /** Where the editor runs at all — events, brushes and commands are inert elsewhere. */
    boolean isActiveDimension(Level level);

    /** May this player use the editor at all (commands, wand, brushes, GUIs)? */
    default boolean canUse(ServerPlayer p) { return true; }

    /**
     * Unclamped editing. Computed once per stroke and passed back into
     * {@link #canEdit(ServerPlayer, boolean, int, int, int)} so implementations can skip
     * per-block permission lookups.
     */
    boolean isAdmin(ServerPlayer p);

    /** Can this player write at x/y/z? The jail — every single block write is asked. */
    boolean canEdit(ServerPlayer p, boolean admin, int x, int y, int z);

    /** Lowest editable Y (used to clamp selection loops before the per-block check). */
    default int minY(ServerLevel level) { return level.getMinY(); }

    /** Highest editable Y. */
    default int maxY(ServerLevel level) { return level.getMaxY(); }

    /**
     * What "erase to ground" means at this column, or null when there is no ground concept —
     * the Erase brush then just clears a band around the aim point. When non-null, Erase clears
     * everything above {@code y}, repaints {@code y} with {@code surface} (when non-null), and
     * refills holes dug below it.
     */
    default Ground ground(ServerLevel level, int x, int z) { return null; }

    /** Ground level for the Erase brush: floor Y plus the floor block (null = don't repaint). */
    record Ground(int y, BlockState surface) {}

    /** The command root the editor's chat messages reference (e.g. "/draft undo to revert"). */
    default String commandRoot() { return "draft"; }

    /** Prefix on every chat line the editor sends. */
    default String messagePrefix() { return "[Draft] "; }

    /** Shown when an editor command is used outside an active dimension. */
    default String notHereMessage() { return "The editor isn't enabled in this world."; }

    /** What the editable area is called in chat ("outside your plot" vs "outside your editable area"). */
    default String editableAreaName() { return "your editable area"; }
}
