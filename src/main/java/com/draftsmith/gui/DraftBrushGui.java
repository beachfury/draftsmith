package com.draftsmith.gui;

import com.draftsmith.compat.Compat;
import com.draftsmith.edit.DraftBrush;
import com.draftsmith.edit.DraftEdit;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Brushes screen — configures the paint brush in the player's hand. Every change is
 * written straight onto the item (each brush is its own tool). Palette and mask slots use the
 * vanilla cursor: pick a block up from your inventory, click it into a slot; click with an empty
 * cursor to clear.
 */
public final class DraftBrushGui {

    private DraftBrushGui() {}

    public static void open(ServerPlayer sp) {
        if (!com.draftsmith.api.DraftSmithApi.access().canUse(sp, com.draftsmith.api.EditAccess.Tool.BRUSH)) {
            DraftEdit.msg(sp, "You don't have permission for the brush tools.");
            return;
        }
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        gui.setTitle(Component.literal("Build Editor — Brushes"));
        render(gui, sp);
        gui.open();
    }

    private static void render(SimpleGui gui, ServerPlayer sp) {
        for (int i = 0; i < 54; i++) gui.setSlot(i, DraftEditGui.filler());
        ItemStack brush = heldBrush(sp);

        if (brush == null) {
            gui.setSlot(22, new GuiElementBuilder(Items.BRUSH)
                    .setName(Component.literal("Get a paint brush"))
                    .addLoreLine(Component.literal("Hold the brush, then sneak + right-click to configure it"))
                    .addLoreLine(Component.literal("Each brush remembers its own settings — keep several!"))
                    .setCallback((i, t, a, g) -> {
                        sp.getInventory().placeItemBackInInventory(DraftBrush.createBrush());
                        DraftEdit.msg(sp, "Brush added to your inventory.");
                        render(gui, sp);
                    }).build());
            gui.setSlot(45, DraftEditGui.btn(Items.ARROW, "Back to editor", (i, t, a, g) -> DraftEditGui.open(sp)));
            return;
        }
        DraftBrush.Config c = DraftBrush.read(brush);

        // Rows 1-2 — brush types.
        typeBtn(gui, sp, brush, 0, Items.GRAVEL, DraftBrush.Type.SPLATTER, "Splatter", "Scattered random blocks — the path maker");
        typeBtn(gui, sp, brush, 1, Items.SNOWBALL, DraftBrush.Type.ROUND, "Round", "Solid stamp — disc on surface, ball in air mode");
        typeBtn(gui, sp, brush, 2, Items.GRASS_BLOCK, DraftBrush.Type.OVERLAY, "Overlay", "Repaints the exposed surface only (always surface mode)");
        typeBtn(gui, sp, brush, 3, Items.SUGAR, DraftBrush.Type.SPRAY, "Spray", "Very sparse dusting — flowers, ore flecks");
        typeBtn(gui, sp, brush, 4, Items.STONE_BRICKS, DraftBrush.Type.WALL, "Wall", "Textures the vertical face you aim at — mix up flat walls");
        typeBtn(gui, sp, brush, 5, Items.AMETHYST_SHARD, DraftBrush.Type.GRADIENT, "Gradient", "Palette IN ORDER — rings on the ground, bottom-to-top on walls");
        typeBtn(gui, sp, brush, 6, Items.CLAY_BALL, DraftBrush.Type.BLEND, "Blend", "Re-mixes the blocks already there — erases seams, no palette needed");
        typeBtn(gui, sp, brush, 7, Items.DIRT, DraftBrush.Type.RAISE, "Raise", "Mounds the terrain up (fade = smooth hill); palette overrides the fill");
        typeBtn(gui, sp, brush, 8, Items.IRON_SHOVEL, DraftBrush.Type.LOWER, "Lower", "Dips the terrain down, re-capping the new surface");
        typeBtn(gui, sp, brush, 9, Items.QUARTZ, DraftBrush.Type.SMOOTH, "Smooth", "Averages bumpy terrain toward its neighbors — repeat to melt edits");
        typeBtn(gui, sp, brush, 10, Items.SPONGE, DraftBrush.Type.ERASE, "Erase", "Surface mode restores the ground; ball mode clears to air");
        gui.setSlot(16, new GuiElementBuilder(c.surface ? Items.GRASS_BLOCK : Items.ENDER_PEARL)
                .setName(Component.literal("Mode: " + (c.surface ? "Surface" : "Ball")))
                .addLoreLine(Component.literal(c.surface
                        ? "Paints the top solid block of each column (paths hug terrain)"
                        : "Paints the full ball where you aim (blobs, leaves, veins)"))
                .addLoreLine(Component.literal("Click to switch"))
                .setCallback((i, t, a, g) -> { c.surface = !c.surface; save(sp, brush, c); render(gui, sp); }).build());

        // Row 2 — dials + mask.
        gui.setSlot(12, new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal("Size (radius): " + c.size)).setCount(Math.max(1, c.size))
                .addLoreLine(Component.literal("Left-click +1 · Right-click −1"))
                .setCallback((i, t, a, g) -> { c.size = clamp(c.size + (t.isRight ? -1 : 1), 1, 15); save(sp, brush, c); render(gui, sp); }).build());
        gui.setSlot(13, new GuiElementBuilder(Items.REDSTONE)
                .setName(Component.literal("Density: " + c.density + "%")).setCount(Math.max(1, Math.min(64, c.density)))
                .addLoreLine(Component.literal("How much of the stroke area gets painted"))
                .addLoreLine(Component.literal("Left-click +10 · Right-click −10"))
                .setCallback((i, t, a, g) -> { c.density = clamp(c.density + (t.isRight ? -10 : 10), 10, 100); save(sp, brush, c); render(gui, sp); }).build());
        gui.setSlot(14, new GuiElementBuilder(c.fade ? Items.FEATHER : Items.IRON_INGOT)
                .setName(Component.literal("Fade edges: " + (c.fade ? "ON" : "OFF")))
                .addLoreLine(Component.literal("ON = strokes thin out toward the edge (hand-worn look)"))
                .setCallback((i, t, a, g) -> { c.fade = !c.fade; save(sp, brush, c); render(gui, sp); }).build());
        Item maskItem = c.mask.isEmpty() ? Items.BARRIER : Compat.item(c.mask);
        gui.setSlot(17, new GuiElementBuilder(maskItem == Items.AIR ? Items.BARRIER : maskItem)
                .setName(Component.literal(c.mask.isEmpty() ? "Paint over: anything" : "Paint over: only " + pretty(c.mask)))
                .addLoreLine(Component.literal("Click holding a block to only repaint that block"))
                .addLoreLine(Component.literal("(protects walls near your path) · empty cursor clears"))
                .setCallback((i, t, a, g) -> {
                    ItemStack carried = sp.containerMenu.getCarried();
                    if (!carried.isEmpty() && carried.getItem() instanceof BlockItem bi)
                        c.mask = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                    else c.mask = "";
                    save(sp, brush, c); render(gui, sp);
                }).build());

