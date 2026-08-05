package com.draftsmith.command;

import com.draftsmith.api.DraftSmithApi;
import com.draftsmith.api.EditAccess;
import com.draftsmith.config.DraftConfig;
import com.draftsmith.edit.DraftBrush;
import com.draftsmith.edit.DraftEdit;
import com.draftsmith.edit.DraftMeasure;
import com.draftsmith.edit.DraftShapes;
import com.draftsmith.gui.DraftEditGui;
import com.draftsmith.gui.DraftMeasureGui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The editor commands: wand, brush, selection, set/replace, clipboard, shapes and the measuring
 * tape. Standalone they live under the /draft root ({@link #register}); a host mod can instead
 * chain the exact same nodes onto its own root with {@link #attach} — the handlers are shared,
 * only the root differs.
 */
public final class DraftCommands {
    private DraftCommands() {}

    private static EditAccess access() { return DraftSmithApi.access(); }

    /** Standalone registration: the /draft root. Hidden entirely from players who can't use it. */
    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext bc) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("draft")
                .requires(src -> src.getPlayer() != null && access().canUse(src.getPlayer()))
                .executes(DraftCommands::editGui) // bare /draft opens the editor hub
                // The selection wand is DraftSmith's own tool — /draft wand only, never attached
                // to a host root ("editwand" kept as a quiet alias for FabricPlots muscle memory).
                .then(Commands.literal("wand").executes(DraftCommands::editwand))
                .then(Commands.literal("editwand").executes(DraftCommands::editwand))
                .then(Commands.literal("help").executes(DraftCommands::help))
                .then(Commands.literal("reload")
                        .requires(src -> src.getPlayer() == null || access().isAdmin(src.getPlayer()))
                        .executes(ctx -> {
                            DraftConfig.load();
                            msg(ctx, "Config reloaded.");
                            return 1;
                        }));
        attach(root, bc);
        d.register(root);
    }

    /** Chain every editor subcommand onto any root builder (call before dispatcher.register). */
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext bc) {
        attach(root, bc, java.util.Set.of());
    }

    /**
     * Chain the editor subcommands onto any root builder, skipping the named ones — for hosts
     * that keep some tools under DraftSmith's own root instead of duplicating them (e.g.
     * {@code attach(root, bc, Set.of("brush"))}). Skipping "tape" removes "tape clear" with it.
     * The wand-getting command is NOT part of this set — it is /draft wand, DraftSmith's own.
     */
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext bc,
                              java.util.Set<String> skip) {
        java.util.function.Consumer<LiteralArgumentBuilder<CommandSourceStack>> add =
                node -> { if (!skip.contains(node.getLiteral())) root.then(node); };
        add.accept(Commands.literal("brush").executes(DraftCommands::brush));
        add.accept(Commands.literal("pos1").executes(DraftCommands::pos1));
        add.accept(Commands.literal("pos2").executes(DraftCommands::pos2));
        add.accept(Commands.literal("set")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .executes(DraftCommands::setBlocks)));
        add.accept(Commands.literal("replace")
                .then(Commands.argument("from", BlockStateArgument.block(bc))
                        .then(Commands.argument("to", BlockStateArgument.block(bc))
                                .executes(DraftCommands::replaceBlocks))));
        add.accept(Commands.literal("edit").executes(DraftCommands::editGui));
        add.accept(Commands.literal("measure").executes(DraftCommands::measureGui));
        add.accept(Commands.literal("undo").executes(DraftCommands::undoEdit));
        add.accept(Commands.literal("redo").executes(DraftCommands::redoEdit));
        add.accept(Commands.literal("copy").executes(DraftCommands::copyEdit));
        add.accept(Commands.literal("cut").executes(DraftCommands::cutEdit));
        add.accept(Commands.literal("paste").executes(DraftCommands::pasteEdit));
        add.accept(Commands.literal("stack")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> stackEdit(ctx, IntegerArgumentType.getInteger(ctx, "count")))));
        add.accept(Commands.literal("move")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                        .executes(ctx -> moveEdit(ctx, IntegerArgumentType.getInteger(ctx, "count")))));
        add.accept(Commands.literal("walls")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .executes(DraftCommands::wallsEdit)));
        add.accept(Commands.literal("sphere")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> sphereEdit(ctx, false)))));
        add.accept(Commands.literal("hsphere")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> sphereEdit(ctx, true)))));
        add.accept(Commands.literal("cyl")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> cylEdit(ctx, 1))
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> cylEdit(ctx, IntegerArgumentType.getInteger(ctx, "height")))))));
        add.accept(Commands.literal("disc")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> shapeEdit(ctx, DraftShapes.Shape.CIRCLE, false, 1))
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 128))
                                        .executes(ctx -> shapeEdit(ctx, DraftShapes.Shape.CIRCLE, false,
                                                IntegerArgumentType.getInteger(ctx, "height")))))));
        add.accept(Commands.literal("ring")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> shapeEdit(ctx, DraftShapes.Shape.CIRCLE, true, 1))
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 128))
                                        .executes(ctx -> shapeEdit(ctx, DraftShapes.Shape.CIRCLE, true,
                                                IntegerArgumentType.getInteger(ctx, "height")))))));
        add.accept(Commands.literal("line")
                .then(Commands.argument("block", BlockStateArgument.block(bc))
                        .executes(ctx -> lineEdit(ctx, 1))
                        .then(Commands.argument("thickness", IntegerArgumentType.integer(1, 8))
                                .executes(ctx -> lineEdit(ctx, IntegerArgumentType.getInteger(ctx, "thickness"))))));
        add.accept(Commands.literal("center").executes(DraftCommands::centerEdit));
        add.accept(Commands.literal("tape")
                .executes(DraftCommands::tapeEdit)
                .then(Commands.literal("clear").executes(DraftCommands::tapeClear)));
    }

    // ---- guards ----------------------------------------------------------

    /**
     * The player, after the editor gate: allowed to use the editor AND standing in an active
     * dimension. Null (with the reason already messaged) when either fails.
     */
    private static ServerPlayer editor(CommandContext<CommandSourceStack> ctx) throws Exception {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        if (!access().canUse(p)) { msg(ctx, "You don't have permission to use the editor."); return null; }
        if (!access().isActiveDimension(p.level())) { msg(ctx, access().notHereMessage()); return null; }
        return p;
    }

    /** Same gate minus the dimension check — for handing out tools (usable from anywhere). */
    private static ServerPlayer user(CommandContext<CommandSourceStack> ctx) throws Exception {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        if (!access().canUse(p)) { msg(ctx, "You don't have permission to use the editor."); return null; }
        return p;
    }

    // ---- handlers --------------------------------------------------------

    private static int brush(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = user(ctx);
            if (p == null) return 0;
            p.getInventory().placeItemBackInInventory(DraftBrush.createBrush());
            msg(ctx, "Paint brush added. Sneak + right-click to configure it, right-click to paint.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int editwand(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = user(ctx);
            if (p == null) return 0;
            p.addItem(DraftEdit.createWand());
            msg(ctx, "Wand given. Right-click a block for corner 1, right-click again for corner 2 (or use /draft pos1 · /draft pos2). Then /draft set <block> or /draft replace <from> <to>.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int pos1(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = user(ctx);
            if (p == null) return 0;
            DraftEdit.setPos1(p);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int pos2(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = user(ctx);
            if (p == null) return 0;
            DraftEdit.setPos2(p);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int setBlocks(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            var block = BlockStateArgument.getBlock(ctx, "block");
            return DraftEdit.set(p, (ServerLevel) p.level(), block.getState());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int replaceBlocks(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            var from = BlockStateArgument.getBlock(ctx, "from");
            var to = BlockStateArgument.getBlock(ctx, "to");
            return DraftEdit.replace(p, (ServerLevel) p.level(), from, to.getState());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int undoEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.undo(p, (ServerLevel) p.level());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int redoEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.redo(p, (ServerLevel) p.level());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int editGui(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            DraftEditGui.open(p);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int measureGui(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            DraftMeasureGui.open(p);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int wallsEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.walls(p, (ServerLevel) p.level(), BlockStateArgument.getBlock(ctx, "block").getState());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int sphereEdit(CommandContext<CommandSourceStack> ctx, boolean hollow) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            int r = IntegerArgumentType.getInteger(ctx, "radius");
            return DraftEdit.sphere(p, (ServerLevel) p.level(), BlockStateArgument.getBlock(ctx, "block").getState(), r, hollow);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int cylEdit(CommandContext<CommandSourceStack> ctx, int height) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            int r = IntegerArgumentType.getInteger(ctx, "radius");
            return DraftEdit.cylinder(p, (ServerLevel) p.level(), BlockStateArgument.getBlock(ctx, "block").getState(), r, height);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int shapeEdit(CommandContext<CommandSourceStack> ctx, DraftShapes.Shape shape, boolean hollow, int height) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            int size = IntegerArgumentType.getInteger(ctx, "size");
            return DraftShapes.buildShape(p, (ServerLevel) p.level(), BlockStateArgument.getBlock(ctx, "block").getState(),
                    shape, hollow, size, height, 1, 1, 0);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int lineEdit(CommandContext<CommandSourceStack> ctx, int thickness) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftShapes.line(p, (ServerLevel) p.level(), BlockStateArgument.getBlock(ctx, "block").getState(), thickness);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int centerEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftShapes.findLineCenter(p, (ServerLevel) p.level());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int tapeEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftMeasure.tape(p, (ServerLevel) p.level());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int tapeClear(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            DraftMeasure.clearTape(p, (ServerLevel) p.level());
            msg(ctx, "Measuring tape cleared.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int copyEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.copy(p, (ServerLevel) p.level());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int cutEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.cut(p, (ServerLevel) p.level());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int pasteEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.paste(p, (ServerLevel) p.level());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int stackEdit(CommandContext<CommandSourceStack> ctx, int count) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.stack(p, (ServerLevel) p.level(), count);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int moveEdit(CommandContext<CommandSourceStack> ctx, int count) {
        try {
            ServerPlayer p = editor(ctx);
            if (p == null) return 0;
            return DraftEdit.move(p, (ServerLevel) p.level(), count);
        } catch (Exception e) { return err(ctx, e); }
    }

    // ---- help ------------------------------------------------------------

    private static final int CLR_TEAL = 0x1ABC9C, CLR_YELLOW = 0xFFE066, CLR_PURPLE = 0xC792EA;

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("DraftSmith").withStyle(s -> s.withColor(CLR_TEAL))
                .append(Component.literal(" — build tools (click a command to fill it in)").withStyle(s -> s.withColor(CLR_PURPLE))), false);

        section(src, "Tools");
        line(src, "/draft", "open the editor hub — every tool below as a GUI");
        line(src, "/draft wand", "get the selection wand (right-click corners 1 / 2)");
        line(src, "/draft brush", "get a paint brush (sneak + right-click configures it)");

        section(src, "Selection & clipboard");
        line(src, "/draft pos1", "set corner 1 where you stand");
        line(src, "/draft pos2", "set corner 2 where you stand");
        line(src, "/draft copy", "copy the selection");
        line(src, "/draft cut", "copy, then clear the selection");
        line(src, "/draft paste", "paste where you aim");
        line(src, "/draft stack <count>", "repeat the selection along your facing");
        line(src, "/draft move <count>", "shift the selection along your facing");

        section(src, "Editing");
        line(src, "/draft set <block>", "fill the selection");
        line(src, "/draft replace <from> <to>", "replace only matching blocks");
        line(src, "/draft walls <block>", "perimeter walls around the selection");
        line(src, "/draft undo", "step the last edit back (10 deep)");
        line(src, "/draft redo", "step forward again");

        section(src, "Shapes");
        line(src, "/draft sphere <block> <radius>", "sphere where you stand");
        line(src, "/draft hsphere <block> <radius>", "hollow sphere");
        line(src, "/draft cyl <block> <radius> [height]", "cylinder");
        line(src, "/draft disc <block> <size> [height]", "flat disc");
        line(src, "/draft ring <block> <size> [height]", "ring");
        line(src, "/draft line <block> [thickness]", "3D line, corner 1 → corner 2");

        section(src, "Measuring");
        line(src, "/draft measure", "open the measuring screen");
        line(src, "/draft center", "gold-mark the middle of the corner 1 → corner 2 line");
        line(src, "/draft tape", "lay a numbered measuring tape between the corners");
        line(src, "/draft tape clear", "remove your tapes (restores what they covered)");

        ServerPlayer p = src.getPlayer();
        if (p == null || access().isAdmin(p)) {
            section(src, "Admin");
            line(src, "/draft reload", "reload config/draftsmith.properties");
        }
        return 1;
    }

    private static void section(CommandSourceStack src, String title) {
        src.sendSuccess(() -> Component.literal(title).withStyle(s -> s.withColor(CLR_PURPLE).applyFormat(net.minecraft.ChatFormatting.BOLD)), false);
    }

    private static void line(CommandSourceStack src, String command, String desc) {
        int cut = command.length();
        int lt = command.indexOf('<'), br = command.indexOf('[');
        if (lt >= 0) cut = lt;
        if (br >= 0 && br < cut) cut = br;
        final String suggest = command.substring(0, cut); // drop <args>/[args] from what gets typed
        src.sendSuccess(() -> Component.literal("  " + command)
                .withStyle(s -> s.withColor(CLR_YELLOW).withClickEvent(com.draftsmith.compat.Compat.suggestCommand(suggest)))
                .append(Component.literal("   " + desc).withStyle(s -> s.withColor(CLR_PURPLE))), false);
    }

    // ---- helpers ---------------------------------------------------------

    private static void msg(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(access().messagePrefix() + text), false);
    }

    private static int err(CommandContext<CommandSourceStack> ctx, Exception e) {
        ctx.getSource().sendFailure(Component.literal(access().messagePrefix() + "Error: " + e.getMessage()));
        return 0;
    }
}
