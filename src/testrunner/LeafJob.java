package testrunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.commons.io.FileUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import testrunner.HttpIface.HttpIfaceException;

/**
 * A wrapper for a job. It will run a job presented as a zip archive with a
 * run.sh inside.
 * 
 * The specific details of the job are all handled by the run.sh and all the
 * resources must be fully contained inside the zip file. This wrapper assumes
 * nothing about the job being ran.
 * 
 * @author vitvitskyy
 *
 */
public class LeafJob extends BaseJob {
    private File processingBase;
    private FileStore files;
    private File logFile;

    public static class JobFactory implements BaseJob.JobFactory {
        private File processingBase;
        private FileStore files;

        public JobFactory(FileStore files, File processingBase) {
            this.files = files;
            this.processingBase = processingBase;
        }

        @Override
        public LeafJob newJob(String name, String jobArchiveRef, int priority) {
            Log.info("[" + name + "] Creating job.");
            return new LeafJob(name, jobArchiveRef, priority, files, processingBase);
        }

        @Override
        public BaseJob newJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq, int priority) {
            Log.info("[" + name + "] Creating job.");
            return new LeafJob(name, remoteJobArchiveRef, remoteUrl, cpuReq, priority, files, processingBase);
        }
    }

    private LeafJob(String name, String jobArchiveRef, int priority, FileStore files, File processingBase) {
        super(name, jobArchiveRef, priority);
        this.files = files;
        this.processingBase = processingBase;
    }

    private LeafJob(String name, String remoteJobArchiveRef, String remoteUrl, int cpuReq, int priority, FileStore files,
            File processingBase) {
        super(name, remoteJobArchiveRef, remoteUrl, cpuReq, priority);
        this.files = files;
        this.processingBase = processingBase;
    }

    public void run(HttpIface http) throws Exception {
        // Dearchive the job
        Log.info("[" + getName() + "] Running job.");
        if (getJobArchiveRef() == null && getRemoteJobArchiveRef() != null) {
            downloadJobArchive(http);
        }
        File jobArchive = files.get(getJobArchiveRef());
        if (jobArchive == null)
            throw new Exception(
                    "Could not resolve incoming job archive for job " + getName() + ":" + getJobArchiveRef());
        File jobBase = new File(processingBase, getName());
        ZipUtils.extractAll(jobArchive, jobBase);

        // Read the manifest
        File manifest = new File(jobBase, "manifest.json");
        if (manifest.exists()) {
            parseManifest(manifest);
        }
        logFile = new File(jobBase, "stdout.log");
        Process proc = new ProcessBuilder("/bin/bash", jobBase.getAbsolutePath() + "/run.sh").redirectErrorStream(true)
                .directory(jobBase).start();
        InputStream is = proc.getInputStream();
        FileUtils.copyInputStreamToFile(is, logFile);

        // Archive the results
        File tmp = File.createTempFile("super", "duper");
        ZipUtils.compressDir(jobBase, tmp);
        updateResultArchiveRef(files.addAsFile(tmp));
        tmp.delete();
        FileUtils.deleteDirectory(jobBase);

        // Deleting the input file
        Log.info("[" + getName() + "] Deleting job archive '" + getJobArchiveRef() + "'.");
        files.delete(getJobArchiveRef());

        Log.info("[" + getName() + "] Finished job.");
    }
    
    @Override
    public InputStream getLog() throws IOException {
        return logFile == null ? null : new FileInputStream(logFile);
    }

    private void downloadJobArchive(HttpIface http) throws IOException, HttpIfaceException {
        Log.info("[" + getName() + "] Downloading job archive '" + getRemoteJobArchiveRef() + "' from '"
                + getRemoteUrl() + "'.");
        try (InputStream is = http
                .openUrlStream(new URL(new URL(getRemoteUrl()), "/download/" + getRemoteJobArchiveRef()))) {
            String jobArchiveRef = files.addAsInputStream(is);
            updateJobArchiveRef(jobArchiveRef);
        }
    }

    private void parseManifest(File manifest) throws IOException {
        String manifestStr = FileUtils.readFileToString(manifest);
        JsonObject jsonObject = JsonParser.parseString(manifestStr).getAsJsonObject();
        JsonElement jsonElement = jsonObject.get("cpu");
        if (jsonElement != null) {
            updateCpuReq(jsonElement.getAsInt());
        }
    }
}
