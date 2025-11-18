package net.anatomyworld.hfd;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Extracts or reuses a JRE & runs jars with it. Cleans up temp files. */
final class PortableJava {
    // Optional embedded runtime (fallback only)
    static final String JRE_ZIP_CLZ = "/embedded/runtime/win-x64-jre.zip";
    static final String JRE_ZIP_CL  = "embedded/runtime/win-x64-jre.zip";

    static final String NEOFORGE_CLZ = "/embedded/neoforge-installer.jar.bin";
    static final String NEOFORGE_CL  = "embedded/neoforge-installer.jar.bin";

    /**
     * Prefer the currently running runtime (NSIS launched us with a portable JRE).
     * If that isn't suitable, fall back to extracting the embedded zip.
     */
    static Path extractJreToTemp(Installer.Log log) throws IOException {
        // 1) Try current runtime
        Path jreHome = Paths.get(System.getProperty("java.home"));
        if (isUsableRuntime(jreHome)) {
            log.line("Using current runtime: " + jreHome);
            return jreHome;
        }

        // 2) Fallback: extract embedded zip to a temp dir
        Path jreRoot = Files.createTempDirectory("hfd-jre-");
        try (InputStream in1 = PortableJava.class.getResourceAsStream(JRE_ZIP_CLZ);
             InputStream in2 = in1 == null ? PortableJava.class.getClassLoader().getResourceAsStream(JRE_ZIP_CL) : null) {
            InputStream in = (in1 != null) ? in1 : in2;
            if (in == null) throw new FileNotFoundException("Missing embedded runtime: " + JRE_ZIP_CL);
            log.line("Preparing portable Java runtime (embedded) …");
            ZipUtil.unzip(in, jreRoot);
        }
        return jreRoot;
    }

    /** Copy the embedded NeoForge installer jar to the given temp dir. */
    static Path extractInstallerJar(Path tempDir, Installer.Log log) throws IOException {
        Path jar = tempDir.resolve("neoforge-installer.jar");
        try (InputStream in1 = PortableJava.class.getResourceAsStream(NEOFORGE_CLZ);
             InputStream in2 = in1 == null ? PortableJava.class.getClassLoader().getResourceAsStream(NEOFORGE_CL) : null) {
            InputStream in = (in1 != null) ? in1 : in2;
            if (in == null) throw new FileNotFoundException("Missing embedded neoforge-installer.jar.bin");
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        return jar;
    }

    /** Run the jar with the given JRE. Returns process exit code. */
    static int runJar(Path jreDir, Path jar, List<String> args, Installer.Log log)
            throws IOException, InterruptedException {
        Path javaBin = findJavaLauncher(jreDir);
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin.toString());
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.addAll(args);

        log.line("Launching NeoForge installer…");
        Process p = new ProcessBuilder(cmd) // don't inherit IO; javaw has no console
                .start();
        int code = p.waitFor();            // wait until it actually exits
        return code;
    }

    /** Best-effort recursive delete. */
    static void deleteRecursively(Path root) {
        if (root == null) return;
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    /* ---------- helpers ---------- */

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static boolean isUsableRuntime(Path jreHome) {
        Path bin = jreHome.resolve("bin");
        return Files.isDirectory(bin) && (Files.isRegularFile(bin.resolve(isWindows() ? "javaw.exe" : "java"))
                || Files.isRegularFile(bin.resolve(isWindows() ? "java.exe" : "java")));
    }

    /** Prefer javaw.exe on Windows to avoid a console window (falls back to java.exe / java). */
    private static Path findJavaLauncher(Path jreDir) {
        Path bin = jreDir.resolve("bin");
        if (isWindows()) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) return javaw;
            Path java = bin.resolve("java.exe");
            if (Files.isRegularFile(java)) return java;
        }
        Path unixJava = bin.resolve("java");
        return Files.isRegularFile(unixJava) ? unixJava : bin.resolve("java"); // best effort
    }

    private PortableJava() {}
}
