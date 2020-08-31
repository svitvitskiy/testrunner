package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

import testrunner.AgentConnection.Event;

public class BalancingStatusPage implements BaseAgent.Handler {
    private List<BaseJob> jobs;
    private List<AgentConnection> delegates;
    private long startTime;

    public BalancingStatusPage(List<BaseJob> jobs, List<AgentConnection> delegates, long startTime) {
        this.jobs = jobs;
        this.delegates = delegates;
        this.startTime = startTime;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestURI = request.getRequestURI();
        List<AgentConnection> safeCopy = Util.safeCopy(delegates);
        if (requestURI.startsWith("/proxy")) {
            int idx = Integer.parseInt(requestURI.replace("/proxy/", ""));
            if (safeCopy.size() <= idx) {
                return;
            }
            AgentConnection remote = safeCopy.get(idx);
            System.out.println("INFO: Proxying remote url: '" + remote.getUrl() + "'");
            try (InputStream is = Util.openUrlStream(new URL(remote.getUrl()), 1000, 3000)) {
                IOUtils.copy(is, response.getOutputStream());
            }
        } else {
            String action = request.getParameter("action");
            if ("wipejobs".equals(action)) {
                synchronized (jobs) {
                    for (ListIterator<BaseJob> it = jobs.listIterator(); it.hasNext();) {
                        BaseJob baseJob = it.next();
                        if (baseJob.getStatus() == BaseJob.Status.DONE)
                            it.remove();
                    }
                }
                redirectHome(response);
            } else if ("restart".equals(action)) {
                redirectHome(response);
                exitIn1Second();
            } else if ("rerun".equals(action)) {
                String jobName = request.getParameter("job");
                boolean found = false;
                for (BaseJob baseJob : Util.safeCopy(jobs)) {
                    if (jobName.equals(baseJob.getName())) {
                        System.out.println("INFO: [" + jobName + "] rerunning.");
                        found = true;
                        baseJob.updateStatus(BaseJob.Status.NEW);
                        ((BalancingJob) baseJob).eraseDelegate();
                        break;
                    }
                }
                if (!found) {
                    System.out.println("INFO: [" + jobName + "] Couldn't rerun, job not found.");
                }
                redirectHome(response);
            } else {
                try (InputStream is = this.getClass().getClassLoader()
                        .getResourceAsStream("testrunner/balancingstatus.html")) {
                    String templ = IOUtils.toString(is);

                    StringBuilder sb = new StringBuilder();
                    List<BaseJob> tmp = Util.safeCopy(jobs);
                    sb.append("<div>Start time: "
                            + new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(new Date(startTime)) + "</div>");
                    sb.append("<table class=\"delegates\" cellpadding=\"0\" cellspacing=\"0\">");
                    int i = 0;
                    for (AgentConnection agentConnection : safeCopy) {
                        sb.append("<tr><td>Url</td><td><a href=\"/proxy/" + i + "\">" + agentConnection.getUrl()
                                + "</a></td></tr>");
                        sb.append("<tr><td>Available CPU</td><td>" + agentConnection.getAvailableCPU() + "</td></tr>");
                        sb.append("<tr><td>Total running jobs</td><td>" + agentConnection.getTotalRunningJobs()
                                + "</td></tr>");
                        sb.append("<tr><td>Online</td><td>" + agentConnection.isOnline() + "</td></tr>");
                        sb.append("<tr><td colspan=\"2\">");
                        jobList(sb, tmp, agentConnection.getUrl());
                        sb.append("</td></tr>");
                        sb.append("<tr><td>Events</td><td>" + listEvents(agentConnection) + "</td></tr>");
                        ++i;
                    }
                    sb.append("</table>");
                    unscheduled(sb, tmp);
                    templ = templ.replace("|||BODY|||", sb.toString());

                    new PrintStream(response.getOutputStream()).print(templ);
                }
            }
        }
    }

    private void exitIn1Second() {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.schedule(new Runnable() {
            @Override
            public void run() {
                System.exit(0);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void redirectHome(HttpServletResponse response) {
        response.setStatus(302);
        response.addHeader("Location", "/");
    }

    private String listEvents(AgentConnection agentConnection) {
        StringBuilder sb = new StringBuilder();
        for (Event event : agentConnection.getEvents()) {
            sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(new Date(event.getTime())) + ": "
                    + event.getType() + "<br/>");
        }
        return sb.toString();
    }

    private void unscheduled(StringBuilder sb, List<BaseJob> tmp) {
        sb.append("<table>");
        sb.append("<tr><td colspan=\"6\">Unscheduled jobs: </td></tr>");
        for (int i = 0; i < tmp.size(); i += 6) {
            sb.append("<tr>");
            for (int j = 0; j < Math.min(tmp.size() - i, 6); j++) {
                BalancingJob bj = (BalancingJob) tmp.get(i + j);
                if (bj.getDelegate() != null)
                    continue;
                sb.append("<td>" + bj.getName() + "</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
    }

    private void jobList(StringBuilder sb, List<BaseJob> tmp, String agentUrl) {
        sb.append("<table class=\"jobs\" cellpadding=\"0\" cellspacing=\"0\">");
        sb.append(
                "<tr><td>Name</td><td>Status</td><td>Downloading?</td><td>Job archive</td><td>Result archive</td><td>Actions</td></tr>");
        for (BaseJob baseJob : tmp) {
            BalancingJob bj = (BalancingJob) baseJob;
            if (bj.getDelegate() == null || !agentUrl.equals(bj.getDelegate().getAgent().getUrl()))
                continue;
            sb.append("<tr>");
            sb.append("<td>" + baseJob.getName() + "</td>");
            sb.append("<td>" + baseJob.getStatus() + "</td>");
            sb.append("<td>" + (bj.isDownloading() ? "YES" : "NO") + "</td>");
            sb.append("<td>" + valOrNa(baseJob.getJobArchiveRef()) + "</td>");
            sb.append("<td>" + valOrNa(baseJob.getResultArchiveRef()) + "</td>");
            sb.append("<td><a href=\"/?action=rerun&job=" + baseJob.getName() + "\">rerun</a></td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
    }

    private String valOrNa(String val) {
        return val == null ? "N/A" : "<a href=\"/download/" + val + "\">" + val + "</a>";
    }
}
