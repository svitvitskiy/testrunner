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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import testrunner.Util._4Future;

/**
 * Schedules encoder Test
 * 
 * @author vitvitskyy
 *
 */
public class TestScheduler {
    private static final int MAX_RETRIES = 100;
    private String PREFIX_ERROR = (char) 27 + "[91mERROR: ";
    private String PREFIX_WARN = (char) 27 + "[95mWARN: ";
    private String SUFFIX_CLEAR = (char) 27 + "[0m";

    static class Descriptor {
        private List<String> dataset;
        private String[] encBin;
        private String[] encName;
        private int maxFrames;
        private String profile;
        private String codec;

        public Descriptor(List<String> dataset, String[] encBin, String[] encName, int maxFrames, String profile,
                String codec) {
            this.dataset = dataset;
            this.encBin = encBin;
            this.encName = encName;
            this.maxFrames = maxFrames;
            this.profile = profile;
            this.codec = codec;
        }

        public static Descriptor parse(File baseDir, String str) throws IOException {
            str = str.replace("$HOME", System.getProperty("user.home"));

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

            return new Descriptor(dataset, encBin, encName, maxFrames, profile, codec);
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
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Syntax: scheduler <agent url> <destcriptor> <base folder>");
            return;
        }
        File file = new File(args[1]);
        Descriptor descriptor = Descriptor.parse(file.getParentFile(), FileUtils.readFileToString(file));

        new TestScheduler().run(descriptor, args[0], new File(args[2]));
    }

    public void run(Descriptor descriptor, String agentUrl, File baseFldr) throws Exception {
        int nThreads = Math.min(64, Runtime.getRuntime().availableProcessors() * 8);
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        ScheduledExecutorService executor2 = Executors.newScheduledThreadPool(1);

        AgentConnection agent = new AgentConnection(agentUrl, executor2);
        agent.scheduleStatusCheck();

        List<_4Future<JobResult>> futures = new ArrayList<_4Future<JobResult>>();
        scheduleJobs(agent, descriptor, baseFldr, executor, futures);

        System.out.println("INFO: Waiting for the jobs.");
        // Wait for everything to be processed
        List<JobResult> results = new ArrayList<JobResult>();
        for (_4Future<JobResult> job : futures) {
            results.add(job.get());
        }

        agent.shutdown();

        System.out.println("INFO: Generating report.");
        generateReport(baseFldr, results, descriptor);

        executor.shutdown();
        executor2.shutdown();
        executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
    }

    private void scheduleJobs(AgentConnection agent, Descriptor descriptor, File baseFldr, ExecutorService executor,
            List<_4Future<JobResult>> results) {
        File requestsFldr = new File(baseFldr, "requests");
        File resultsFldr = new File(baseFldr, "results");
        requestsFldr.mkdirs();
        resultsFldr.mkdirs();

        for (String stream : descriptor.getDataset()) {
            for (int enc = 0; enc < 2; enc++) {
                for (int qp = 48; qp <= 176; qp += 16) {
                    stream = stream.trim();
                    String fileName = new File(stream).getName();
                    String outputBaseName = fileName.replaceAll("\\.[0-9a-zA-Z]+$", "");
                    File encBinF = new File(descriptor.getEncBin()[enc]);

                    String jobName = outputBaseName + "_" + qp + "_" + encBinF.getName() + "_"
                            + String.format("%07d", (int) (Math.random() * 1000000));
                    File jobArchive = new File(requestsFldr, jobName + ".zip");
                    JobRequest jobRequest = new JobRequest(jobName, stream, descriptor, outputBaseName, enc, qp,
                            jobArchive);
                    _4Future<JobResult> future = Util.compoundFuture(executor.submit(() -> {
                        createJobArchive(jobRequest);
                        return executor.submit(() -> {
                            RemoteJob rj = scheduleWithRetry(agent, jobName, jobArchive, jobRequest);
                            return rj
                                    .onDone(() -> executor.submit(() -> processJobResult(jobRequest, rj, resultsFldr)));
                        });
                    }));
                    results.add(future);
                }
            }
        }
    }

