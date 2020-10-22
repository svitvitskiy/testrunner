package testrunner;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface TestScheduler {

    public static class JobRequest {
        private String jobName;
        private File jobArchive;
        private int priority;

        public JobRequest(String jobName, File jobArchive, int priority) {
            this.jobName = jobName;
            this.jobArchive = jobArchive;
            this.priority = priority;
        }

        public String getJobName() {
            return jobName;
        }

        public File getJobArchive() {
            return jobArchive;
        }

        public int getPriority() {
            return priority;
        }
    }

    public static class JobResult {
        private JobRequest jobRequest;
        private boolean valid;
        private String stdout;

        public JobResult(JobRequest jobRequest, boolean valid, String stdout) {
            this.jobRequest = jobRequest;
            this.valid = valid;
            this.stdout = stdout;
        }

        public JobRequest getJobRequest() {
            return jobRequest;
        }

        public boolean isValid() {
            return valid;
        }

        public String getStdout() {
            return stdout;
        }
        
        public void updateValid(boolean valid) {
            this.valid = valid;
        }
    }

    List<JobRequest> generateJobRequests(File requestsFldr);

    void createJobArchive(JobRequest jobRequest);

    JobResult processResult(JobRequest jobRequest, File resultArchive);

    void finish(List<JobResult> results, File baseFldr) throws IOException;
}
