package testrunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class ZipUtils {

    public static void compressDir(File dir, File output) throws IOException {
        try (ZipOutputStream os = new ZipOutputStream(new FileOutputStream(output))) {
            File[] listFiles = dir.listFiles();
            for (File file2 : listFiles) {
                if (!file2.isFile())
                    continue;
                os.putNextEntry(new ZipEntry(file2.getName()));
                try (InputStream is = new FileInputStream(file2)) {
                    IOUtils.copy(is, os);
                    os.closeEntry();
                }
            }
        }
    }

    public static String getFileAsString(File zipFile, String filePath) throws IOException {
        if (zipFile == null)
            return null;
        if (filePath == null)
            return null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            String result = null;
            while (zipEntry != null) {
                if (filePath.equals(zipEntry.getName())) {
                    result = IOUtils.toString(zis);
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            return result;
        }
    }

    public static boolean extractFileTo(File zipFile, String filePath, File toFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                if (filePath.equals(zipEntry.getName())) {
                    FileUtils.copyToFile(zis, toFile);
                    return true;
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
        return false;
    }
    
    public static enum CompareFilesStatus {
        FILE_A_NOT_FOUND, FILE_B_NOT_FOUND, EQUAL, NON_EQUAL
    }
    
    public static CompareFilesStatus compareFiles(File zipFile, String fileAPath, String fileBPath) throws IOException {
        try (ZipInputStream zisA = new ZipInputStream(new FileInputStream(zipFile)); ZipInputStream zisB = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntryA = findZipEntry(fileAPath, zisA);
            if (zipEntryA == null) {
                return CompareFilesStatus.FILE_A_NOT_FOUND;
            }
            ZipEntry zipEntryB = findZipEntry(fileBPath, zisB);
            if (zipEntryB == null) {
                zisA.closeEntry();
                return CompareFilesStatus.FILE_B_NOT_FOUND;
            }
            boolean equals = IOUtils.contentEquals(zisA, zisB);
            
            zisA.closeEntry();
            zisB.closeEntry();
            
            return equals ? CompareFilesStatus.EQUAL : CompareFilesStatus.NON_EQUAL;
        }
    }

    private static ZipEntry findZipEntry(String filePath, ZipInputStream zis) throws IOException {
        ZipEntry zipEntryA = null;
        do {
            zipEntryA = zis.getNextEntry();
            if (zipEntryA != null && filePath.equals(zipEntryA.getName())) {
                return zipEntryA;
            }
        } while(zipEntryA != null);
        return null;
    }

    public static void createArchive(Map<String, Object> map, File output) throws IOException {
        try (ZipOutputStream os = new ZipOutputStream(new FileOutputStream(output))) {
            Set<Entry<String, Object>> entrySet = map.entrySet();
            for (Entry<String, Object> entry : entrySet) {
                if (entry.getValue() instanceof File) {
                    os.putNextEntry(new ZipEntry(entry.getKey()));
                    try (InputStream is = new FileInputStream((File) entry.getValue())) {
                        IOUtils.copy(is, os);
                    }
                    os.closeEntry();
                } else if (entry.getValue() instanceof String) {
                    os.putNextEntry(new ZipEntry(entry.getKey()));
                    new PrintStream(os).print((String) entry.getValue());
                    os.closeEntry();
                }
            }
        }
    }

    public static long getFileSize(File zipFile, String filePath) throws IOException {
        long ret = -1;
        byte[] b = new byte[1024];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                if (filePath.equals(zipEntry.getName())) {
                    long size = 0;
                    int read = 0;
                    while ((read = zis.read(b)) != -1)
                        size += read;
                    ret = size;
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
        return ret;
    }

    public static void extractAll(File zipFile, File tgtFolder) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                FileUtils.copyToFile(zis, new File(tgtFolder, zipEntry.getName()));
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    public static boolean containsFile(File zipFile, String filePath) throws IOException {
        boolean ret = false;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                if (filePath.equals(zipEntry.getName())) {
                    ret = true;
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
        return ret;
    }
}
