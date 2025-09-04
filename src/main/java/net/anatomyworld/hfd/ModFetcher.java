package net.anatomyworld.hfd;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public class ModFetcher extends Main {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    // Modrinth asks for a unique, identifying UA (include contact if possible).
    private static final String UA = "HFD-Installer/1.3 (+https://harambefinaldestination.world)";

    /** Top-level: read /embedded/mods.fetch.json and fetch everything. */
    public void fetchAll(Path modsDir, String mcVersion, Installer.Log log) {
        List<ModRule> rules = loadRulesFromConfig(mcVersion, log);
        if (rules.isEmpty()) {
            log.line("(No mods.fetch.json found — skipping external mods)");
            return;
        }

        for (ModRule rule : rules) {
            try {
                // optional cleanup per rule
                for (Pattern p : rule.cleanupPatterns) {
                    deleteMatching(modsDir, p, log);
                }
                Path placed = rule.fetchTo(modsDir, log);
                log.line(rule.displayName + " placed: " + placed.getFileName());
            } catch (Exception ex) {
                log.line(rule.displayName + " download failed: " + ex.getMessage());
            }
        }
    }

    // ----------- Config ------------

    private List<ModRule> loadRulesFromConfig(String mcVersion, Installer.Log log) {
        try (InputStream in = Main.class.getResourceAsStream("/embedded/mods.fetch.json")) {
            if (in == null) return List.of();
            JsonNode arr = JSON.readTree(in);
            if (arr == null || !arr.isArray() || arr.isEmpty()) return List.of();

            List<ModRule> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String name = n.path("name").asText("Unknown Mod");

                // cleanup patterns (optional)
                List<Pattern> cleanup = new ArrayList<>();
                for (JsonNode rx : n.withArray("cleanup")) {
                    try { cleanup.add(Pattern.compile(rx.asText())); } catch (Exception ignored) {}
                }

                // Build strategy pipeline from sources[]
                List<Strategy> strategies = new ArrayList<>();
                for (JsonNode s : n.withArray("sources")) {
                    String type = s.path("type").asText("");
                    switch (type) {
                        case "modrinth_exact" -> strategies.add(Strategy.modrinthExact(
                                s.path("slug").asText(),
                                expand(s.path("version").asText(), mcVersion),
                                s.path("requireLoader").asText("neoforge")));
                        case "modrinth_filtered" -> strategies.add(Strategy.modrinthFilteredLatest(
                                s.path("slug").asText(),
                                s.path("loader").asText("neoforge"),
                                expand(s.path("mc").asText(mcVersion), mcVersion),
                                s.path("requireLoader").asText("neoforge")));
                        case "direct" -> strategies.add(Strategy.direct(
                                expand(s.path("url").asText(), mcVersion),
                                s.path("filename").asText(null),
                                s.path("sha512").asText(null),
                                s.path("expectFilenameContains").asText(null)));
                        default -> log.line("Unknown source type: " + type + " (skip)");
                    }
                }

                if (!strategies.isEmpty())
                    out.add(new ModRule(name, cleanup, strategies));
            }
            return out;
        } catch (Exception e) {
            log.line("Failed to read mods.fetch.json: " + e.getMessage());
            return List.of();
        }
    }

    private static String expand(String s, String mc) {
        return (s == null) ? null : s.replace("${mc}", mc);
    }

    // ----------- Model ------------

    private record ModRule(String displayName, List<Pattern> cleanupPatterns, List<Strategy> pipeline) {
        Path fetchTo(Path modsDir, Installer.Log log) throws Exception {
            for (Strategy s : pipeline) {
                try {
                    Path p = s.tryFetch(modsDir, log);
                    if (p != null) return p;
                } catch (Exception ex) {
                    log.line(displayName + " strategy failed (" + s.name() + "): " + ex.getMessage());
                }
            }
            throw new IOException("No valid source found for " + displayName);
        }
    }

    private interface Strategy {
        String name();
        Path tryFetch(Path modsDir, Installer.Log log) throws Exception;

        static Strategy modrinthExact(String slug, String versionNumber, String requiredLoader) {
            return new ModrinthExact(slug, versionNumber, requiredLoader);
        }
        static Strategy modrinthFilteredLatest(String slug, String loader, String mc, String requiredLoader) {
            return new ModrinthFiltered(slug, loader, mc, requiredLoader);
        }
        static Strategy direct(String url, String filename, String sha512, String expectContains) {
            return new Direct(url, filename, sha512, expectContains);
        }
    }

    // ----------- Strategies -----------

    /** Modrinth exact version: GET /v2/project/{slug}/version/{number} (official). */
    private static final class ModrinthExact implements Strategy {
        private final String slug, versionNumber, requiredLoader;
        ModrinthExact(String slug, String versionNumber, String requiredLoader) {
            this.slug = slug; this.versionNumber = versionNumber; this.requiredLoader = requiredLoader;
        }
        public String name() { return "modrinthExact(" + versionNumber + ")"; }

        @Override public Path tryFetch(Path modsDir, Installer.Log log) throws Exception {
            JsonNode node = getJson("https://api.modrinth.com/v2/project/" + slug + "/version/" + versionNumber);
            if (!arrayContainsIgnoreCase(node.withArray("loaders"), requiredLoader))
                throw new IOException("Not the required loader for version " + versionNumber);

            FileInfo f = chooseNeoForgeFile(node.withArray("files"));
            if (f == null) throw new IOException("No NeoForge JAR in exact version: " + versionNumber);

            return safeDownloadTo(f.url, modsDir.resolve(f.filename), f.sha512, log);
        }
    }

    /** Modrinth filtered list: GET /v2/project/{slug}/version?loaders=&game_versions=. */
    private static final class ModrinthFiltered implements Strategy {
        private final String slug, loader, mc, requiredLoader;
        ModrinthFiltered(String slug, String loader, String mc, String requiredLoader) {
            this.slug = slug; this.loader = loader; this.mc = mc; this.requiredLoader = requiredLoader;
        }
        public String name() { return "modrinthFilteredLatest(" + loader + "," + mc + ")"; }

        @Override public Path tryFetch(Path modsDir, Installer.Log log) throws Exception {
            String url = "https://api.modrinth.com/v2/project/" + slug + "/version"
                    + "?loaders=%5B%22" + enc(loader) + "%22%5D"
                    + "&game_versions=%5B%22" + enc(mc) + "%22%5D";
            JsonNode arr = getJson(url);
            if (!arr.isArray() || arr.isEmpty()) throw new IOException("No results");

            JsonNode newest = null;
            for (JsonNode v : arr) {
                if (!arrayContainsIgnoreCase(v.withArray("loaders"), requiredLoader)) continue;
                if (newest == null || Instant.parse(v.get("date_published").asText())
                        .isAfter(Instant.parse(newest.get("date_published").asText()))) {
                    newest = v;
                }
            }
            if (newest == null) throw new IOException("No version with loader " + requiredLoader);

            FileInfo f = chooseNeoForgeFile(newest.withArray("files"));
            if (f == null) throw new IOException("No NeoForge JAR in files[]");

            return safeDownloadTo(f.url, modsDir.resolve(f.filename), f.sha512, log);
        }
    }

    /** Direct URL fallback (legal CDN or your own distribution). */
    private static final class Direct implements Strategy {
        private final String url, filename, sha512, expectContains;
        Direct(String url, String filename, String sha512, String expectContains) {
            this.url = url; this.filename = filename; this.sha512 = sha512; this.expectContains = expectContains;
        }
        public String name() { return "direct(" + url + ")"; }

        @Override public Path tryFetch(Path modsDir, Installer.Log log) throws Exception {
            String fn = (filename == null || filename.isBlank())
                    ? lastSegment(URI.create(url).getPath()) : filename;
            if (expectContains != null && !fn.toLowerCase(Locale.ROOT).contains(expectContains.toLowerCase(Locale.ROOT)))
                throw new IOException("Unexpected filename: " + fn);

            return safeDownloadTo(url, modsDir.resolve(fn), sha512, log);
        }
    }

    // ----------- HTTP / IO (robust writes) -----------

    private static JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new IOException("HTTP " + res.statusCode() + " @ " + url + ": " + res.body());
        return JSON.readTree(res.body());
    }

    /** Download to memory → write temp → atomic move. Retries x3. */
    private static Path safeDownloadTo(String url, Path finalPath, String expectedSha512, Installer.Log log)
            throws Exception {
        Files.createDirectories(finalPath.getParent());
        String base = finalPath.getFileName().toString();

        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Path tmp = null;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).GET().build();
                HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() / 100 != 2)
                    throw new IOException("Download HTTP " + res.statusCode() + " @ " + url);

                // temp in target dir; if blocked, use system temp
                try {
                    tmp = Files.createTempFile(finalPath.getParent(), "dl-", ".tmp");
                    Files.write(tmp, res.body(), StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException ioInMods) {
                    tmp = Files.createTempFile(Paths.get(System.getProperty("java.io.tmpdir")), "dl-", ".tmp");
                    Files.write(tmp, res.body(), StandardOpenOption.TRUNCATE_EXISTING);
                }

                if (expectedSha512 != null && !expectedSha512.isBlank()) verifySha512(tmp, expectedSha512);

                try {
                    Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
                }
                return finalPath;
            } catch (IOException io) {
                last = io;
                log.line("Download hiccup (" + io.getMessage() + "), retrying in " + (attempt * 800L) + "ms…");
                Thread.sleep(attempt * 800L);
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }
        }
        throw last != null ? last : new IOException("Unknown download error for " + base);
    }

    private static void verifySha512(Path file, String expected) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] got = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : got) sb.append(String.format("%02x", b));
        if (!sb.toString().equalsIgnoreCase(expected))
            throw new IOException("SHA-512 mismatch for " + file.getFileName());
    }

    private static boolean arrayContainsIgnoreCase(JsonNode arr, String needle) {
        for (JsonNode n : arr) if (needle.equalsIgnoreCase(n.asText())) return true;
        return false;
    }

    private record FileInfo(String url, String filename, String sha512) {}
    private static FileInfo chooseNeoForgeFile(JsonNode files) {
        // Prefer filenames clearly marked neoforge, avoid fabric; else primary; else first.
        JsonNode primary = null, first = null, named = null;
        for (JsonNode f : files) {
            String fn = f.path("filename").asText("");
            if (first == null) first = f;
            if (f.path("primary").asBoolean(false)) primary = f;
            String fnLower = fn.toLowerCase(Locale.ROOT);
            if (fnLower.contains("neoforge") && !fnLower.contains("fabric")) named = f;
        }
        JsonNode pick = named != null ? named : (primary != null ? primary : first);
        if (pick == null) return null;
        String url = pick.get("url").asText();
        String filename = pick.get("filename").asText();
        String sha = pick.with("hashes").path("sha512").asText(null);
        return new FileInfo(url, filename, sha);
    }

    private static void deleteMatching(Path dir, Pattern p, Installer.Log log) {
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(f -> p.matcher(f.getFileName().toString()).find())
                    .forEach(f -> { try { Files.deleteIfExists(f); log.line("Deleted old: " + f.getFileName()); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private static String enc(String s) { return s.replace(" ", "%20"); }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return (i >= 0) ? path.substring(i + 1) : path;
    }
}
