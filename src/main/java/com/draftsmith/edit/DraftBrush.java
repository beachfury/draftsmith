package com.draftsmith.edit;

import com.draftsmith.api.DraftSmithApi;
import com.draftsmith.api.EditAccess;
import com.draftsmith.compat.Compat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Paint brushes — a brush item whose whole configuration lives ON the item (custom data
 * component), so every brush is its own tool: keep a gravel-path splatter in slot 1 and a leaf
 * blob in slot 2 and swap like a painter. Right-click paints where you aim (30 blocks); sneak +
 * right-click opens the config GUI. Strokes are jailed through the editor pipeline, land as
 * one undo entry each, and a particle ring previews the radius while you aim.
 */
public final class DraftBrush {
    public static final String BRUSH_NAME = "Paint Brush";
    // The vanilla archaeology brush — thematically right, and NOT a stick: Litematica and friends
    // claim the stick as their client-side tool and eat the right-click before it reaches us.
    static final net.minecraft.world.item.Item BRUSH_ITEM = Items.BRUSH;
    private static final String TAG = "draftsmith_brush";
    private static final String LEGACY_TAG = "fabricplots_brush"; // brushes made by FabricPlots ≤0.4.0
    private static final int REACH = 30;
    private static final java.util.Random RNG = new java.util.Random();

    public enum Type { SPLATTER, ROUND, OVERLAY, SPRAY, ERASE, WALL, RAISE, LOWER, SMOOTH, GRADIENT, BLEND }

    /** Brush settings, (de)serialized from the item's custom-data tag. */
    public static final class Config {
        public Type type = Type.SPLATTER;
        public int size = 4;            // radius, 1..15
        public int density = 60;        // % of area a splatter/spray stroke covers, 10..100
        public boolean fade = true;     // edges thin out
        public boolean surface = true;  // paint the top solid block of each column
        public String mask = "";        // only repaint this block id ("" = anything)
        public final List<String> palette = new ArrayList<>();
    }

    private DraftBrush() {}

    private static EditAccess access() { return DraftSmithApi.access(); }

    // ---- the brush item ----------------------------------------------------

