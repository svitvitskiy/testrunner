package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

public class LeafStatusPage implements BaseAgent.Handler {
    private List<BaseJob> jobs;

    public LeafStatusPage(List<BaseJob> jobs) {
        this.jobs = jobs;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("testrunner/leafstatus.html")) {
            String templ = IOUtils.toString(is);

            StringBuilder sb = new StringBuilder();
            List<BaseJob> tmp = BaseAgent.safeCopy(jobs);
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
    private String valOrNa(String val) {
        return val == null ? "N/A" : "<a href=\"/download/" + val + "\">" + val + "</a>";
    }

}
