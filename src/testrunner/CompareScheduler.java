package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CompareScheduler implements TestScheduler {
    private static class JobRequest extends TestScheduler.JobRequest {
        private String stream;
        private Descriptor descriptor;
        private String ofName;
        private int enIdx;
        private int ptIdx;

        public JobRequest(String jobName, File jobArchive, int priority, String stream, Descriptor descriptor, String ofName, int enIdx, int ptIdx) {
            super(jobName, jobArchive, priority);
            this.stream = stream;
            this.descriptor = descriptor;
            this.ofName = ofName;
            this.enIdx = enIdx;
            this.ptIdx = ptIdx;
        }

        public String getStream() {
            return stream;
        }

        public Descriptor getDescriptor() {
            return descriptor;
        }

        public int getEnIdx() {
            return enIdx;
        }

        public int getPtIdx() {
            return ptIdx;
        }

        public String getOfName() {
            return ofName;
        }
    }

    public static class JobResult extends TestScheduler.JobResult {
        private long fileSize;
        private double psnr;
        private double ssim;

        public JobResult(JobRequest jobRequest, boolean valid, String stdout, long fileSize, double psnr, double ssim) {
            super(jobRequest, valid, stdout);
            this.fileSize = fileSize;
            this.psnr = psnr;
            this.ssim = ssim;
        }

        public long getFileSize() {
            return fileSize;
        }

        public double getPsnr() {
            return psnr;
        }

        public double getSsim() {
            return ssim;
        }
    }

    static class Descriptor {
        private List<String> dataset;
        private String[] encBin;
        private String[] encName;
        private int maxFrames;
        private String profile;
        private String codec;
        private String mode;
        private int effort;
        private String extraArgs;
        private String testDefinition;

        public Descriptor(List<String> dataset, String[] encBin, String[] encName, int maxFrames, String profile,
                String codec, String mode, int effort, String extraArgs, String testDefinition) {
            this.dataset = dataset;
            this.encBin = encBin;
            this.encName = encName;
            this.maxFrames = maxFrames;
            this.profile = profile;
            this.codec = codec;
            this.mode = mode;
            this.effort = effort;
            this.extraArgs = extraArgs;
            this.testDefinition = testDefinition;
        }

        public static Descriptor parse(File baseDir, String str, Map<String, String> params) throws IOException {
            str = str.replace("$HOME", System.getProperty("user.home"));
            for (Entry<String, String> entry : params.entrySet()) {
                str = str.replace("$" + entry.getKey(), entry.getValue());
            }

            JsonObject jsonObject = JsonParser.parseString(str).getAsJsonObject();
            String profile = jsonObject.get("profile").getAsString();
            String datasetFile = jsonObject.get("dataset").getAsString();
            List<String> dataset = null;
            if (datasetFile != null) {
                File file = new File(baseDir, datasetFile);
                dataset = file.exists() ? FileUtils.readLines(file) : null;
            }

            String[] encBin = parseArray(jsonObject.get("encBin").getAsJsonArray());
            String[] encName = parseArray(jsonObject.get("encName").getAsJsonArray());

            JsonElement jsonElement0 = jsonObject.get("maxFrames");
            int maxFrames = jsonElement0 == null ? 100 : jsonElement0.getAsInt();
            JsonElement jsonElement1 = jsonObject.get("codec");
            String codec = jsonElement1 == null ? "av1" : jsonElement1.getAsString();
            JsonElement jsonElement2 = jsonObject.get("mode");
            String mode = jsonElement2 == null ? "bitrate" : jsonElement2.getAsString();
            JsonElement jsonElement3 = jsonObject.get("effort");
            int effort = jsonElement3 == null ? 0 : jsonElement3.getAsInt();
            JsonElement jsonElement4 = jsonObject.get("extraArgs");
            String extraArgs = jsonElement4 == null ? "" : jsonElement4.getAsString();
            JsonElement jsonElement5 = jsonObject.get("testDefinition");
            String testDefinition = jsonElement5 == null ? "" : jsonElement5.getAsString();

            return new Descriptor(dataset, encBin, encName, maxFrames, profile, codec, mode, effort, extraArgs, testDefinition);
        }

        private static String[] parseArray(JsonArray encNames) {
            String[] encName = new String[encNames.size()];
            for (int i = 0; i < encNames.size(); i++) {
                encName[i] = encNames.get(i).getAsString();
            }
            return encName;
        }

        public List<String> getDataset() {
            return dataset;
        }

        public String[] getEncBin() {
            return encBin;
        }

        public String[] getEncName() {
            return encName;
        }

        public int getMaxFrames() {
            return maxFrames;
        }

        public String getProfile() {
            return profile;
        }

        public String getCodec() {
            return codec;
        }

        public String getMode() {
            return mode;
        }

        public int getEffort() {
            return effort;
        }

        public String getExtraArgs() {
            return extraArgs;
        }

        public String getTestDefinition() {
            return testDefinition;
        }
    }

    private Descriptor descriptor;
    private int priority;

    public CompareScheduler(Descriptor descriptor, int priority) {
        this.descriptor = descriptor;
        this.priority = priority;
    }

    public static TestScheduler create(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("CompareScheduler: need a job descriptor json");
            return null;
        }
        File file = new File(args[0]);
        int priority = 255;
        Map<String, String> params = new HashMap<String, String>();
        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("-D")) {
                  String[] split = arg.substring(2).split(":");
                  params.put(split[0], split[1]);
                } else if (arg.startsWith("-p")) {
                    priority = Integer.parseInt(arg.substring(2));
                }
            }
        }

        Descriptor descriptor = Descriptor.parse(file.getParentFile(), FileUtils.readFileToString(file), params);
        return new CompareScheduler(descriptor, priority);
    }

    @Override
    public void finish(List<TestScheduler.JobResult> results, File baseFldr) throws IOException {
        Log.info("Generating report.");
        generateReport(baseFldr, results, descriptor);
    }

    @Override
    public List<TestScheduler.JobRequest> generateJobRequests(File requestsFldr) {
        List<TestScheduler.JobRequest> result = new ArrayList<TestScheduler.JobRequest>();
        for (String stream : descriptor.getDataset()) {
            for (int enc = 0; enc < 2; enc++) {
                for (int pt = 0; pt < 11; pt ++) {
                    stream = stream.trim();
                    String fileName = new File(stream).getName();
                    String outputBaseName = fileName.replaceAll("\\.[0-9a-zA-Z]+$", "");
                    File encBinF = new File(descriptor.getEncBin()[enc]);

                    String jobName = outputBaseName + "_" + pt + "_" + encBinF.getName() + "_"
                            + String.format("%07d", (int) (Math.random() * 1000000));
                    File jobArchive = new File(requestsFldr, jobName + ".zip");
                    result.add(new JobRequest(jobName, jobArchive, priority, stream, descriptor, outputBaseName, enc, pt));
                }
            }
        }
        return result;
    }

    @Override
    public TestScheduler.JobResult processResult(TestScheduler.JobRequest jobRequest_, File resultArchive) {
        JobRequest jobRequest = (JobRequest) jobRequest_;
        try {
            String psnrTxt = ZipUtils.getFileAsString(resultArchive, jobRequest.getOfName() + "_psnr.csv");
            String ssimTxt = ZipUtils.getFileAsString(resultArchive, jobRequest.getOfName() + "_ssim.csv");
            long fileSize = ZipUtils.getFileSize(resultArchive, jobRequest.getOfName() + "." + getFileExtension(jobRequest.getDescriptor().getCodec()));
            String stdout = ZipUtils.getFileAsString(resultArchive, "stdout.log");
            boolean errorFlag = ZipUtils.containsFile(resultArchive, "error.flag");

            if (errorFlag) {
                Log.error("[" + jobRequest.getJobName() + "] Job failed.");
                return new JobResult(jobRequest, false, stdout, 0, 0, 0);
            }
            if (psnrTxt == null) {
                Log.error("[" + jobRequest.getJobName() + "] Job result did not contain PSNR data.");
                return new JobResult(jobRequest, false, stdout, 0, 0, 0);
            }

            if (ssimTxt == null) {
                Log.error("[" + jobRequest.getJobName() + "] Job result did not contain SSIM data.");
                return new JobResult(jobRequest, false, stdout, 0, 0, 0);
            }

            if (fileSize < 0) {
                Log.error("[" + jobRequest.getJobName() + "] Job result did not contain encoded stream.");
                return new JobResult(jobRequest, false, stdout, 0, 0, 0);
            }

            double psnr = parseCsvVal(psnrTxt);
            double ssim = parseCsvVal(ssimTxt);

            System.out.println((char) 27 + "[92m+ " + jobRequest.getOfName() + "#" + jobRequest.getPtIdx() + "("
                    + jobRequest.getDescriptor().getEncName()[jobRequest.getEnIdx()] + ")" + (char) 27 + "[0m");

            return new JobResult(jobRequest, true, "", fileSize, psnr, ssim);
        } catch (Exception e) {
            Log.error("[" + jobRequest.getJobName() + "] Couldn't process the job result.");
            e.printStackTrace(System.out);
            return new JobResult(jobRequest, false, "Got exception: " + e.getMessage(), 0, 0, 0);
        }
    }

    private String getFileExtension(String codec) {
        if ("h264".equals(codec))
            return "264";
        else
            return "ivf";
    }

    @Override
    public void createJobArchive(TestScheduler.JobRequest jobRequest_) {
        JobRequest jobRequest = (JobRequest) jobRequest_;
        try {
            StringBuilder runSh = new StringBuilder();
            String fileName = new File(jobRequest.getStream()).getName();

            int width = workoutW(fileName);
            int height = workoutH(fileName);

            File encBinF = new File(jobRequest.getDescriptor().getEncBin()[jobRequest.getEnIdx()]);

            runSh.append("FILENAME=\"" + fileName + "\"\n");
            runSh.append("ENC_BN=\"" + encBinF.getName() + "\"\n");
            runSh.append("PROFILE=\"" + jobRequest.getDescriptor().getProfile() + "\"\n");
            runSh.append("PT_IDX=\"" + jobRequest.getPtIdx() + "\"\n");
            runSh.append("MAX_FRAMES=\"" + jobRequest.getDescriptor().getMaxFrames() + "\"\n");
            runSh.append("OF_BN=\"" + jobRequest.getOfName() + "\"\n");
            runSh.append("WIDTH=\"" + width + "\"\n");
            runSh.append("HEIGHT=\"" + height + "\"\n");
            runSh.append("CODEC=\"" + jobRequest.getDescriptor().getCodec() + "\"\n");
            runSh.append("DF_BN=\"" + jobRequest.getOfName() + "_recon.yuv\"\n");
            runSh.append("MODE=\"" + jobRequest.getDescriptor().getMode() + "\"\n");
            runSh.append("EFFORT=\"" + jobRequest.getDescriptor().getEffort() + "\"\n");
            runSh.append("EXTRA_ARGS=\"" + jobRequest.getDescriptor().getExtraArgs() + "\"\n");

            try (InputStream is = this.getClass().getClassLoader()
                    .getResourceAsStream("testrunner/gcloud_remote.tpl")) {
                runSh.append(IOUtils.toString(is));
            }

            Map<String, Object> map = new HashMap<String, Object>();
            map.put("run.sh", runSh.toString());
            map.put("manifest.json", "{\"cpu\":3}");
            map.put(encBinF.getName(), encBinF);
            map.put("test_parameters.sh", new File(new File(jobRequest.getDescriptor().getTestDefinition()), "/common/test/quality/test_parameters.sh"));

            ZipUtils.createArchive(map, jobRequest.getJobArchive());
            Log.info("[" + jobRequest.getJobName() + "] Created job archive.");
        } catch (IOException e) {
            Log.error("Could not create a job archive for job '" + jobRequest.getJobName() + "'.");
            e.printStackTrace(System.out);
        }
    }

    private void generateReport(File baseFldr, List<TestScheduler.JobResult> results, Descriptor descriptor)
            throws IOException {
        Log.info("Done, generating report.");
        File reportFile = new File(baseFldr, "report.html");
        List<String> strings0 = new ArrayList<String>();
        List<String> strings1 = new ArrayList<String>();
        for (TestScheduler.JobResult jr : Util.copy(results)) {
            if (!jr.isValid()) {
                Log.warn("[" + jr.getJobRequest().getJobName() + "] Didn't have the result, skipping.");
                String stdout = jr.getStdout();
                if (stdout != null) {
                    String[] split = stdout.split("\n");
                    String[] copyOfRange = Arrays.copyOfRange(split, Math.max(0, split.length - 10), split.length);
                    Log.warn("    " + String.join("\n    ", copyOfRange));
                }
                removeAllSimilar(jr, results);
            }
        }

        for (TestScheduler.JobResult jr_ : results) {
            JobResult jr = (JobResult) jr_;
            JobRequest jobRequest = (JobRequest) jr.getJobRequest();
            String line = "\n{\"filename\":\"" + jobRequest.getOfName() + "\",\"ptIdx\":\"" + jobRequest.getPtIdx()
                    + "\",\"dist\":[\"" + jr.getPsnr() + "\",\"" + jr.getPsnr() + "\",\"" + jr.getSsim()
                    + "\"],\"rate\":[\"" + jr.getFileSize() + "\",\"" + jr.getFileSize() + "\",\"" + jr.getFileSize()
                    + "\"]}";
            if (jobRequest.getEnIdx() == 0)
                strings0.add(line);
            else
                strings1.add(line);
        }
        StringBuilder[] builders = { new StringBuilder(), new StringBuilder() };
        for (int i = 0; i < 2; i++) {
            builders[i].append("var dataset" + (i + 1) + " = `{");
            builders[i].append("\"encoder\": \"" + descriptor.getEncName()[i] + "\",");
            builders[i].append("\"encoderArgs\": \"" + descriptor.getProfile() + "\",");
            builders[i].append("\"maxFrames\": \"" + descriptor.getMaxFrames() + "\",");
            builders[i].append("\"profile\": \"" + descriptor.getProfile() + "\",");
            builders[i].append("\"points\": [");
            builders[i].append(i == 0 ? String.join(",", strings0) : String.join(",", strings1));
            builders[i].append("]}`;");
        }

        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("testrunner/report.html")) {
            String templ = IOUtils.toString(is);

            templ = templ.replace("|||BODY0|||", builders[0].toString());
            templ = templ.replace("|||BODY1|||", builders[1].toString());

            FileUtils.writeStringToFile(reportFile, templ);

            System.out.println("Saved report to file: " + reportFile.getAbsolutePath());
        }
    }

    private void removeAllSimilar(TestScheduler.JobResult jr, List<TestScheduler.JobResult> results) {
        JobRequest jreq0 = (JobRequest) jr.getJobRequest();
        for (ListIterator<TestScheduler.JobResult> it = results.listIterator(); it.hasNext();) {
            TestScheduler.JobResult jobResult = it.next();
            JobRequest jreq1 = (JobRequest) jobResult.getJobRequest();
            if (jreq0.getStream().equals(jreq1.getStream()) && jreq0.getPtIdx() == jreq1.getPtIdx()) {
                it.remove();
            }
        }
    }

    private double parseCsvVal(String txt) {
        String[] lines = txt.split("\n");
        if (lines.length == 0)
            return 0;

        return Double.parseDouble(lines[lines.length - 1].replace("average,", ""));
    }

    public static int workoutW(String filename) {
        Map<String, Integer> stdWidth = new HashMap<String, Integer>();
        stdWidth.put("qcif", 176);
        stdWidth.put("cif", 352);
        stdWidth.put("sif", 352);
        stdWidth.put("vga", 640);
        stdWidth.put("4cif", 704);
        stdWidth.put("qvga", 320);
        stdWidth.put("720p", 1280);

        String whRegex = ".*_w([0-9]*)h([0-9]*)[\\._].*";
        String w = filename.replaceAll(whRegex, "$1");
        if (w.equals(filename)) {
            whRegex = ".*_([0-9]*)x([0-9]*)[\\._].*";
            w = filename.replaceAll(whRegex, "$1");
            if (w.equals(filename)) {
                whRegex = ".*_(cif|qcif|sif|vga|4cif|qvga|720p).*";
                String std = filename.replaceAll(whRegex, "$1");
                if (stdWidth.containsKey(std)) {
                    return stdWidth.get(std);
                }
            } else {
                return Integer.parseInt(w);
            }
        } else {
            return Integer.parseInt(w);
        }
        return 0;
    }

    public static int workoutH(String filename) {
        Map<String, Integer> stdHeight = new HashMap<String, Integer>();
        stdHeight.put("qcif", 144);
        stdHeight.put("cif", 288);
        stdHeight.put("sif", 240);
        stdHeight.put("vga", 480);
        stdHeight.put("4cif", 576);
        stdHeight.put("qvga", 240);
        stdHeight.put("720p", 720);

        String whRegex = ".*_w([0-9]*)h([0-9]*)[\\._].*";
        String h = filename.replaceAll(whRegex, "$2");
        if (h.equals(filename)) {
            whRegex = ".*_([0-9]*)x([0-9]*)[\\._].*";
            h = filename.replaceAll(whRegex, "$2");
            if (h.equals(filename)) {
                whRegex = ".*_(cif|qcif|sif|vga|4cif|qvga|720p).*";
                String std = filename.replaceAll(whRegex, "$1");
                if (stdHeight.containsKey(std)) {
                    return stdHeight.get(std);
                }
            } else {
                return Integer.parseInt(h);
            }
        } else {
            return Integer.parseInt(h);
        }
        return 0;
    }
}
