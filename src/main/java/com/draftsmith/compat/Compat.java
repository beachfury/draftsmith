package com.draftsmith.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * The version seam. Everything that tends to change between Minecraft versions funnels through
 * this package, so porting to another version means re-implementing THIS class — not hunting the
 * codebase. Known differences are noted per method.
 */
public final class Compat {
    private Compat() {}

    // ---- registry lookups --------------------------------------------------
    // 26.x: Identifier + Registry.getValue. 1.21.1: ResourceLocation + Registry.get.
    // Always address blocks/items by REGISTRY ID in shared code — 26.2 moved the per-color
    // constants (LIME_CONCRETE etc.) into ColorCollection, but ids never changed.

    /** Raw block lookup by registry id (returns the registry default on unknown ids). */
    public static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    /** Raw item lookup by registry id (returns the registry default on unknown ids). */
    public static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    // ---- sign text (measuring tape numbers) --------------------------------
    // SignText API is stable since 1.20, but setText/getText signatures have moved before.

    /** Write {@code text} on both faces of the sign at pos — black dye + glow ink, waxed. */
    public static void labelSign(ServerLevel level, BlockPos pos, String text) {
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sbe) {
            Component c = Component.literal(text);
            sbe.setText(sbe.getText(true).setMessage(1, c)
                    .setColor(DyeColor.BLACK).setHasGlowingText(true), true);
            sbe.setText(sbe.getText(false).setMessage(1, c)
                    .setColor(DyeColor.BLACK).setHasGlowingText(true), false);
            sbe.setWaxed(true); // nobody should be able to edit the numbers
            sbe.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_CLIENTS);
        }
    }
}
