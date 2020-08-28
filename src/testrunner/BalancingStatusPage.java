package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.List;
import java.util.ListIterator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

public class BalancingStatusPage implements BaseAgent.Handler {
    private List<BaseJob> jobs;
    private List<AgentConnection> delegates;

    public BalancingStatusPage(List<BaseJob> jobs, List<AgentConnection> delegates) {
        this.jobs = jobs;
        this.delegates = delegates;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestURI = request.getRequestURI();
        List<AgentConnection> safeCopy = BaseAgent.safeCopy(delegates);
        if (requestURI.startsWith("/proxy")) {
            int idx = Integer.parseInt(requestURI.replace("/proxy/", ""));
            if (safeCopy.size() <= idx) {
                return;
            }
            AgentConnection remote = safeCopy.get(idx);
            try (InputStream is = new URL(remote.getUrl()).openStream()) {
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
            } else if ("restart".equals(action)) {
                System.exit(0);
            } else {
                try (InputStream is = this.getClass().getClassLoader()
                        .getResourceAsStream("testrunner/balancingstatus.html")) {
                    String templ = IOUtils.toString(is);

                    StringBuilder sb = new StringBuilder();
                    List<BaseJob> tmp = BaseAgent.safeCopy(jobs);
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
                "<tr><td>Name</td><td>Status</td><td>Downloading?</td><td>Job archive</td><td>Result archive</td></tr>");
        for (BaseJob baseJob : tmp) {
            BalancingJob bj = (BalancingJob) baseJob;
            if (bj.getDelegate() == null || !agentUrl.equals(bj.getDelegate().getAgentUrl()))
                continue;
            sb.append("<tr>");
            sb.append("<td>" + baseJob.getName() + "</td>");
            sb.append("<td>" + baseJob.getStatus() + "</td>");
            sb.append("<td>" + (bj.isDownloading() ? "YES" : "NO") + "</td>");
            sb.append("<td>" + valOrNa(baseJob.getJobArchiveRef()) + "</td>");
            sb.append("<td>" + valOrNa(baseJob.getResultArchiveRef()) + "</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
    }

    private String valOrNa(String val) {
        return val == null ? "N/A" : "<a href=\"/download/" + val + "\">" + val + "</a>";
    }
}
