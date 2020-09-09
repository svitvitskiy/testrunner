package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

public class LogHandler implements BaseAgent.Handler {
    private List<BaseJob> jobs;

    public LogHandler(List<BaseJob> jobs) {
        this.jobs = jobs;
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (!"GET".equals(request.getMethod()))
            return;
        String pathInfo = request.getPathInfo();
        String id = pathInfo.replaceAll("^/log/", "");

        BaseJob foundJob = null;
        for (BaseJob baseJob : Util.safeCopy(jobs)) {
            if (id.equals(baseJob.getName())) {
                foundJob = baseJob;
                break;
            }
        }
        if (foundJob == null) {
            response.setStatus(404);
        } else {
            InputStream log = foundJob.getLog();
            if (log == null) {
                response.setStatus(404);
            } else {
                response.setStatus(200);

                IOUtils.copy(log, response.getOutputStream());
            }
        }
    }
}
