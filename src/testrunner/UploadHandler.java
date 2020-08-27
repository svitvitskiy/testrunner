package testrunner;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

public class UploadHandler implements BaseAgent.Handler {

    private FileStore files;

    public UploadHandler(FileStore files) {
        this.files = files;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if ("POST".equals(request.getMethod())) {
            Part part = request.getPart("file");
            String id = files.addAsInputStream(part.getInputStream());
            String txt = "{\"id\":\"" + id + "\"}";

            response.getWriter().println(txt);
        }
    }
}
