package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class RegressionScheduler implements TestScheduler {
    private String PREFIX_ERROR = (char) 27 + "[91mERROR: ";
    private String PREFIX_WARN = (char) 27 + "[95mWARN: ";
    private String SUFFIX_CLEAR = (char) 27 + "[0m";

    static class JobRequest extends TestScheduler.JobRequest {
        private int encIdx;

        public JobRequest(String jobName, File jobArchive, int encIdx) {
            super(jobName, jobArchive);
            this.encIdx = encIdx;
        }

        public int getEncIdx() {
            return encIdx;
        }
    }

    static class JobResult extends TestScheduler.JobResult {

        private boolean match;

        public JobResult(testrunner.TestScheduler.JobRequest jobRequest, boolean valid, String stdout, boolean match) {
            super(jobRequest, valid, stdout);
            this.match = match;
        }

        public boolean isMatch() {
            return match;
        }
    }

    static class Descriptor {
        private String[] encoders;
        private String profile;
        private int maxFrames;
        private String codec;
        private String stream;
        private int qp;

        private static String[] parseArray(JsonArray encNames) {
            String[] encName = new String[encNames.size()];
            for (int i = 0; i < encNames.size(); i++) {
                encName[i] = encNames.get(i).getAsString();
            }
            return encName;
        }

        public static Descriptor parse(File file) throws IOException {
            String str = FileUtils.readFileToString(file);
            File baseDir = file.getParentFile();
            str = str.replace("$HOME", System.getProperty("user.home"));

            JsonObject jsonObject = JsonParser.parseString(str).getAsJsonObject();
            JsonElement jsonElement0 = jsonObject.get("encoders");
            if (jsonElement0 == null) {
                System.out.println("Element 'encoders' is required in the manifest.");
                return null;
            }
            JsonElement jsonElement1 = jsonObject.get("profile");
            if (jsonElement1 == null) {
                System.out.println("Element 'profile' is required in the manifest.");
                return null;
            }
            JsonElement jsonElement2 = jsonObject.get("maxFrames");
            if (jsonElement2 == null) {
                System.out.println("Element 'maxFrames' is required in the manifest.");
                return null;
            }
            JsonElement jsonElement3 = jsonObject.get("codec");
            if (jsonElement3 == null) {
                System.out.println("Element 'codec' is required in the manifest.");
                return null;
            }
            JsonElement jsonElement4 = jsonObject.get("stream");
            if (jsonElement4 == null) {
                System.out.println("Element 'stream' is required in the manifest.");
                return null;
            }
            JsonElement jsonElement5 = jsonObject.get("qp");
            if (jsonElement5 == null) {
                System.out.println("Element 'qp' is required in the manifest.");
                return null;
            }
            String[] encoders = parseArray(jsonElement0.getAsJsonArray());
            return new Descriptor(encoders, jsonElement1.getAsString(), jsonElement2.getAsInt(),
                    jsonElement3.getAsString(), jsonElement4.getAsString(), jsonElement5.getAsInt());
        }

        public Descriptor(String[] encoders, String profile, int maxFrames, String codec, String stream, int qp) {
            this.encoders = encoders;
            this.profile = profile;
            this.maxFrames = maxFrames;
            this.codec = codec;
            this.stream = stream;
            this.qp = qp;
        }

        public String[] getEncoders() {
            return encoders;
        }

        public String getProfile() {
            return profile;
        }

        public int getMaxFrames() {
            return maxFrames;
        }

        public String getCodec() {
            return codec;
        }

        public String getStream() {
            return stream;
        }

        public int getQp() {
            return qp;
        }
    }

    public static TestScheduler create(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Missing test descriptor spec");
            return null;
        }
        return new RegressionScheduler(Descriptor.parse(new File(args[0])));
    }

    private Descriptor descriptor;

    public RegressionScheduler(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public List<TestScheduler.JobRequest> generateJobRequests(File requestsFldr) {
        List<testrunner.TestScheduler.JobRequest> result = new ArrayList<TestScheduler.JobRequest>();
        for (int encIdx = 0; encIdx < descriptor.getEncoders().length; encIdx++) {
            File encBinF = new File(descriptor.getEncoders()[encIdx]);
            String encBaseName = encBinF.getName();
            String jobName = "test_" + encBaseName + "_" + String.format("%6d", Math.random() * 1000000);

            result.add(new JobRequest(jobName, new File(requestsFldr, jobName + ".zip"), encIdx));
        }
        return result;
    }

    @Override
    public void createJobArchive(TestScheduler.JobRequest jobRequest_) {
        JobRequest jobRequest = (JobRequest) jobRequest_;
        try {
            StringBuilder runSh = new StringBuilder();
            String fileName = new File(descriptor.getStream()).getName();

            int width = CompareScheduler.workoutW(fileName);
            int height = CompareScheduler.workoutH(fileName);

            File encBinF = new File(descriptor.getEncoders()[jobRequest.getEncIdx()]);
            String ofName = descriptor.getStream().replaceAll("\\.[0-9a-zA-Z]+$", "");

            runSh.append("FILENAME=\"" + fileName + "\"\n");
            runSh.append("ENC_BN=\"" + encBinF.getName() + "\"\n");
            runSh.append("PROFILE=\"" + descriptor.getProfile() + "\"\n");
            runSh.append("MINQ=\"" + descriptor.getQp() + "\"\n");
            runSh.append("MAX_FRAMES=\"" + descriptor.getMaxFrames() + "\"\n");
            runSh.append("OF_BN=\"" + ofName + "\"\n");
            runSh.append("WIDTH=\"" + width + "\"\n");
            runSh.append("HEIGHT=\"" + height + "\"\n");
            runSh.append("CODEC=\"" + descriptor.getCodec() + "\"\n");
            runSh.append("DF_BN=\"" + ofName + "_recon.yuv\"\n");
            try (InputStream is = this.getClass().getClassLoader()
                    .getResourceAsStream("testrunner/gcloud_regression.tpl")) {
                runSh.append(IOUtils.toString(is));
            }

            Map<String, Object> map = new HashMap<String, Object>();
            map.put("run.sh", runSh.toString());
            map.put("manifest.json", "{\"cpu\":3}");
            map.put(encBinF.getName(), encBinF);

            ZipUtils.createArchive(map, jobRequest.getJobArchive());
            System.out.println("INFO: [" + jobRequest.getJobName() + "] Created job archive.");
        } catch (IOException e) {
            System.out.println(PREFIX_ERROR + "Could not create a job archive for job '" + jobRequest.getJobName()
                    + "'." + SUFFIX_CLEAR);
            e.printStackTrace(System.out);
        }
    }

    @Override
    public JobResult processResult(TestScheduler.JobRequest jobRequest_, File resultArchive) {
        JobRequest jobRequest = (JobRequest) jobRequest_;
        try {
            String stdout = ZipUtils.getFileAsString(resultArchive, "stdout.log");

            String ofName = descriptor.getStream().replaceAll("\\.[0-9a-zA-Z]+$", "");
            File f1 = File.createTempFile("cool", "stan");
            File f2 = File.createTempFile("cool", "stan");
            ZipUtils.extractFileTo(resultArchive, "recon.yuv", f1);
            ZipUtils.extractFileTo(resultArchive, ofName + "_recon.yuv", f1);

            boolean contentEquals = FileUtils.contentEquals(f1, f2);

            String encBaseName = new File(descriptor.getEncoders()[jobRequest.getEncIdx()]).getName();

            System.out.println((char) 27 + "[92m+ " + ofName + "@" + descriptor.getQp() + "(" + encBaseName + ")"
                    + (char) 27 + "[0m");

            return new JobResult(jobRequest, true, stdout, contentEquals);
        } catch (Exception e) {
            System.out.println(
                    PREFIX_ERROR + "[" + jobRequest.getJobName() + "] Couldn't process the job result." + SUFFIX_CLEAR);
            e.printStackTrace(System.out);
            return new JobResult(jobRequest, false, "Got exception: " + e.getMessage(), false);
        }
    }

    @Override
    public void finish(List<TestScheduler.JobResult> results, File baseFldr) throws IOException {
        System.out.println("Report:");
        for (TestScheduler.JobResult r : results) {
            JobResult jr = (JobResult) r;
            JobRequest req = (JobRequest) jr.getJobRequest();
            System.out
                    .println(descriptor.getEncoders()[req.getEncIdx()] + ": " + (jr.isMatch() ? "MATCH" : "MISMATCH"));
        }
    }
}
