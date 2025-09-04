package net.anatomyworld.hfd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Main {

    // ======== CONFIGURE HERE ========
    protected static final String TARGET_MC_VERSION = "1.21.8";
    protected static final String REQUIRED_NEOFORGE_ID = "neoforge-21.8.39"; // base we inherit from
    protected static final String CHILD_VERSION_ID  = "HFD-" + TARGET_MC_VERSION; // child version id
    protected static final String PROFILE_NAME      = "HFD";
    protected static final String GAME_DIR_NAME     = "HFD";

    protected static final String ICON_RESOURCE_CLZ = "/embedded/icon.png";
    protected static final String ICON_RESOURCE_CL  = "embedded/icon.png";

    // shaded as .bin so tools don't merge it; extracted to .jar at runtime
    protected static final String NEOFORGE_RES_CLZ  = "/embedded/neoforge-installer.jar.bin";
    protected static final String NEOFORGE_RES_CL   = "embedded/neoforge-installer.jar.bin";
    // =================================

    protected static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new UiApp().open());
    }

    // ---------- shared helpers (protected so subclasses can use them) ----------

    protected Path defaultMinecraftDir() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) return Paths.get(appdata, ".minecraft");
        } else if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "minecraft");
        }
        return Paths.get(System.getProperty("user.home"), ".minecraft");
    }

    /** Find an installed neoforge-* under versions/. */
    protected String findNeoForgeId(Path mcDir) throws IOException {
        Path versions = mcDir.resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(versions)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith("neoforge-"))
                    .forEach(ids::add);
        }
        ids.sort(Comparator.reverseOrder());
        if (ids.isEmpty()) return null;
        if (REQUIRED_NEOFORGE_ID != null) {
            for (String id : ids) if (id.equals(REQUIRED_NEOFORGE_ID)) return id;
            return null;
        }
        return ids.get(0);
    }

    /** Extract embedded installer (.jar.bin) into a temp .jar. */
    protected Path locateBundledInstaller(Installer.Log log) {
        try (InputStream inA = Main.class.getResourceAsStream(NEOFORGE_RES_CLZ);
             InputStream inB = inA == null ? Main.class.getClassLoader().getResourceAsStream(NEOFORGE_RES_CL) : null) {
            InputStream in = inA != null ? inA : inB;
            if (in != null) {
                Path tmp = Files.createTempFile("neoforge-installer-", ".jar");
                try (in; OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                } catch (IOException e) { return null; }
                log.line("Embedded installer extracted: " + tmp.getFileName());
                tmp.toFile().deleteOnExit();
                return tmp;
            }
        } catch (IOException ignored) {}
        return null;
    }

    protected void runInstallerJar(Path installer, Installer.Log log) throws Exception {
        log.line("Launching installer: " + installer);
        Process p = new ProcessBuilder(findJavaBin(), "-jar", installer.toAbsolutePath().toString())
                .inheritIO().start();
        p.waitFor();
        log.line("Installer exited with code " + p.exitValue());
    }

    protected String findJavaBin() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Extract embedded mods from embedded/mods/ (accept .jar or .jar.bin). */
    protected void extractEmbeddedMods(Path destDir, Installer.Log log) throws Exception {
        Path self = selfJarPath();
        if (self == null || !self.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            // dev mode (IDE): copy from project-root mods/
            Path devMods = Paths.get("mods");
            if (Files.isDirectory(devMods)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(devMods, "*.jar")) {
                    for (Path p : ds) {
                        Files.copy(p, destDir.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                        log.line("Copied mod (dev): " + p.getFileName());
                    }
                }
            }
            return;
        }
        try (ZipFile zf = new ZipFile(self.toFile())) {
            Enumeration<? extends ZipEntry> it = zf.entries();
            boolean any = false;
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (!e.isDirectory() && e.getName().startsWith("embedded/mods/")) {
                    String name = e.getName().substring("embedded/mods/".length());
                    if (!(name.endsWith(".jar") || name.endsWith(".jar.bin"))) continue;
                    any = true;
                    String outName = name.replaceFirst("\\.jar\\.bin$", ".jar");
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, destDir.resolve(outName), StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.line("Copied mod: " + outName);
                }
            }
            if (!any) log.line("(No embedded mods found under embedded/mods/)");
        }
    }

    protected Path selfJarPath() {
        try {
            URI uri = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Paths.get(uri);
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) { return null; }
    }

    protected Path detectLauncherProfilesFile(Path mcDir) throws IOException {
        Path ms = mcDir.resolve("launcher_profiles_microsoft_store.json");
        Path std = mcDir.resolve("launcher_profiles.json");
        if (Files.exists(ms)) return ms;
        if (!Files.exists(std)) Files.writeString(std, "{\n  \"profiles\": {}\n}\n", StandardCharsets.UTF_8);
        return std;
    }

    /** Delete any profile entries whose lastVersionId equals the given version. */
    protected void pruneProfilesUsingVersion(Path profilesPath, String versionId, Installer.Log log) throws Exception {
        ObjectNode root = readJsonObject(profilesPath);
        ObjectNode profiles = root.with("profiles");

        List<String> toRemove = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = profiles.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode prof = e.getValue();
            if (prof.has("lastVersionId") && versionId.equals(prof.get("lastVersionId").asText())) {
                toRemove.add(e.getKey());
            }
        }
        for (String k : toRemove) profiles.remove(k);
        if (!toRemove.isEmpty()) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(profilesPath.toFile(), root);
            log.line("Removed " + toRemove.size() + " NeoForge launcher installation(s).");
        } else {
            log.line("No extra NeoForge installations to remove.");
        }
    }

    /** Upsert our HFD profile with icon and gameDir. */
    protected void upsertHfdProfile(Path profilesPath, Path gameDir, Installer.Log log) throws Exception {
        ObjectNode root = readJsonObject(profilesPath);
        ObjectNode profiles = root.with("profiles");

        ObjectNode mine = profiles.with(CHILD_VERSION_ID);
        mine.put("name", PROFILE_NAME);
        mine.put("type", "custom");
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        mine.put("created", now);
        mine.put("lastUsed", now);
        mine.put("lastVersionId", CHILD_VERSION_ID);
        mine.put("gameDir", gameDir.toAbsolutePath().toString());

        String iconDataUri = readIconDataUri();
        if (iconDataUri != null) mine.put("icon", iconDataUri);

        JSON.writerWithDefaultPrettyPrinter().writeValue(profilesPath.toFile(), root);
        log.line("Updated " + profilesPath.getFileName() + " with HFD profile.");
    }

    protected ObjectNode readJsonObject(Path file) throws IOException {
        return file.toFile().exists()
                ? (ObjectNode) JSON.readTree(file.toFile())
                : JSON.createObjectNode();
    }

    /** Read icon as base64 and prepend data-URL prefix. */
    protected String readIconDataUri() {
        try (InputStream in1 = Main.class.getResourceAsStream(ICON_RESOURCE_CLZ);
             InputStream in2 = in1 == null ? Main.class.getClassLoader().getResourceAsStream(ICON_RESOURCE_CL) : null) {
            InputStream in = in1 != null ? in1 : in2;
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return "data:image/png;base64," + b64;
        } catch (IOException e) { return null; }
    }

    // ---- servers.dat writer -------------------------------------------------

    protected void writeServersDat(Path serversDat, String name, String ip, boolean acceptTextures) throws IOException {
        Files.createDirectories(serversDat.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(serversDat, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {

            // Root TAG_Compound ("")
            out.writeByte(0x0A);                 // TAG_Compound
            out.writeShort(0);                   // name length = 0 ("")
            // TAG_List("servers") of TAG_Compound
            writeNamedTagHeader(out, (byte)0x09, "servers"); // TAG_List
            out.writeByte(0x0A);                 // contained type = TAG_Compound
            out.writeInt(1);                     // list length = 1

            // ---- First list element: unnamed TAG_Compound payload ----
            writeStringTag(out, "ip", ip);
            writeStringTag(out, "name", name);
            writeByteTag(out, "acceptTextures", (byte)(acceptTextures ? 1 : 0));
            // end of this compound
            out.writeByte(0x00);                 // TAG_End for the inner compound

            // end root compound
            out.writeByte(0x00);                 // TAG_End
        }
    }

    protected void writeNamedTagHeader(DataOutputStream out, byte type, String name) throws IOException {
        out.writeByte(type);
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(nb.length);
        out.write(nb);
    }

    protected void writeStringTag(DataOutputStream out, String name, String value) throws IOException {
        writeNamedTagHeader(out, (byte)0x08, name); // TAG_String
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(vb.length);
        out.write(vb);
    }

    protected void writeByteTag(DataOutputStream out, String name, byte value) throws IOException {
        writeNamedTagHeader(out, (byte)0x01, name); // TAG_Byte
        out.writeByte(value);
    }

    protected InputStream iconStream() {
        try {
            InputStream in1 = Main.class.getResourceAsStream(ICON_RESOURCE_CLZ);
            if (in1 != null) return in1;
            return Main.class.getClassLoader().getResourceAsStream(ICON_RESOURCE_CL);
        } catch (Exception ex) {
            return null;
        }
    }
}