        // Row 3 — the palette.
        for (int slot = 0; slot < 9; slot++) {
            final int idx = slot;
            String id = idx < c.palette.size() ? c.palette.get(idx) : null;
            Item icon = id != null ? Compat.item(id) : Compat.item("minecraft:light_gray_stained_glass_pane");
            GuiElementBuilder b = new GuiElementBuilder(id != null && icon != Items.AIR ? icon : (id != null ? Items.BARRIER : Compat.item("minecraft:light_gray_stained_glass_pane")))
                    .setName(Component.literal(id != null ? pretty(id) : "Empty palette slot"))
                    .addLoreLine(Component.literal("Click holding a block to set · empty cursor clears"))
                    .addLoreLine(Component.literal("Same block in several slots = more common"));
            gui.setSlot(18 + slot, b.setCallback((i, t, a, g) -> {
                ItemStack carried = sp.containerMenu.getCarried();
                if (!carried.isEmpty() && carried.getItem() instanceof BlockItem bi) {
                    String bid = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                    while (c.palette.size() <= idx) c.palette.add(bid);
                    c.palette.set(idx, bid);
                } else if (idx < c.palette.size()) {
                    c.palette.remove(idx);
                }
                save(sp, brush, c); render(gui, sp);
            }).build());
        }

        // Row 5 — the protected list: blocks a stroke must NEVER paint over (masks in reverse —
        // the palette is what the brush paints WITH, this row is what it must leave alone).
        for (int slot = 0; slot < 9; slot++) {
            final int idx = slot;
            String id = idx < c.protect.size() ? c.protect.get(idx) : null;
            Item icon = id != null ? Compat.item(id) : Compat.item("minecraft:red_stained_glass_pane");
            GuiElementBuilder b = new GuiElementBuilder(id != null && icon != Items.AIR ? icon : (id != null ? Items.BARRIER : Compat.item("minecraft:red_stained_glass_pane")))
                    .setName(Component.literal(id != null ? "Protected: " + pretty(id) : "Empty protected slot"))
                    .addLoreLine(Component.literal("Click holding a block — that block is NEVER painted over"))
                    .addLoreLine(Component.literal("All other blocks repaint as normal · empty cursor clears"));
            gui.setSlot(36 + slot, b.setCallback((i, t, a, g) -> {
                ItemStack carried = sp.containerMenu.getCarried();
                if (!carried.isEmpty() && carried.getItem() instanceof BlockItem bi) {
                    String bid = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                    while (c.protect.size() <= idx) c.protect.add(bid);
                    c.protect.set(idx, bid);
                } else if (idx < c.protect.size()) {
                    c.protect.remove(idx);
                }
                save(sp, brush, c); render(gui, sp);
            }).build());
        }

