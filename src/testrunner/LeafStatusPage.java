package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.thymeleaf.context.Context;

public class LeafStatusPage implements BaseAgent.Handler {
    private List<BaseJob> jobs;
    private long startTime;

    public LeafStatusPage(List<BaseJob> jobs, long startTime) {
        this.jobs = jobs;
        this.startTime = startTime;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String action = request.getParameter("action");
        if ("wipejobs".equals(action)) {
            wipeJobs();
        } else if ("restart".equals(action)) {
            System.exit(0);
        } else {
            displayStatus(response);
        }
    }

    private void displayStatus(HttpServletResponse response) throws IOException {
        Context context = new Context();
        context.setVariable("startTime",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(new Date(startTime)));
        context.setVariable("version", new Util().getVersion());

        List<Object> result = new ArrayList<Object>();
        for (BaseJob baseJob : Util.safeCopy(jobs)) {
            LeafJob lj = (LeafJob) baseJob;
            Map<String, Object> map = new HashMap<String, Object>();
            map.put("name", baseJob.getName());
            map.put("status", baseJob.getStatus());
            map.put("jobArchiveUrl", "/download/" + lj.getJobArchiveRef());
            map.put("jobArchiveName", lj.getJobArchiveRef());
            map.put("resultArchiveUrl", "/download/" + lj.getResultArchiveRef());
            map.put("resultArchiveName", lj.getResultArchiveRef());
            result.add(map);
        }
        context.setVariable("jobs", result);

        Util.processTemplate(response, context, "testrunner/leafstatus.html");
    }

    private void wipeJobs() {
        synchronized (jobs) {
            for (ListIterator<BaseJob> it = jobs.listIterator(); it.hasNext();) {
                BaseJob baseJob = it.next();
                if (baseJob.getStatus() == BaseJob.Status.DONE)
                    it.remove();
            }
        }
    }
}
