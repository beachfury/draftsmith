package com.draftsmith.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Standalone settings (config/draftsmith.properties), applied with /draft reload. These only
 * matter for the built-in ops-only access provider — a host mod that installs its own
 * {@link com.draftsmith.api.EditAccess} makes its own rules and ignores this file.
 *
 * Ship safe: on a survival server the editor is a grief cannon without a gate, so the default
 * is ops only, everywhere, and every extra editor or world is an explicit admin choice.
 */
public final class DraftConfig {
    // Defaults double as the documented values written to a fresh file.
    /** Extra (non-op) player names allowed to use the editor. Comma-separated, case-insensitive. */
    public static volatile Set<String> editors = Set.of();
    /** Dimensions the editor works in. Comma-separated ids ("minecraft:overworld"); blank = all. */
    public static volatile Set<String> worlds = Set.of();

    private static Path file;

    private DraftConfig() {}

    public static boolean isEditor(String playerName) {
        return editors.contains(playerName.toLowerCase(Locale.ROOT));
    }

    public static boolean worldEnabled(String dimensionId) {
        return worlds.isEmpty() || worlds.contains(dimensionId);
    }

    /** Load (creating a default file if absent). Safe to call again for /draft reload. */
    public static void load() {
        if (file == null) file = FabricLoader.getInstance().getConfigDir().resolve("draftsmith.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { p.load(in); }
            catch (Exception e) { System.err.println("[DraftSmith] Failed to read config: " + e); }
        }
        editors = split(p.getProperty("editors", ""));
        worlds = split(p.getProperty("worlds", ""));
        save(); // always rewrite so keys added in a mod update show up in the file (values are preserved)
    }

    public static void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("editors", String.join(",", editors));
        p.setProperty("worlds", String.join(",", worlds));
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                p.store(out, "DraftSmith — ops always have the editor; 'editors' adds non-op names; 'worlds' limits it to listed dimension ids (blank = all)");
            }
        } catch (Exception e) { System.err.println("[DraftSmith] Failed to write config: " + e); }
    }

    private static Set<String> split(String csv) {
        Set<String> out = new java.util.HashSet<>();
        for (String s : csv.split(",")) if (!s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }
}