    private RemoteJob scheduleWithRetry(AgentConnection agent, String jobName, File jobArchive, JobRequest jobRequest) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                RemoteJob rj = agent.scheduleJob(jobName, jobArchive);
                System.out.println("INFO: [" + jobRequest.getJobName() + "] Scheduled.");
                return rj;
            } catch (IOException e) {
                int retryTime = (int) (Math.random() * 1000);
                System.out.println(PREFIX_WARN + "[" + jobRequest.getJobName() + "] Couldn't start a job, retrying in "
                        + retryTime + "ms." + SUFFIX_CLEAR);
                // Random holdoff
                try {
                    Thread.sleep(retryTime);
                } catch (InterruptedException e1) {
                }
            }
        }
        System.out.println(PREFIX_ERROR + "[" + jobName + "] Couldn't schedule a job at all after " + MAX_RETRIES
                + " retries." + SUFFIX_CLEAR);
        return null;
    }

    private JobResult processJobResult(JobRequest jobRequest, RemoteJob job, File resultsFldr) {
        if (job == null)
            return new JobResult(jobRequest, false, 0, 0, 0, "Remote job was null");
        System.out.println("INFO: [" + jobRequest.getJobName() + "] Processing result.");
        File resultArchive = getResultWithRetry(job);

        if (resultArchive == null)
            return new JobResult(jobRequest, false, 0, 0, 0, "Result archive was null");

        File dest = new File(resultsFldr, jobRequest.getJobName() + ".zip");
        resultArchive.renameTo(dest);
        resultArchive = dest;

        try {
            String psnrTxt = ZipUtils.getFileAsString(resultArchive, jobRequest.getOfName() + "_psnr.csv");
            String ssimTxt = ZipUtils.getFileAsString(resultArchive, jobRequest.getOfName() + "_ssim.csv");
            long fileSize = ZipUtils.getFileSize(resultArchive, jobRequest.getOfName() + ".ivf");
            String stdout = ZipUtils.getFileAsString(resultArchive, "stdout.log");

            if (psnrTxt == null) {
                System.out.println(
                        PREFIX_ERROR + "[" + job.getName() + "] Job result did not contain PSNR data." + SUFFIX_CLEAR);
                return new JobResult(jobRequest, false, 0, 0, 0, stdout);
            }

            if (ssimTxt == null) {
                System.out.println(
                        PREFIX_ERROR + "[" + job.getName() + "] Job result did not contain SSIM data." + SUFFIX_CLEAR);
                return new JobResult(jobRequest, false, 0, 0, 0, stdout);
            }

            if (fileSize < 0) {
                System.out.println(PREFIX_ERROR + "[" + job.getName() + "] Job result did not contain encoded stream."
                        + SUFFIX_CLEAR);
                return new JobResult(jobRequest, false, 0, 0, 0, stdout);
            }

            double psnr = parseCsvVal(psnrTxt);
            double ssim = parseCsvVal(ssimTxt);

            System.out.println((char) 27 + "[92m+ " + jobRequest.getOfName() + "@" + jobRequest.getQp() + "("
                    + jobRequest.getDescriptor().getEncName()[jobRequest.getEnIdx()] + ")" + (char) 27 + "[0m");

            return new JobResult(jobRequest, true, fileSize, psnr, ssim, "");
        } catch (Exception e) {
            System.out
                    .println(PREFIX_ERROR + "[" + job.getName() + "] Couldn't process the job result." + SUFFIX_CLEAR);
            e.printStackTrace();
            return new JobResult(jobRequest, false, 0, 0, 0, "Got exception: " + e.getMessage());
        }
    }

    private File getResultWithRetry(RemoteJob job) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return job.getResultArchive();
            } catch (IOException e) {
                int retryTime = (int) (Math.random() * 1000);
                System.out.println(PREFIX_WARN + "[" + job.getName() + "] Couldn't get a job result, retrying in "
                        + retryTime + "ms." + SUFFIX_CLEAR);
                // Random holdoff
                try {
                    Thread.sleep(retryTime);
                } catch (InterruptedException e1) {
                }
            }
        }
        System.out.println(PREFIX_ERROR + "[" + job.getName() + "] Couldn't get a result at all after " + MAX_RETRIES
                + " retries." + SUFFIX_CLEAR);
        return null;
    }

    private void createJobArchive(JobRequest jobRequest) {
        try {
            StringBuilder runSh = new StringBuilder();
            String fileName = new File(jobRequest.getStream()).getName();

            int width = workoutW(fileName);
            int height = workoutH(fileName);

            File encBinF = new File(jobRequest.getDescriptor().getEncBin()[jobRequest.getEnIdx()]);

            runSh.append("FILENAME=\"" + fileName + "\"\n");
            runSh.append("ENC_BN=\"" + encBinF.getName() + "\"\n");
            runSh.append("PROFILE=\"" + jobRequest.getDescriptor().getProfile() + "\"\n");
            runSh.append("MINQ=\"" + jobRequest.getQp() + "\"\n");
            runSh.append("MAX_FRAMES=\"" + jobRequest.getDescriptor().getMaxFrames() + "\"\n");
            runSh.append("OF_BN=\"" + jobRequest.getOfName() + "\"\n");
            runSh.append("WIDTH=\"" + width + "\"\n");
            runSh.append("HEIGHT=\"" + height + "\"\n");
            runSh.append("CODEC=\"" + jobRequest.getDescriptor().getCodec() + "\"\n");
            runSh.append("DF_BN=\"" + jobRequest.getOfName() + "_recon.yuv\"\n");
            try (InputStream is = this.getClass().getClassLoader()
                    .getResourceAsStream("testrunner/gcloud_remote.tpl")) {
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
            e.printStackTrace();
        }
    }

    private void generateReport(File baseFldr, List<JobResult> results, Descriptor descriptor) throws IOException {
        System.out.println("INFO: Done, generating report.");
        File reportFile = new File(baseFldr, "report.html");
        List<String> strings0 = new ArrayList<String>();
        List<String> strings1 = new ArrayList<String>();
        for (JobResult jr : results) {
            if (!jr.isValid()) {
                System.out.println(PREFIX_WARN + "[" + jr.getJobRequest().getJobName()
                        + "] Didn't have the result, skipping." + SUFFIX_CLEAR);
                String[] split = jr.getStdout().split("\n");
                String[] copyOfRange = Arrays.copyOfRange(split, Math.max(0, split.length - 10), split.length);
                System.out.println(PREFIX_WARN + "    "
                        + String.join(SUFFIX_CLEAR + "\n" + PREFIX_WARN + "    ", copyOfRange) + SUFFIX_CLEAR);
                removeCounterPart(jr, results);
            }
        }
        for (ListIterator<JobResult> it = results.listIterator(); it.hasNext();) {
            JobResult jr = it.next();
            if (!jr.isValid()) {
                it.remove();
            }
        }
        for (JobResult jr : results) {
            String line = "\n{\"filename\":\"" + jr.getJobRequest().getOfName() + "\",\"minq\":\""
                    + jr.getJobRequest().getQp() + "\",\"dist\":[\"" + jr.getPsnr() + "\",\"" + jr.getPsnr() + "\",\""
                    + jr.getSsim() + "\"],\"rate\":[\"" + jr.getFileSize() + "\",\"" + jr.getFileSize() + "\",\""
                    + jr.getFileSize() + "\"]}";
            if (jr.getJobRequest().getEnIdx() == 0)
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

    private void removeCounterPart(JobResult jr, List<JobResult> results) {
        JobRequest jreq0 = jr.getJobRequest();
        for (JobResult jobResult : results) {
            JobRequest jreq1 = jobResult.getJobRequest();
            if (jreq0.getStream().equals(jreq1.getStream()) && jreq0.getQp() == jreq1.getQp()) {
                jobResult.updateValid(false);
            }
        }
    }

    private double parseCsvVal(String txt) {
        String[] lines = txt.split("\n");
        if (lines.length == 0)
            return 0;

        return Double.parseDouble(lines[lines.length - 1].replace("average,", ""));
    }

    int workoutW(String filename) {
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

    int workoutH(String filename) {
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

    public static class JobResult {
        private JobRequest jobRequest;
        private boolean valid;
        private long fileSize;
        private double psnr;
        private double ssim;
        private String stdout;

        public JobResult(JobRequest jobRequest, boolean valid, long fileSize, double psnr, double ssim, String stdout) {
            this.jobRequest = jobRequest;
            this.fileSize = fileSize;
            this.psnr = psnr;
            this.ssim = ssim;
            this.stdout = stdout;
        }

        public String getStdout() {
            return stdout;
        }

        public void updateValid(boolean valid) {
            this.valid = valid;
        }

        public boolean isValid() {
            return valid;
        }

        public JobRequest getJobRequest() {
            return jobRequest;
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

    private static class JobRequest {
        private String jobName;
        private String stream;
        private Descriptor descriptor;
        private String ofName;
        private int enIdx;
        private int qp;
        private File jobArchive;

        public JobRequest(String jobName, String stream, Descriptor descriptor, String ofName, int enIdx, int qp,
                File jobArchive) {
            this.jobName = jobName;
            this.stream = stream;
            this.descriptor = descriptor;
            this.ofName = ofName;
            this.enIdx = enIdx;
            this.qp = qp;
            this.jobArchive = jobArchive;
        }

        public File getJobArchive() {
            return jobArchive;
        }

        public String getJobName() {
            return jobName;
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

        public int getQp() {
            return qp;
        }

        public String getOfName() {
            return ofName;
        }
    }
}
