package testrunner;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import testrunner.BaseJob.Status;

public class StatusHandler implements BaseAgent.Handler {

    private List<BaseJob> jobs;

    public StatusHandler(List<BaseJob> jobs) {
        this.jobs = jobs;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);
        StringBuilder txt = new StringBuilder();
        int nThreads = Runtime.getRuntime().availableProcessors();

        int cpuUsed = 0;
        txt.append("{");
        txt.append("\"jobs\": [");
        for (Iterator<BaseJob> it = BaseAgent.safeCopy(jobs).iterator(); it.hasNext();) {
            BaseJob job = it.next();
            txt.append("{");
            txt.append("\"jobArchiveRef\":\"" + job.getJobArchiveRef() + "\",");
            txt.append("\"resultArchiveRef\":\"" + job.getResultArchiveRef() + "\",");
            txt.append("\"name\":\"" + job.getName() + "\",");
            txt.append("\"status\":\"" + job.getStatus() + "\"");
            txt.append("}");
            if (it.hasNext()) {
                txt.append(",");
            }
            if (job.getStatus() != Status.DONE && (job instanceof LeafJob)) {
                cpuUsed += ((LeafJob) job).getCpuReq();
            }
        }
        txt.append("],");
        txt.append("\"availableCPU\":" + Math.max(0, nThreads - cpuUsed));
        txt.append("}");

        response.getWriter().println(txt.toString());
    }
}
