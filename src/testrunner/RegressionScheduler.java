package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import testrunner.ZipUtils.CompareFilesStatus;

public class RegressionScheduler implements TestScheduler {
    static class JobRequest extends TestScheduler.JobRequest {
        private int encIdx;
        private int strmIdx;
        private int qpIdx;

        public JobRequest(String jobName, File jobArchive, int priority, int encIdx, int strmIdx, int qpIdx) {
            super(jobName, jobArchive, priority);
            this.encIdx = encIdx;
            this.strmIdx = strmIdx;
            this.qpIdx = qpIdx;
        }

        public int getEncIdx() {
            return encIdx;
        }

        public int getStrmIdx() {
            return strmIdx;
        }

        public int getQpIdx() {
            return qpIdx;
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
        private String[] streams;
        private String[] qps;

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
            String[] streams = parseArray(jsonElement4.getAsJsonArray());
            String[] qps = parseArray(jsonElement5.getAsJsonArray());
            return new Descriptor(encoders, jsonElement1.getAsString(), jsonElement2.getAsInt(),
                    jsonElement3.getAsString(), streams, qps);
        }

        public Descriptor(String[] encoders, String profile, int maxFrames, String codec, String[] streams,
                String[] qps) {
            this.encoders = encoders;
            this.profile = profile;
            this.maxFrames = maxFrames;
            this.codec = codec;
            this.streams = streams;
            this.qps = qps;
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

        public String[] getStreams() {
            return streams;
        }

        public String[] getQps() {
            return qps;
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
    private File baseFldr;

    public RegressionScheduler(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public List<TestScheduler.JobRequest> generateJobRequests(File requestsFldr) {
        List<testrunner.TestScheduler.JobRequest> result = new ArrayList<TestScheduler.JobRequest>();
        for (int encIdx = 0; encIdx < descriptor.getEncoders().length; encIdx++) {
            for (int strmIdx = 0; strmIdx < descriptor.getStreams().length; strmIdx++) {
                for (int qpIdx = 0; qpIdx < descriptor.getQps().length; qpIdx++) {
                    File encBinF = new File(descriptor.getEncoders()[encIdx]);
                    String stream = descriptor.getStreams()[strmIdx];
                    String qp = descriptor.getQps()[qpIdx];

                    String encBaseName = encBinF.getName();
                    String jobName = "test_" + encBaseName + "_" + getOfName(stream) + "_" + qp + "_"
                            + String.format("%06d", (int) (Math.random() * 1000000));

                    result.add(
                            new JobRequest(jobName, new File(requestsFldr, jobName + ".zip"), 255, encIdx, strmIdx, qpIdx));
                }
            }
        }
        return result;
    }

    @Override
    public void createJobArchive(TestScheduler.JobRequest jobRequest_) {
        JobRequest jobRequest = (JobRequest) jobRequest_;
        try {
            StringBuilder runSh = new StringBuilder();
            String stream = descriptor.getStreams()[jobRequest.getStrmIdx()];
            String fileName = new File(stream).getName();

            int width = CompareScheduler.workoutW(fileName);
            int height = CompareScheduler.workoutH(fileName);

            File encBinF = new File(descriptor.getEncoders()[jobRequest.getEncIdx()]);
            String ofName = getOfName(stream);

            runSh.append("FILENAME=\"" + fileName + "\"\n");
            runSh.append("ENC_BN=\"" + encBinF.getName() + "\"\n");
            runSh.append("PROFILE=\"" + descriptor.getProfile() + "\"\n");
            runSh.append("MINQ=\"" + descriptor.getQps()[jobRequest.getQpIdx()] + "\"\n");
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
            Log.info("[" + jobRequest.getJobName() + "] Created job archive.");
        } catch (IOException e) {
            Log.error("Could not create a job archive for job '" + jobRequest.getJobName() + "'.");
            e.printStackTrace(System.out);
        }
    }

    @Override
    public JobResult processResult(TestScheduler.JobRequest jobRequest_, File resultArchive) {
        JobRequest jobRequest = (JobRequest) jobRequest_;
        try {
            String stdout = ZipUtils.getFileAsString(resultArchive, "stdout.log");
            String ofName = getOfName(descriptor.getStreams()[jobRequest.getStrmIdx()]);
            
            CompareFilesStatus compareResult = ZipUtils.compareFiles(resultArchive, "recon.yuv", ofName + "_recon.yuv");

            if (compareResult == CompareFilesStatus.FILE_A_NOT_FOUND) {
                Log.error("[" + jobRequest.getJobName() + "] Job result did not contain recon file.");
                return new JobResult(jobRequest, false, stdout, false);
            }

            if (compareResult == CompareFilesStatus.FILE_B_NOT_FOUND) {
                Log.error("[" + jobRequest.getJobName() + "] Job result did not contain reference recon file.");
                return new JobResult(jobRequest, false, stdout, false);
            }

            String encBaseName = new File(descriptor.getEncoders()[jobRequest.getEncIdx()]).getName();

            System.out.println((char) 27 + "[92m+ " + ofName + "@" + descriptor.getQps()[jobRequest.getQpIdx()] + "("
                    + encBaseName + ")" + (char) 27 + "[0m");

            return new JobResult(jobRequest, true, stdout, compareResult == CompareFilesStatus.EQUAL);
        } catch (Exception e) {
            Log.error("[" + jobRequest.getJobName() + "] Couldn't process the job result.");
            e.printStackTrace(System.out);
            return new JobResult(jobRequest, false, "Got exception: " + e.getMessage(), false);
        }
    }

    private String getOfName(String stream) {
        String fileName = new File(stream).getName();
        return fileName.replaceAll("\\.[0-9a-zA-Z]+$", "");
    }

    @Override
    public void finish(List<TestScheduler.JobResult> results) throws IOException {
        System.out.println("Report:");
        for (TestScheduler.JobResult r : results) {
            if (r instanceof JobResult) {
                JobResult jr = (JobResult) r;
                JobRequest req = (JobRequest) jr.getJobRequest();
                String prefix = descriptor.getEncoders()[req.getEncIdx()] + " qp:" + descriptor.getQps()[req.getQpIdx()]
                        + " " + descriptor.getStreams()[req.getStrmIdx()];
                if (jr.isValid()) {
                    System.out.println(prefix + ": " + (jr.isMatch() ? "MATCH" : "MISMATCH"));
                } else {
                    System.out.println(prefix + ": ERROR PROCESSING THE JOB");
                    String[] split = jr.getStdout().split("\n");
                    String[] copyOfRange = Arrays.copyOfRange(split, Math.max(0, split.length - 10), split.length);
                    System.out.println("    " + String.join("\n    ", copyOfRange));
                }
            } else {
                JobRequest req = (JobRequest) r.getJobRequest();
                System.out.println(descriptor.getEncoders()[req.getEncIdx()] + ": ERROR SCHEDULING THE JOB");
            }
        }
    }

    @Override
    public void processError(testrunner.TestScheduler.JobRequest jobRequest) {
        Log.error("[" + jobRequest.getJobName() + "] Job failed to schedule.");        
    }

    @Override
    public void init(File baseFldr) {
        this.baseFldr = baseFldr;
    }
}
