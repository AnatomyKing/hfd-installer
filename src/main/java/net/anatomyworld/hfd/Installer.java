package net.anatomyworld.hfd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class Installer extends Main {

    public interface Log { void line(String s); }

    public void runInstall(Path mc, Log log) throws Exception {
        log.line("Minecraft dir: " + mc + "\n(Close the Minecraft Launcher before installing.)");

        // 1) Ensure NeoForge base exists (run embedded installer if needed)
        String neo = findNeoForgeId(mc);
        if (neo == null || (REQUIRED_NEOFORGE_ID != null && !REQUIRED_NEOFORGE_ID.equals(neo))) {
            if (neo != null) log.line("Found '" + neo + "' but require '" + REQUIRED_NEOFORGE_ID + "'. Reinstalling…");
            log.line("Running embedded NeoForge installer…");
            Path inst = locateBundledInstaller(log);
            if (inst == null) throw new IllegalStateException("No embedded neoforge-installer.jar.bin found in the installer JAR.");
            runInstallerJar(inst, log);
            neo = findNeoForgeId(mc);
            if (neo == null || (REQUIRED_NEOFORGE_ID != null && !REQUIRED_NEOFORGE_ID.equals(neo))) {
                throw new IllegalStateException("NeoForge not detected after running installer.");
            }
        }
        log.line("Using base: " + neo);

        // 2) Create child version that inherits from NeoForge
        Path childDir = mc.resolve("versions").resolve(CHILD_VERSION_ID);
        Files.createDirectories(childDir);
        var child = JSON.createObjectNode();
        child.put("id", CHILD_VERSION_ID);
        child.put("inheritsFrom", neo);
        child.put("type", "release");
        Path childJson = childDir.resolve(CHILD_VERSION_ID + ".json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(childJson.toFile(), child);
        log.line("Wrote " + childJson);

        // 3) Prepare gameDir + extract embedded mods
        Path gameDir = mc.resolve(GAME_DIR_NAME);
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        extractEmbeddedMods(modsDir, log);

        // 3b) Fetch all external mods purely from config
        new ModFetcher().fetchAll(modsDir, TARGET_MC_VERSION, log);

        // 3c) Pre-seed client options so players don’t have to
        ensureClientOptions(gameDir, log);

        // 4) Update launcher profiles (set our profile + icon; remove NeoForge auto-profile)
        Path profilesPath = detectLauncherProfilesFile(mc);
        pruneProfilesUsingVersion(profilesPath, neo, log);
        upsertHfdProfile(profilesPath, gameDir, log);

        // 5) Create Multiplayer server list (servers.dat) in our gameDir
        Path serversDat = gameDir.resolve("servers.dat");
        writeServersDat(serversDat, "HarambeFD", "harambefinaldestination.world", true);
        log.line("Wrote servers.dat with HarambeFD.");

        log.line("Done (" + Instant.now() + ")!");
    }

    // ---------------------------------------------------------------------
    // options.txt pre-seeding
    // ---------------------------------------------------------------------

    /**
     * Ensures options.txt contains:
     *  - narrator: 0   (Off)
     *  - soundCategory_music: 0.25  (25% music volume)
     *  - skipMultiplayerWarning: true  (don't show the online-play warning)
     */
    private void ensureClientOptions(Path gameDir, Log log) {
        Path options = gameDir.resolve("options.txt");
        try {
            Map<String, String> kv = readOptions(options);

            // Apply required values
            kv.put("narrator", "0");                // off
            kv.put("soundCategory_music", "0.25");  // 25%
            kv.put("skipMultiplayerWarning", "true");

            writeOptions(options, kv);
            log.line("Pre-seeded options: narrator=0, music=0.25, skipMultiplayerWarning=true → " + options);
        } catch (Exception e) {
            log.line("Could not write options.txt: " + e.getMessage());
        }
    }

    private Map<String, String> readOptions(Path options) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (Files.exists(options)) {
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                int i = line.indexOf(':');
                if (i <= 0) continue;
                String key = line.substring(0, i).trim();
                String val = line.substring(i + 1).trim();
                if (!key.isEmpty()) map.put(key, val);
            }
        }
        return map;
    }

    private void writeOptions(Path options, Map<String, String> kv) throws IOException {
        List<String> lines = new ArrayList<>(kv.size());
        for (Map.Entry<String, String> e : kv.entrySet()) {
            lines.add(e.getKey() + ":" + e.getValue());
        }
        Files.createDirectories(options.getParent());
        Files.write(options, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
