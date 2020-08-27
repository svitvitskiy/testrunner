package testrunner;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;

public class DownloadHandler implements BaseAgent.Handler {

    private FileStore files;

    public DownloadHandler(FileStore files) {
        this.files = files;
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (!"GET".equals(request.getMethod()))
            return;
        String pathInfo = request.getPathInfo();
        String id = pathInfo.replaceAll("^/download/", "");
        if (!files.has(id)) {
            response.setStatus(404);
        } else {
            response.setStatus(200);
            FileUtils.copyFile(files.get(id), response.getOutputStream());
        }
    }
}
