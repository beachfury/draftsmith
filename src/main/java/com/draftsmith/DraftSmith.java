package com.draftsmith;

import com.draftsmith.command.DraftCommands;
import com.draftsmith.config.DraftConfig;
import com.draftsmith.edit.DraftBrush;
import com.draftsmith.edit.DraftEdit;
import com.draftsmith.gui.DraftBrushGui;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * DraftSmith — a lightweight server-side building editor: GUI shapes, paint brushes, measuring
 * tools, clipboard and undo. Works for Bedrock players through Geyser with zero client installs.
 *
 * Standalone it is ops-only by default (config/draftsmith.properties opens it up). A host mod
 * can instead install its own {@link com.draftsmith.api.EditAccess} to jail edits to its rules
 * and hang the same commands under its own root — see {@link com.draftsmith.api.DraftSmithApi}.
 */
public final class DraftSmith implements ModInitializer {

    @Override
    public void onInitialize() {
        // Live, admin-editable settings (config/draftsmith.properties), reloaded by /draft reload.
        DraftConfig.load();

        // Editor wand (selection) — registered early so it can swallow its own clicks before any
        // protection mod that initializes after us (hosts depend on us, so we always come first).
        DraftEdit.register();
        // Paint brushes + the end-of-tick GUI queue and particle previews.
        DraftBrush.register(DraftBrushGui::open);

        // /draft … (works for Bedrock players via Geyser — they just type it).
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                DraftCommands.register(dispatcher, access));
    }
}
