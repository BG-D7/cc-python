package work.diffdev.kik.python.pip;

import work.diffdev.kik.python.bridge.FSBridge;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WheelExtractor {

    /**
     * Extracts a .whl (ZIP) file directly into the ComputerCraft virtual filesystem.
     * Skips metadata (.dist-info, .egg-info) and __pycache__ to conserve disk space.
     *
     * @param zipBytes  Raw binary bytes of the .whl or .zip file
     * @param targetDir Destination directory inside CC virtual fs (e.g. "lib")
     * @param fs        ComputerCraft FileSystem bridge
     * @return Number of files extracted
     */
    public static int extract(byte[] zipBytes, String targetDir, FSBridge fs) {
        if (zipBytes == null || fs == null) return 0;
        int count = 0;
        String cleanTarget = (targetDir == null || targetDir.isEmpty() || targetDir.equals("/")) ? "" : targetDir.replaceAll("^/+", "").replaceAll("/+$", "");

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                // Skip metadata, tests, and bytecode caches to conserve computer disk space
                if (name.contains(".dist-info/") || name.contains(".egg-info/") || name.contains("__pycache__/")) {
                    continue;
                }

                String destPath = cleanTarget.isEmpty() ? name : (cleanTarget + "/" + name);

                if (entry.isDirectory()) {
                    fs.makeDir(destPath);
                } else {
                    byte[] data = zis.readAllBytes();
                    int lastSlash = destPath.lastIndexOf('/');
                    if (lastSlash > 0) {
                        fs.makeDir(destPath.substring(0, lastSlash));
                    }
                    if (fs.writeBytes(destPath, data)) {
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return count;
    }
}
