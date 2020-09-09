package testrunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

public abstract class BaseAgent extends AbstractHandler {
    public static interface Handler {
        void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        if ("/status".equals(target)) {
            getStatusHandler().handle(request, response);
        } else if ("/upload".equals(target)) {
            getUploadHandler().handle(request, response);
        } else if (target.startsWith("/download")) {
            getDownloadHandler().handle(request, response);
        } else if ("/new".equals(target)) {
            getNewJobHandler().handle(request, response);
        } else if ("/log".equals(target)) {
            getLogHandler().handle(request, response);
        } else {
            getStatusPage().handle(request, response);
        }
        baseRequest.setHandled(true);
    }


    public void startAgent(int port) throws Exception {
        Server server = new Server(port);

        MultipartConfigInjectionHandler multipartConfigInjectionHandler = new MultipartConfigInjectionHandler();
        multipartConfigInjectionHandler.setHandler(this);
        server.setHandler(multipartConfigInjectionHandler);

        server.start();
        server.join();
    }
    protected abstract Handler getStatusPage();

    protected abstract Handler getNewJobHandler();

    protected abstract Handler getDownloadHandler();

    protected abstract Handler getUploadHandler();

    protected abstract Handler getStatusHandler();
    
    protected abstract Handler getLogHandler();
}
