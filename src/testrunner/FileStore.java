package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.commons.io.FileUtils;

/**
 * Manages the file storage for the agent. The public API is thread safe.
 * 
 * Will restore the 'awareness' of stored files on the application restart.
 * 
 * @author vitvitskyy
 *
 */
public class FileStore {
    private Map<String, File> files;
    private Executor executor;
    private File baseDir;

    public FileStore(File baseDir) {
        this.baseDir = baseDir;
        files = new HashMap<String, File>();
        executor = Executors.newFixedThreadPool(1);

        executor.execute(new Runnable() {
            public void run() {
                restoreFiles();
            }
        });
    }

    public String addAsInputStream(InputStream is) throws IOException {
        String id = UUID.randomUUID().toString();
        File dest = new File(baseDir, id);
        FileUtils.copyInputStreamToFile(is, dest);
        add(dest, id);
        return id;
    }

    public String addAsFile(File file) throws IOException {
        String id = UUID.randomUUID().toString();
        File dest = new File(baseDir, id);
        FileUtils.copyFile(file, dest);
        add(dest, id);
        return id;
    }

    synchronized public boolean has(String id) {
        return files.containsKey(id);
    }
    
    synchronized public File get(String id) {
        return files.get(id);
    }

    synchronized public void delete(String id) {
        File file = files.get(id);
        files.remove(id);
        executor.execute(new Runnable() {
            public void run() {
                file.delete();
            }
        });
    }
    
    private void restoreFiles() {
        if (!this.baseDir.exists())
            this.baseDir.mkdirs();
        for (File file : this.baseDir.listFiles()) {
            add(file, file.getName());
        }
    }
    
    synchronized private void add(File file, String id) {
        files.put(id, file);
    }
}