        // Row 4 — brush management.
        gui.setSlot(28, new GuiElementBuilder(Items.BRUSH)
                .setName(Component.literal("Get another brush"))
                .addLoreLine(Component.literal("A fresh brush with default settings"))
                .setCallback((i, t, a, g) -> {
                    sp.getInventory().placeItemBackInInventory(DraftBrush.createBrush());
                    DraftEdit.msg(sp, "Brush added to your inventory.");
                }).build());
        gui.setSlot(30, DraftEditGui.btn(Items.BARRIER, "Clear palette",
                (i, t, a, g) -> { c.palette.clear(); save(sp, brush, c); render(gui, sp); }));
        gui.setSlot(32, DraftEditGui.btn(Items.SHIELD, "Clear protected list",
                (i, t, a, g) -> { c.protect.clear(); save(sp, brush, c); render(gui, sp); }));
        gui.setSlot(34, DraftEditGui.btn(Items.CLOCK, "Undo last stroke",
                (i, t, a, g) -> DraftEdit.undo(sp, (net.minecraft.server.level.ServerLevel) sp.level())));

        // Row 6 — back + status.
        gui.setSlot(45, DraftEditGui.btn(Items.ARROW, "Back to editor", (i, t, a, g) -> DraftEditGui.open(sp)));
        gui.setSlot(49, new GuiElementBuilder(Items.BRUSH)
                .setName(Component.literal("Editing: the brush in your hand"))
                .addLoreLine(Component.literal(c.type.name().charAt(0) + c.type.name().substring(1).toLowerCase()
                        + " · size " + c.size + " · " + (c.palette.isEmpty() ? "no blocks yet" : c.palette.size() + " palette entries")
                        + (c.protect.isEmpty() ? "" : " · " + c.protect.size() + " protected")))
                .build());
    }

    private static void typeBtn(SimpleGui gui, ServerPlayer sp, ItemStack brush, int slot, Item icon,
                                DraftBrush.Type type, String name, String lore) {
        DraftBrush.Config c = DraftBrush.read(brush);
        boolean sel = c.type == type;
        gui.setSlot(slot, new GuiElementBuilder(icon)
                .setName(Component.literal((sel ? "▶ " : "") + name + (sel ? " (selected)" : "")))
                .addLoreLine(Component.literal(lore))
                .setCallback((i, t, a, g) -> { c.type = type; save(sp, brush, c); render(gui, sp); }).build());
    }

    /** The brush being edited: main hand first, then offhand. */
    private static ItemStack heldBrush(ServerPlayer sp) {
        if (DraftBrush.isBrush(sp.getMainHandItem())) return sp.getMainHandItem();
        if (DraftBrush.isBrush(sp.getOffhandItem())) return sp.getOffhandItem();
        return null;
    }

    private static void save(ServerPlayer sp, ItemStack brush, DraftBrush.Config c) {
        DraftBrush.write(brush, c);
    }

    private static String pretty(String id) {
        String n = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return n.replace('_', ' ');
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
