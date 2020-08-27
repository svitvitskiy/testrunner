package testrunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
}
