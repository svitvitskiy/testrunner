package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.thymeleaf.context.Context;

import testrunner.AgentConnection.Event;

public class BalancingStatusPage implements BaseAgent.Handler {
    private List<BaseJob> jobs;
    private List<AgentConnection> delegates;
    private long startTime;
    private HttpIface http;
    private FileStore fileStore;

    public BalancingStatusPage(List<BaseJob> jobs, List<AgentConnection> delegates, HttpIface http, FileStore fileStore, long startTime) {
        this.jobs = jobs;
        this.delegates = delegates;
        this.http = http;
        this.fileStore = fileStore;
        this.startTime = startTime;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestURI = request.getRequestURI();
        List<AgentConnection> safeCopy = Util.safeCopy(delegates);
        if (requestURI.startsWith("/proxy")) {
            proxy(response, requestURI, safeCopy);
        } else {
            String action = request.getParameter("action");
            if ("wipejobs".equals(action)) {
                wipeJobs(response);
            } else if ("restart".equals(action)) {
                restart(response);
            } else if ("rerun".equals(action)) {
                rerunJob(request, response);
            } else {
                displayStatus(response, safeCopy);
            }
        }
    }

    private void displayStatus(HttpServletResponse response, List<AgentConnection> safeCopy) throws IOException {
        Context context = new Context();
        context.setVariable("startTime",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(new Date(startTime)));
        context.setVariable("version", new Util().getVersion());
        List<Object> agents = new ArrayList<Object>();
        int i = 0;
        List<BaseJob> tmp = Util.safeCopy(jobs);
        for (AgentConnection agentConnection : safeCopy) {
            Map<String, Object> agent = new HashMap<String, Object>();
            agent.put("url", "/proxy/" + i);
            agent.put("name", agentConnection.getUrl());
            agent.put("availableCPU", agentConnection.getAvailableCPU());
            agent.put("totalJobs", agentConnection.getTotalRunningJobs());
            agent.put("online", agentConnection.isOnline() ? "YES" : "NO");
            agent.put("serving", agentConnection.isServing() ? "YES" : "NO");
            agent.put("jobs", jobList(tmp, agentConnection.getUrl()));
            agent.put("events", listEvents(agentConnection));
            agents.add(agent);
            ++i;
        }
        context.setVariable("agents", agents);
        context.setVariable("unsched", unscheduled(context, tmp));

        try {
            Util.processTemplate(response, context, "testrunner/balancingstatus.html");
        } catch(Exception e) {
            e.printStackTrace(response.getWriter());
        }
    }

    private void rerunJob(HttpServletRequest request, HttpServletResponse response) {
        String jobName = request.getParameter("job");
        boolean found = false;
        for (BaseJob baseJob : Util.safeCopy(jobs)) {
            if (jobName.equals(baseJob.getName())) {
                Log.info("[" + jobName + "] rerunning.");
                found = true;
                baseJob.updateStatus(BaseJob.Status.NEW);
                ((BalancingJob) baseJob).eraseDelegate();
                break;
            }
        }
        if (!found) {
            Log.info("[" + jobName + "] Couldn't rerun, job not found.");
        }
        redirectHome(response);
    }

    private void restart(HttpServletResponse response) {
        redirectHome(response);
        exitIn1Second();
    }

    private void wipeJobs(HttpServletResponse response) {
        synchronized (jobs) {
            for (ListIterator<BaseJob> it = jobs.listIterator(); it.hasNext();) {
                BaseJob baseJob = it.next();
                if (baseJob.getStatus() == BaseJob.Status.DONE)
                    it.remove();
            }
        }
        redirectHome(response);
    }

    private void proxy(HttpServletResponse response, String requestURI, List<AgentConnection> safeCopy)
            throws IOException, MalformedURLException {
        int idx = Integer.parseInt(requestURI.replace("/proxy/", ""));
        if (safeCopy.size() <= idx) {
            return;
        }
        AgentConnection remote = safeCopy.get(idx);
        Log.info("Proxying remote url: '" + remote.getUrl() + "'");
        try (InputStream is = http.openUrlStream(new URL(remote.getUrl()))) {
            IOUtils.copy(is, response.getOutputStream());
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

    private List<Object> listEvents(AgentConnection agentConnection) {
        List<Object> result = new ArrayList<Object>();
        List<Event> events = agentConnection.getEvents();
        for (int i = events.size() - 1, cnt = 0; (i > 0) && (cnt < 10); i--, cnt++) {
            Event event = events.get(i);
            Map<String, String> evt = new HashMap<String, String>();
            evt.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(new Date(event.getTime())));
            evt.put("type", String.valueOf(event.getType()));
            result.add(evt);
        }
        return result;
    }

    private List<Object> unscheduled(Context context, List<BaseJob> tmp) {
        List<Object> result = new ArrayList<Object>();
        ArrayList<BaseJob> unsched = new ArrayList<BaseJob>(tmp);
        Collections.sort(unsched, new Comparator<BaseJob>() {
            @Override
            public int compare(BaseJob o1, BaseJob o2) {
                return Integer.compare(o1.getPriority(), o2.getPriority());
            }
        });
        for (BaseJob baseJob : unsched) {
            BalancingJob bj = (BalancingJob) baseJob;
            if (bj.getDelegate() == null) {
                Map<String, String> obj = new HashMap<String, String>();
                obj.put("name", bj.getName());
                obj.put("priority", String.valueOf(bj.getPriority()));
                obj.put("status", bj.getStatus().name());
                result.add(obj);
            }
        }
        return result;
    }

    private List<Object> jobList(List<BaseJob> tmp, String agentUrl) {
        List<Object> result = new ArrayList<Object>();
        for (BaseJob baseJob : tmp) {
            BalancingJob bj = (BalancingJob) baseJob;
            if (bj.getDelegate() == null || !agentUrl.equals(bj.getDelegate().getAgent().getUrl()))
                continue;
            Map<String, Object> map = new HashMap<String, Object>();
            map.put("name", baseJob.getName());
            map.put("status", String.valueOf(baseJob.getStatus()));
            map.put("priority", bj.getPriority());
            if (fileStore.has(bj.getJobArchiveRef())) {
                map.put("jobArchiveUrl", "/download/" + bj.getJobArchiveRef());
            }
            map.put("jobArchiveName", bj.getJobArchiveRef());
            if (fileStore.has(bj.getResultArchiveRef())) {
                map.put("resultArchiveUrl", "/download/" + bj.getResultArchiveRef());
            }
            map.put("resultArchiveName", bj.getResultArchiveRef());
            map.put("rerunUrl", "/?action=rerun&job=" + bj.getName());
            result.add(map);
        }
        return result;
    }
}
