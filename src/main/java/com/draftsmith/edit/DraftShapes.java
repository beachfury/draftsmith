package com.draftsmith.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shapes engine (Shapes screen of the editor): parametric circles, squares, spheres,
 * cylinders, pyramids and lines, plus the self-cleaning gold center marker. Every build goes
 * through {@link DraftEdit#commit} so it is jailed and undoable like any other edit.
 */
public final class DraftShapes {
    // Shape center marker (gold): the chosen center + the blocks it covered (restored on clear).
    private static final Map<UUID, BlockPos> SHAPE_CENTER = new HashMap<>();
    private static final Map<UUID, List<DraftEdit.Snapshot>> CENTER_MARKER = new HashMap<>();

    private DraftShapes() {}

    public enum Shape { CIRCLE, SQUARE, SPHERE, CYLINDER, PYRAMID, LINE }

    /**
     * Place (or move) the gold center marker at the player's feet: a single block for odd sizes,
     * a 2x2 for even sizes (the true center is the corner between the four). Self-cleaning: the
     * covered blocks are restored when the marker moves, is cleared, or a shape is built.
     */
    public static void setShapeCenter(ServerPlayer sp, ServerLevel level, boolean evenSize) {
        BlockPos feet = sp.blockPosition().immutable();
        List<BlockPos> positions = new ArrayList<>();
        int span = evenSize ? 2 : 1;
        for (int dx = 0; dx < span; dx++) for (int dz = 0; dz < span; dz++)
            positions.add(feet.offset(dx, 0, dz));
        placeMarker(sp, level, positions, feet);
        DraftEdit.msg(sp, "Center marked at " + DraftEdit.xyz(feet) + (evenSize ? " (2x2 — even size)" : "") + ". Build when ready.");
    }

    /** Drop the self-cleaning gold marker on the given blocks; base becomes the shape center. */
    private static void placeMarker(ServerPlayer sp, ServerLevel level, List<BlockPos> positions, BlockPos base) {
        clearShapeMarker(sp, level);
        UUID id = sp.getUUID();
        List<DraftEdit.Snapshot> covered = new ArrayList<>();
        boolean admin = DraftEdit.access().isAdmin(sp);
        for (BlockPos p : positions) {
            if (!DraftEdit.canEdit(sp, admin, p.getX(), p.getY(), p.getZ())) continue;
            if (DraftEdit.unsafeWrite(level, p, Blocks.GOLD_BLOCK.defaultBlockState())) {
                DraftEdit.msg(sp, "Center marker skipped a container/sign to protect its data.");
                continue;
            }
            covered.add(new DraftEdit.Snapshot(p, level.getBlockState(p)));
            level.setBlock(p, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        SHAPE_CENTER.put(id, base);
        CENTER_MARKER.put(id, covered);
    }

    /**
     * Find the middle of the corner 1 → corner 2 line and mark it with gold — one block when the
     * line is an odd number of blocks long, the middle two when it's even (same rule as shapes).
     * The marker doubles as the shape center, so you can find-center then build a circle on it.
     */
    public static int findLineCenter(ServerPlayer sp, ServerLevel level) {
        UUID id = sp.getUUID();
        BlockPos p1 = DraftEdit.POS1.get(id), p2 = DraftEdit.POS2.get(id);
        if (p1 == null || p2 == null) { DraftEdit.msg(sp, "Set corner 1 and corner 2 first — the center is found along that line."); return 0; }
        int steps = Math.max(Math.max(Math.abs(p2.getX() - p1.getX()), Math.abs(p2.getY() - p1.getY())),
                Math.abs(p2.getZ() - p1.getZ()));
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            cells.add(new BlockPos((int) Math.round(p1.getX() + (p2.getX() - p1.getX()) * f),
                    (int) Math.round(p1.getY() + (p2.getY() - p1.getY()) * f),
                    (int) Math.round(p1.getZ() + (p2.getZ() - p1.getZ()) * f)));
        }
        int n = cells.size();
        List<BlockPos> mid = (n % 2 == 1)
                ? List.of(cells.get(n / 2))
                : List.of(cells.get(n / 2 - 1), cells.get(n / 2));
        placeMarker(sp, level, mid, mid.get(0));
        DraftEdit.msg(sp, "Line is " + n + " blocks long — center marked at " + DraftEdit.xyz(mid.get(0))
                + (n % 2 == 0 ? " (2 blocks — even length)" : "") + ".");
        return 1;
    }

    /** Restore whatever the gold marker covered (no-op if none). */
    public static void clearShapeMarker(ServerPlayer sp, ServerLevel level) {
        List<DraftEdit.Snapshot> covered = CENTER_MARKER.remove(sp.getUUID());
        if (covered == null) return;
        boolean admin = DraftEdit.access().isAdmin(sp);
        for (DraftEdit.Snapshot s : covered) {
            // Only un-place gold we placed (don't clobber a block the player changed since).
            if (DraftEdit.canEdit(sp, admin, s.pos().getX(), s.pos().getY(), s.pos().getZ())
                    && !DraftEdit.blockEntityState(s.old())
                    && level.getBlockState(s.pos()).is(Blocks.GOLD_BLOCK)) {
                level.setBlock(s.pos(), s.old(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Build a parametric shape centered on the marker, or — with no marker set — on the block the
     * player is AIMING at (like the paint brushes), falling back to their feet only when nothing is
     * in range. Aiming keeps builders out of the middle of their own spheres.
     * Flat shapes (circle/square) extrude {@code height} layers up; repeat stacks copies with
     * {@code spacing} air between them. Hollow shells are {@code thickness} blocks thick.
     */
    public static int buildShape(ServerPlayer sp, ServerLevel level, BlockState held, Shape shape,
                                 boolean hollow, int size, int height, int thickness, int repeat, int spacing) {
        if (DraftEdit.blockEntityState(held)) return DraftEdit.rejectBlockEntity(sp);
        if (shape == Shape.LINE) return line(sp, level, held, thickness);
        long perShape = estimate(shape, hollow, size, height, thickness);
        if (perShape > DraftLimits.MAX_BLOCKS
                || DraftLimits.exceeds(Math.max(1L, perShape), repeat, DraftLimits.MAX_BLOCKS))
            return DraftEdit.rejectLarge(sp);
        boolean admin = DraftEdit.access().isAdmin(sp);
        BlockPos base = SHAPE_CENTER.get(sp.getUUID());
        if (base == null) {
            // Same raycast the brushes use: center on the aimed block, feet only as a last resort.
            HitResult hit = sp.pick(30, 0f, false);
            base = hit.getType() == HitResult.Type.BLOCK
                    ? ((BlockHitResult) hit).getBlockPos().immutable()
                    : sp.blockPosition().immutable();
        }
        clearShapeMarker(sp, level); // the marker's ground state must be what the shape replaces
        SHAPE_CENTER.remove(sp.getUUID());

        int half = (size - 1) / 2;
        int xMin = base.getX() - half, xMax = xMin + size - 1;
        int zMin = base.getZ() - half, zMax = zMin + size - 1;
        double cx = (xMin + xMax) / 2.0, cz = (zMin + zMax) / 2.0;
        double outer = size / 2.0;

        int shapeHeight = switch (shape) {
            case SPHERE -> size;
            case PYRAMID -> (size + 1) / 2;
            default -> height;
        };
        var mat = DraftEdit.materials(sp, held);
        List<DraftEdit.Write> writes = new ArrayList<>();
        for (int k = 0; k < repeat; k++) {
            int yBase = base.getY() + k * (shapeHeight + spacing);
            switch (shape) {
                case CIRCLE, SQUARE, CYLINDER -> {
                    for (int x = xMin; x <= xMax; x++) for (int z = zMin; z <= zMax; z++) {
                        double dist = (shape == Shape.SQUARE)
                                ? Math.max(Math.abs(x - cx), Math.abs(z - cz))
                                : Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                        if (dist > outer) continue;
                        if (hollow && dist <= outer - thickness) continue;
                        for (int dy = 0; dy < height; dy++)
                            if (DraftEdit.canEdit(sp, admin, x, yBase + dy, z))
                                writes.add(new DraftEdit.Write(new BlockPos(x, yBase + dy, z), mat.get()));
                    }
                }
                case SPHERE -> {
                    double cy = yBase + (size - 1) / 2.0;
                    for (int x = xMin; x <= xMax; x++) for (int z = zMin; z <= zMax; z++)
                        for (int y = yBase; y < yBase + size; y++) {
                            double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
                            if (dist > outer) continue;
                            if (hollow && dist <= outer - thickness) continue;
                            if (DraftEdit.canEdit(sp, admin, x, y, z)) writes.add(new DraftEdit.Write(new BlockPos(x, y, z), mat.get()));
                        }
                }
                case PYRAMID -> {
                    for (int layer = 0; layer * 2 < size; layer++) {
                        int lxMin = xMin + layer, lxMax = xMax - layer;
                        int lzMin = zMin + layer, lzMax = zMax - layer;
                        int y = yBase + layer;
                        for (int x = lxMin; x <= lxMax; x++) for (int z = lzMin; z <= lzMax; z++) {
                            if (hollow) {
                                boolean edge = (x - lxMin < thickness) || (lxMax - x < thickness)
                                        || (z - lzMin < thickness) || (lzMax - z < thickness);
                                if (!edge) continue;
                            }
                            if (DraftEdit.canEdit(sp, admin, x, y, z)) writes.add(new DraftEdit.Write(new BlockPos(x, y, z), mat.get()));
                        }
                    }
                }
                case LINE -> { /* handled above */ }
            }
        }
        String label = (hollow ? "Hollow " : "") + shape.name().toLowerCase() + " —";
        return DraftEdit.commit(sp, level, writes, label.substring(0, 1).toUpperCase() + label.substring(1));
    }

    /**
     * Draw a straight line from corner 1 to corner 2 — any diagonal, any slope, full 3D. The line
     * visits every block the ideal segment passes through; thickness grows it into a square beam.
     */
    public static int line(ServerPlayer sp, ServerLevel level, BlockState held, int thickness) {
        if (DraftEdit.blockEntityState(held)) return DraftEdit.rejectBlockEntity(sp);
        UUID id = sp.getUUID();
        BlockPos p1 = DraftEdit.POS1.get(id), p2 = DraftEdit.POS2.get(id);
        if (p1 == null || p2 == null) { DraftEdit.msg(sp, "A line runs corner 1 → corner 2 — set both first (/draft pos1, /draft pos2 or the wand)."); return 0; }
        long boundedSteps = Math.max(Math.max(Math.abs((long) p2.getX() - p1.getX()), Math.abs((long) p2.getY() - p1.getY())),
                Math.abs((long) p2.getZ() - p1.getZ())) + 1L;
        long side = Math.max(1L, 2L * Math.max(0, thickness - 1) + 1L);
        if (DraftLimits.exceeds(side, side, Long.MAX_VALUE)
                || DraftLimits.exceeds(side * side, side, Long.MAX_VALUE)
                || DraftLimits.exceeds(boundedSteps, side * side * side, DraftLimits.MAX_BLOCKS))
            return DraftEdit.rejectLarge(sp);
        boolean admin = DraftEdit.access().isAdmin(sp);
        int steps = Math.max(Math.max(Math.abs(p2.getX() - p1.getX()), Math.abs(p2.getY() - p1.getY())),
                Math.abs(p2.getZ() - p1.getZ()));
        var mat = DraftEdit.materials(sp, held);
        int radius = Math.max(0, thickness - 1);
        Set<BlockPos> cells = new java.util.LinkedHashSet<>();
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.round(p1.getX() + (p2.getX() - p1.getX()) * f);
            int y = (int) Math.round(p1.getY() + (p2.getY() - p1.getY()) * f);
            int z = (int) Math.round(p1.getZ() + (p2.getZ() - p1.getZ()) * f);
            for (int dx = -radius; dx <= radius; dx++)
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dz = -radius; dz <= radius; dz++)
                        cells.add(new BlockPos(x + dx, y + dy, z + dz));
        }
        List<DraftEdit.Write> writes = new ArrayList<>();
        for (BlockPos p : cells)
            if (DraftEdit.canEdit(sp, admin, p.getX(), p.getY(), p.getZ())) writes.add(new DraftEdit.Write(p, mat.get()));
        return DraftEdit.commit(sp, level, writes, "Line —");
    }

    /** Conservative write estimate used before shape code allocates its write list. */
    static long estimate(Shape shape, boolean hollow, int size, int height, int thickness) {
        double outer = size / 2.0;
        double inner = Math.max(0, outer - Math.max(1, thickness));
        return switch (shape) {
            case CIRCLE, CYLINDER -> (long) Math.ceil(Math.PI * (outer * outer
                    - (hollow ? inner * inner : 0)) * Math.max(1, height));
            case SQUARE -> {
                long outerArea = (long) size * size;
                long innerSide = Math.max(0, size - 2L * Math.max(1, thickness));
                yield (outerArea - (hollow ? innerSide * innerSide : 0)) * Math.max(1, height);
            }
            case SPHERE -> (long) Math.ceil(4.0 / 3.0 * Math.PI * (outer * outer * outer
                    - (hollow ? inner * inner * inner : 0)));
            case PYRAMID -> {
                long blocks = 0;
                for (long side = size; side > 0; side -= 2) {
                    long innerSide = Math.max(0, side - 2L * Math.max(1, thickness));
                    blocks += side * side - (hollow ? innerSide * innerSide : 0);
                    if (blocks > DraftLimits.MAX_BLOCKS) break;
                }
                yield blocks;
            }
            case LINE -> 0;
        };
    }
}
