package com.draftsmith.api;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soft bridge to fabric-permissions-api (the check API LuckPerms and friends implement).
 * When the API mod isn't installed the given fallback decides — so small servers keep the
 * ops + editors-list behavior with zero extra mods. The inner holder keeps me.lucko classes
 * from loading unless the mod is actually present.
 */
public final class PermBridge {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0");

    private PermBridge() {}

    /** True/false from the permissions mod when it defines {@code node}; otherwise {@code fallback}. */
    public static boolean check(ServerPlayer p, String node, boolean fallback) {
        return LOADED ? Impl.check(p, node, fallback) : fallback;
    }

    private static final class Impl {
        static boolean check(ServerPlayer p, String node, boolean fallback) {
            return me.lucko.fabric.api.permissions.v0.Permissions.check(p, node, fallback);
        }
    }
}
