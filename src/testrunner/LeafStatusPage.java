package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

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
            try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("testrunner/leafstatus.html")) {
                String templ = IOUtils.toString(is);

                StringBuilder sb = new StringBuilder();
                List<BaseJob> tmp = Util.safeCopy(jobs);
                sb.append("<div>Start time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(new Date(startTime))
                        + "</div>");
                sb.append("<table class=\"jobs\" cellpadding=\"0\" cellspacing=\"0\">");
                sb.append("<tr><td>Name</td><td>Status</td><td>Job archive</td><td>Result archive</td></tr>");
                for (BaseJob baseJob : tmp) {
                    sb.append("<tr>");
                    sb.append("<td>" + baseJob.getName() + "</td>");
                    sb.append("<td>" + baseJob.getStatus() + "</td>");
                    sb.append("<td>" + valOrNa(baseJob.getJobArchiveRef()) + "</td>");
                    sb.append("<td>" + valOrNa(baseJob.getResultArchiveRef()) + "</td>");
                    sb.append("</tr>");
                }
                sb.append("</table>");
                templ = templ.replace("|||BODY|||", sb.toString());

                new PrintStream(response.getOutputStream()).print(templ);
            }
        }
    }

    private String valOrNa(String val) {
        return val == null ? "N/A" : "<a href=\"/download/" + val + "\">" + val + "</a>";
    }

}