    public static ItemStack createBrush() {
        ItemStack s = new ItemStack(BRUSH_ITEM);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(BRUSH_NAME));
        write(s, new Config());
        return s;
    }

    public static boolean isBrush(ItemStack s) {
        if (s.isEmpty() || s.getItem() != BRUSH_ITEM) return false;
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return false;
        CompoundTag t = d.copyTag();
        return t.contains(TAG) || t.contains(LEGACY_TAG);
    }

    public static Config read(ItemStack s) {
        Config c = new Config();
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return c;
        CompoundTag root = d.copyTag();
        // 1.21.1 CompoundTag has no *Or getters (26.x: getStringOr/getIntOr/getBooleanOr) — the
        // contains() guards matter for the booleans, whose absent-default is TRUE, not false.
        CompoundTag t = root.contains(TAG) ? root.getCompound(TAG) : root.getCompound(LEGACY_TAG);
        try { c.type = Type.valueOf(t.contains("type") ? t.getString("type") : "SPLATTER"); } catch (Exception ignored) {}
        c.size = Math.max(1, Math.min(15, t.contains("size") ? t.getInt("size") : 4));
        c.density = Math.max(10, Math.min(100, t.contains("density") ? t.getInt("density") : 60));
        c.fade = !t.contains("fade") || t.getBoolean("fade");
        c.surface = !t.contains("surface") || t.getBoolean("surface");
        c.mask = t.getString("mask");
        String pal = t.getString("palette");
        if (!pal.isEmpty()) for (String id : pal.split(",")) if (!id.isBlank()) c.palette.add(id);
        return c;
    }

    public static void write(ItemStack s, Config c) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = d != null ? d.copyTag() : new CompoundTag();
        CompoundTag t = new CompoundTag();
        t.putString("type", c.type.name());
        t.putInt("size", c.size);
        t.putInt("density", c.density);
        t.putBoolean("fade", c.fade);
        t.putBoolean("surface", c.surface);
        t.putString("mask", c.mask);
        t.putString("palette", String.join(",", c.palette));
        root.remove(LEGACY_TAG); // migrate legacy brushes to the new tag on first write
        root.put(TAG, t);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    // ---- events ------------------------------------------------------------

    /** openGui is injected by the initializer wiring so edit/ never depends on gui/. */
    public static void register(Consumer<ServerPlayer> openGui) {
        // 1.21.1 fabric-api: UseItemCallback returns InteractionResultHolder<ItemStack> (26.x: plain InteractionResult).
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack held = player.getItemInHand(hand);
            if (!access().isActiveDimension(world)) return InteractionResultHolder.pass(held);
            if (!isBrush(held)) return InteractionResultHolder.pass(held);
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(held);
            if (!access().canUse(sp)) return InteractionResultHolder.pass(held);
            if (player.isShiftKeyDown()) PENDING_GUI.add(sp.getUUID()); else paint(sp, held);
            return InteractionResultHolder.success(held);
        });
        // Clicking directly on a block fires UseBlock first — same behavior, and swallow the click
        // so the brush never opens chests / presses buttons mid-stroke.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!access().isActiveDimension(world)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!isBrush(held)) return InteractionResult.PASS;
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            if (!access().canUse(sp)) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) PENDING_GUI.add(sp.getUUID()); else paint(sp, held);
            return InteractionResult.SUCCESS;
        });
        // GUI opens are queued and drained here — end-of-tick, safely after the click packet that
        // requested them (opening inside the interaction gets closed by the client immediately).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            boolean preview = server.getTickCount() % 4 == 0;
            if (PENDING_GUI.isEmpty() && !preview) return;
            for (ServerLevel level : server.getAllLevels()) {
                if (!access().isActiveDimension(level)) continue;
                for (ServerPlayer sp : level.players()) {
                    if (PENDING_GUI.remove(sp.getUUID())) openGui.accept(sp);
                    if (!preview) continue;
                    ItemStack held = sp.getMainHandItem();
                    if (isBrush(held)) { if (access().canUse(sp)) previewRing(sp, level, read(held).size); }
                    else if (DraftEdit.isWand(held)) outlineSelection(sp, level);
                }
            }
            PENDING_GUI.clear(); // anyone who left the active dimensions mid-click
        });
    }

    private static final java.util.Set<java.util.UUID> PENDING_GUI = new java.util.HashSet<>();

    // ---- painting ----------------------------------------------------------

    static void paint(ServerPlayer sp, ItemStack brush) {
        ServerLevel level = (ServerLevel) sp.level();
        HitResult hit = sp.pick(REACH, 0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) { DraftEdit.msg(sp, "Aim at a block within " + REACH + " blocks."); return; }
        BlockPos center = ((BlockHitResult) hit).getBlockPos();
        Config c = read(brush);

        List<BlockState> palette = new ArrayList<>();
        for (String id : c.palette) { Block b = Compat.block(id); if (b != Blocks.AIR) palette.add(b.defaultBlockState()); }
        boolean needsPalette = switch (c.type) {
            case SPLATTER, ROUND, OVERLAY, SPRAY, WALL, GRADIENT -> true;
            default -> false; // erase/raise/lower/smooth/blend work from the world itself
        };
        if (needsPalette && palette.isEmpty()) {
            DraftEdit.msg(sp, "This brush has no blocks yet — sneak + right-click to open it and fill the palette.");
            return;
        }
        Block maskBlock = c.mask.isEmpty() ? null : Compat.block(c.mask);
        boolean admin = access().isAdmin(sp);
        int r = c.size;
        List<DraftEdit.Write> writes = new ArrayList<>();

        // Surface-mode Erase = restore the ground, not dig holes: clear everything above the
        // host's ground level, repaint the floor block (when the host defines one), and refill
        // any holes dug below it. With no ground concept, it clears a band around the aim point.
        // Ball-mode Erase still just clears to air.
        if (c.type == Type.ERASE && c.surface) {
            int top = center.getY() + Math.max(6, Math.min(r, 10));
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.45) continue;
                double chance = c.fade ? Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5))) : 1.0;
                if (RNG.nextDouble() > chance) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                EditAccess.Ground ground = access().ground(level, x, z);
                int floorY = ground != null ? ground.y() : center.getY() - r - 1;
                for (int y = top; y > floorY; y--) {
                    BlockState cur = level.getBlockState(new BlockPos(x, y, z));
                    if (cur.isAir()) continue;
                    if (maskBlock != null && !cur.is(maskBlock)) continue;
                    if (DraftEdit.canEdit(sp, admin, x, y, z))
                        writes.add(new DraftEdit.Write(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState()));
                }
                if (maskBlock == null && ground != null && ground.surface() != null) {
                    BlockState cur = level.getBlockState(new BlockPos(x, ground.y(), z));
                    if (!cur.equals(ground.surface()) && DraftEdit.canEdit(sp, admin, x, ground.y(), z))
                        writes.add(new DraftEdit.Write(new BlockPos(x, ground.y(), z), ground.surface()));
                    // refill anything dug out below the floor
                    for (int y = ground.y() - 1; y >= Math.max(access().minY(level), ground.y() - 8); y--) {
                        if (level.getBlockState(new BlockPos(x, y, z)).isAir() && DraftEdit.canEdit(sp, admin, x, y, z))
                            writes.add(new DraftEdit.Write(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState()));
                    }
                }
            }
            DraftEdit.commit(sp, level, writes, "Restored ground —");
            return;
        }

        // ---- terraformers: raise / lower / smooth ----
        if (c.type == Type.RAISE || c.type == Type.LOWER || c.type == Type.SMOOTH) {
            terraform(sp, level, c, center, admin, r, palette, writes);
            DraftEdit.commit(sp, level, writes, brushVerb(c.type));
            return;
        }

        // ---- gradient: palette in ORDER - radial on the ground, bottom-to-top on a wall ----
        if (c.type == Type.GRADIENT) {
            net.minecraft.core.Direction face = ((BlockHitResult) hit).getDirection();
            int n = palette.size();
            if (face.getAxis().isHorizontal()) {
                net.minecraft.core.Direction inward = face.getOpposite(), tangent = face.getClockWise();
                for (int u = -r; u <= r; u++) for (int v = -r; v <= r; v++) {
                    if (Math.sqrt(u * u + v * v) > r + 0.45) continue;
                    double t = (v + r + 0.5) / (2 * r + 1) * n;              // 0 bottom -> n top
                    BlockState chosen = palette.get(clampIdx((int) (t + (RNG.nextDouble() - 0.5) * 0.9), n));
                    BlockPos start = center.relative(face, 2).relative(tangent, u).above(v);
                    for (int k = 0; k <= 5; k++) {
                        BlockPos wp = start.relative(inward, k);
                        BlockState cur = level.getBlockState(wp);
                        if (cur.isAir() || !cur.getFluidState().isEmpty()) continue;
                        if (level.getBlockState(wp.relative(face)).isAir())
                            addWrite(writes, sp, level, admin, wp, cur, maskBlock, palette, c, false, chosen);
                        break;
                    }
                }
            } else {
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > r + 0.45) continue;
                    double t = dist / (r + 0.5) * n;                          // palette[0] center -> last at edge
                    BlockState chosen = palette.get(clampIdx((int) (t + (RNG.nextDouble() - 0.5) * 0.9), n));
                    for (int y = center.getY(); y >= center.getY() - r - 2; y--) {
                        BlockPos wp = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        BlockState cur = level.getBlockState(wp);
                        if (cur.isAir() || !cur.getFluidState().isEmpty()) continue;
                        if (level.getBlockState(wp.above()).isAir())
                            addWrite(writes, sp, level, admin, wp, cur, maskBlock, palette, c, true, chosen);
                        break;
                    }
                }
            }
            DraftEdit.commit(sp, level, writes, brushVerb(c.type));
            return;
        }

        // ---- blend: re-scatter the blocks already in the stroke - erases seams ----
        if (c.type == Type.BLEND) {
            List<BlockState> pool = new ArrayList<>();
            List<BlockPos> targets = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.45) continue;
                if (c.surface) {
                    for (int y = center.getY(); y >= center.getY() - r - 2; y--) {
                        BlockPos wp = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        BlockState cur = level.getBlockState(wp);
                        if (cur.isAir() || !cur.getFluidState().isEmpty()) continue;
                        if (level.getBlockState(wp.above()).isAir()) { pool.add(cur); targets.add(wp); }
                        break;
                    }
                } else {
                    for (int dy = -r; dy <= r; dy++) {
                        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > r + 0.45) continue;
                        BlockPos wp = center.offset(dx, dy, dz);
                        BlockState cur = level.getBlockState(wp);
                        if (!cur.isAir() && cur.getFluidState().isEmpty()) { pool.add(cur); targets.add(wp); }
                    }
                }
            }
            if (pool.isEmpty()) { DraftEdit.msg(sp, "Nothing to blend here."); return; }
            for (BlockPos wp : targets) {
                double dist = Math.hypot(wp.getX() - center.getX(), wp.getZ() - center.getZ());
                double chance = c.density / 100.0;
                if (c.fade) chance *= Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5)));
                if (RNG.nextDouble() > chance) continue;
                addWrite(writes, sp, level, admin, wp, level.getBlockState(wp), maskBlock, palette, c, false,
                        pool.get(RNG.nextInt(pool.size())));
            }
            DraftEdit.commit(sp, level, writes, brushVerb(c.type));
            return;
        }

        if (c.type == Type.WALL) {
            net.minecraft.core.Direction face = ((BlockHitResult) hit).getDirection();
            if (!face.getAxis().isHorizontal()) { DraftEdit.msg(sp, "Aim at the SIDE of a wall to texture it."); return; }
            net.minecraft.core.Direction inward = face.getOpposite(), tangent = face.getClockWise();
            for (int u = -r; u <= r; u++) for (int v = -r; v <= r; v++) {
                double dist = Math.sqrt(u * u + v * v);
                if (dist > r + 0.45) continue;
                double chance = c.density / 100.0;
                if (c.fade) chance *= Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5)));
                if (RNG.nextDouble() > chance) continue;
                // scan into the wall from just in front of it, so bumpy walls still get painted
                BlockPos start = center.relative(face, 2).relative(tangent, u).above(v);
                for (int k = 0; k <= 5; k++) {
                    BlockPos wp = start.relative(inward, k);
                    BlockState cur = level.getBlockState(wp);
                    if (cur.isAir() || !cur.getFluidState().isEmpty()) continue;
                    // a wall face is a face exposed to air on the side you aim at — the lawn in
                    // front of the wall fails this test, so the ground never gets wall texture
                    if (level.getBlockState(wp.relative(face)).isAir())
                        addWrite(writes, sp, level, admin, wp, cur, maskBlock, palette, c, false, null);
                    break;
                }
            }
            DraftEdit.commit(sp, level, writes, brushVerb(c.type));
            return;
        }

        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > r + 0.45) continue;
            double chance = switch (c.type) {
                case SPLATTER -> c.density / 100.0;
                case SPRAY -> c.density / 100.0 * 0.2;
                default -> 1.0;
            };
            if (c.fade) chance *= Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5)));
            boolean surfaceMode = c.surface || c.type == Type.OVERLAY;

            if (surfaceMode) {
                if (RNG.nextDouble() > chance) continue;
                // Splatter/spray never reach above the aimed block, and only paint OPEN surfaces —
                // so strokes can't stack onto blocks from earlier strokes and ratchet upward.
                boolean scatter = c.type == Type.SPLATTER || c.type == Type.SPRAY;
                int scanTop = scatter ? center.getY() : center.getY() + Math.min(r, 6) + 1;
                for (int y = scanTop; y >= center.getY() - r - 2; y--) {
                    BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    BlockState cur = level.getBlockState(p);
                    if (cur.isAir() || !cur.getFluidState().isEmpty()) continue;
                    if (scatter && !level.getBlockState(p.above()).isAir()) break; // covered — skip column
                    addWrite(writes, sp, level, admin, p, cur, maskBlock, palette, c, true, null);
                    break;
                }
            } else {
                for (int dy = -r; dy <= r; dy++) {
                    double d3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d3 > r + 0.45) continue;
                    double ballChance = switch (c.type) {
                        case SPLATTER -> c.density / 100.0;
                        case SPRAY -> c.density / 100.0 * 0.2;
                        default -> 1.0;
                    };
                    if (c.fade) ballChance *= Math.max(0, 1.0 - (d3 / (r + 0.5)) * (d3 / (r + 0.5)));
                    if (RNG.nextDouble() > ballChance) continue;
                    BlockPos p = center.offset(dx, dy, dz);
                    addWrite(writes, sp, level, admin, p, level.getBlockState(p), maskBlock, palette, c, false, null);
                }
            }
        }
        DraftEdit.commit(sp, level, writes, brushVerb(c.type));
    }

    private static void addWrite(List<DraftEdit.Write> writes, ServerPlayer sp, ServerLevel level, boolean admin,
                                 BlockPos p, BlockState cur, Block maskBlock, List<BlockState> palette, Config c,
                                 boolean surfaceMode, BlockState forced) {
        if (!DraftEdit.canEdit(sp, admin, p.getX(), p.getY(), p.getZ())) return;
        if (maskBlock != null && !cur.is(maskBlock)) return;
        if (c.type == Type.ERASE) {
            if (!cur.isAir()) writes.add(new DraftEdit.Write(p, Blocks.AIR.defaultBlockState()));
            return;
        }
        BlockState chosen = forced != null ? forced : palette.get(RNG.nextInt(palette.size()));
        // Half blocks (slabs, trapdoors, carpets…) go ON TOP of the surface — sinking them into
        // the ground punches trapdoor-holes in the lawn. Full blocks and stairs replace as before.
        if (surfaceMode && sitsOnTop(chosen)) {
            BlockPos top = p.above();
            if (!level.getBlockState(top).isAir()) return;
            if (!DraftEdit.canEdit(sp, admin, top.getX(), top.getY(), top.getZ())) return;
            // buttons, levers, grindstones default to wall attachment — on the ground they lie flat
            if (chosen.hasProperty(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE))
                chosen = chosen.setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
            writes.add(new DraftEdit.Write(top, chosen));
        } else {
            writes.add(new DraftEdit.Write(p, chosen));
        }
    }

    /**
     * Anything that is not a full cube rests ON the surface; full cubes replace it. Asked of the
     * block itself (collision shape), so every block — vanilla or modded — sorts itself with no
     * per-block list. Stairs are the one agreed exception: they sink into the ground.
     */
    private static boolean sitsOnTop(BlockState s) {
        if (s.getBlock() instanceof net.minecraft.world.level.block.StairBlock) return false;
        return !s.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static String brushVerb(Type t) {
        return switch (t) {
            case SPLATTER -> "Splattered"; case ROUND -> "Painted"; case OVERLAY -> "Overlaid";
            case SPRAY -> "Sprayed"; case ERASE -> "Erased"; case WALL -> "Textured";
            case RAISE -> "Raised"; case LOWER -> "Lowered"; case SMOOTH -> "Smoothed";
            case GRADIENT -> "Gradient"; case BLEND -> "Blended";
        } + " —";
    }

    /** Raise mounds terrain up, Lower dips it, Smooth averages each column toward its 3x3 mean. */
    private static void terraform(ServerPlayer sp, ServerLevel level, Config c, BlockPos center,
                                  boolean admin, int r, List<BlockState> palette, List<DraftEdit.Write> writes) {
        int peak = Math.max(1, r / 2);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > r + 0.45) continue;
            int x = center.getX() + dx, z = center.getZ() + dz;
            int sy = surfaceY(level, x, z, center.getY(), r + 8);
            if (sy == Integer.MIN_VALUE) continue;
            BlockState surf = level.getBlockState(new BlockPos(x, sy, z));

            int target = sy;
            if (c.type == Type.SMOOTH) {
                int sum = 0, cnt = 0;
                for (int nx = -1; nx <= 1; nx++) for (int nz = -1; nz <= 1; nz++) {
                    int ny = surfaceY(level, x + nx, z + nz, center.getY(), r + 8);
                    if (ny != Integer.MIN_VALUE) { sum += ny; cnt++; }
                }
                if (cnt > 0) target = Math.round((float) sum / cnt);
            } else {
                double fall = c.fade ? Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5))) : 1.0;
                int amount = (int) Math.round(peak * fall);
                if (amount == 0) continue;
                target = c.type == Type.RAISE ? sy + amount : sy - amount;
            }

            if (target > sy) {                    // build the column up: filler below, cap on top
                for (int y = sy + 1; y <= target; y++) {
                    if (!DraftEdit.canEdit(sp, admin, x, y, z)) break;
                    BlockState put = !palette.isEmpty() ? palette.get(RNG.nextInt(palette.size()))
                            : (y == target ? surf : Blocks.DIRT.defaultBlockState());
                    writes.add(new DraftEdit.Write(new BlockPos(x, y, z), put));
                }
            } else if (target < sy) {             // carve down, then re-cap the new surface
                for (int y = sy; y > target; y--)
                    if (DraftEdit.canEdit(sp, admin, x, y, z))
                        writes.add(new DraftEdit.Write(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState()));
                if (DraftEdit.canEdit(sp, admin, x, target, z)) {
                    BlockState cap = !palette.isEmpty() ? palette.get(RNG.nextInt(palette.size())) : surf;
                    writes.add(new DraftEdit.Write(new BlockPos(x, target, z), cap));
                }
            }
        }
    }

    /** Topmost solid, non-fluid block near refY, or MIN_VALUE if the window is all air. */
    private static int surfaceY(ServerLevel level, int x, int z, int refY, int window) {
        for (int y = refY + window; y >= refY - window; y--) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (!s.isAir() && s.getFluidState().isEmpty()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static int clampIdx(int i, int n) { return Math.max(0, Math.min(n - 1, i)); }

    // ---- particle previews ---------------------------------------------------

    private static void previewRing(ServerPlayer sp, ServerLevel level, int r) {
        HitResult hit = sp.pick(REACH, 0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos c = ((BlockHitResult) hit).getBlockPos();
        int points = Math.max(12, r * 6);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            level.sendParticles(sp, ParticleTypes.END_ROD, true, // 1.21.1: one boolean (force); 26.x has two
                    c.getX() + 0.5 + Math.cos(a) * (r + 0.5), c.getY() + 1.1, c.getZ() + 0.5 + Math.sin(a) * (r + 0.5),
                    1, 0, 0, 0, 0);
        }
    }

    /** Corner 1 → corner 2 box edges while the editor wand is in hand. */
    private static void outlineSelection(ServerPlayer sp, ServerLevel level) {
        BlockPos p1 = DraftEdit.POS1.get(sp.getUUID()), p2 = DraftEdit.POS2.get(sp.getUUID());
        if (p1 == null || p2 == null) return;
        double x1 = Math.min(p1.getX(), p2.getX()), x2 = Math.max(p1.getX(), p2.getX()) + 1;
        double y1 = Math.min(p1.getY(), p2.getY()), y2 = Math.max(p1.getY(), p2.getY()) + 1;
        double z1 = Math.min(p1.getZ(), p2.getZ()), z2 = Math.max(p1.getZ(), p2.getZ()) + 1;
        double step = 1.5;
        for (double x = x1; x <= x2; x += step) for (double[] yz : new double[][]{{y1,z1},{y1,z2},{y2,z1},{y2,z2}})
            level.sendParticles(sp, ParticleTypes.HAPPY_VILLAGER, true,x, yz[0], yz[1], 1, 0, 0, 0, 0);
        for (double y = y1; y <= y2; y += step) for (double[] xz : new double[][]{{x1,z1},{x1,z2},{x2,z1},{x2,z2}})
            level.sendParticles(sp, ParticleTypes.HAPPY_VILLAGER, true,xz[0], y, xz[1], 1, 0, 0, 0, 0);
        for (double z = z1; z <= z2; z += step) for (double[] xy : new double[][]{{x1,y1},{x1,y2},{x2,y1},{x2,y2}})
            level.sendParticles(sp, ParticleTypes.HAPPY_VILLAGER, true,xy[0], xy[1], z, 1, 0, 0, 0, 0);
    }
}
