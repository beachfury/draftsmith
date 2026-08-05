package com.draftsmith.api;

import com.draftsmith.config.DraftConfig;

/**
 * Holder for the active {@link EditAccess} provider. DraftSmith installs its ops-only default
 * at init; a host mod that declares {@code "depends": {"draftsmith": "*"}} initializes after
 * DraftSmith (Fabric loads dependencies first) and replaces it from its own initializer.
 */
public final class DraftSmithApi {
    private static EditAccess access;

    private DraftSmithApi() {}

    public static EditAccess access() {
        if (access == null) access = new DefaultAccess(); // events can fire before init in tests
        return access;
    }

    /** Replace the access provider — call from the host mod's initializer. */
    public static void setAccess(EditAccess a) {
        if (a == null) throw new IllegalArgumentException("access must not be null");
        access = a;
    }

    /** The standalone default: ops (or configured editors) only, whole-world edits, no ground. */
    public static final class DefaultAccess implements EditAccess {
        @Override
        public boolean isActiveDimension(net.minecraft.world.level.Level level) {
            return DraftConfig.worldEnabled(level.dimension().location().toString()); // 26.x: .identifier()
        }

        /** Ops + the config editors list — what decides when no permissions mod defines a node. */
        private boolean fallback(net.minecraft.server.level.ServerPlayer p) {
            return vanillaAdmin(p) || DraftConfig.isEditor(p.getName().getString());
        }

        @Override
        public boolean canUse(net.minecraft.server.level.ServerPlayer p) {
            return PermBridge.check(p, "draftsmith.use", fallback(p));
        }

        @Override
        public boolean canUse(net.minecraft.server.level.ServerPlayer p, Tool tool) {
            return canUse(p) && PermBridge.check(p, "draftsmith." + tool.name().toLowerCase(java.util.Locale.ROOT), fallback(p));
        }

        @Override
        public boolean isAdmin(net.minecraft.server.level.ServerPlayer p) {
            return PermBridge.check(p, "draftsmith.admin", vanillaAdmin(p));
        }

        /**
         * True for any op/admin. Reads the player's EFFECTIVE permission (set for the
         * single-player host, LAN host, and dedicated-server ops alike) rather than
         * ops.json / profile identity, which are unreliable in single-player.
         */
        private boolean vanillaAdmin(net.minecraft.server.level.ServerPlayer p) {
            var server = p.level().getServer();
            if (server == null) return false;
            if (server.isSingleplayer()) return true;
            // 1.21.1: permission-level check. (26.x: p.permissions().hasPermission(COMMANDS_GAMEMASTER)
            // + nameAndId()-based owner/op lookups.)
            if (p.hasPermissions(2)) return true;
            return server.isSingleplayerOwner(p.getGameProfile()) || server.getPlayerList().isOp(p.getGameProfile());
        }

        @Override
        public boolean canEdit(net.minecraft.server.level.ServerPlayer p, boolean admin, int x, int y, int z) {
            if (!(p.level() instanceof net.minecraft.server.level.ServerLevel level)) return false;
            if (!canUse(p)) return false;
            return y >= minY(level) && y <= maxY(level);
        }
    }
}
