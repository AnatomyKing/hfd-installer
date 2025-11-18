package net.anatomyworld.hfd;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Tiny safe unzip (prevents path traversal). */
final class ZipUtil {
    static void unzip(InputStream zipIn, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zipIn))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                Path out = destDir.resolve(e.getName()).normalize();
                if (!out.startsWith(destDir))
                    throw new IOException("Zip path traversal: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        int n; while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private ZipUtil() {}
}
