package testrunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.IOUtils;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.Compute.Instances.Get;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.InstanceList;
import com.google.api.services.compute.model.NetworkInterface;
import com.google.api.services.compute.model.Operation;
import com.google.api.services.compute.model.Zone;
import com.google.api.services.compute.model.ZoneList;

public class GCloudUtil {

    public static final String META_INSTANCE_NAME = "name";
    public static final String META_INSTANCE_IP = "network-interfaces/0/ip";
    private String projectId;
    private Compute computeService;

    public GCloudUtil() throws IOException, GeneralSecurityException {
        projectId = getGCloudProjectVal("project-id");
        computeService = createComputeService();
        Log.info("GCloud project id: " + projectId);
    }

    public static String getGCloudProjectVal(String field) throws MalformedURLException, IOException {
        URL url = new URL("http://metadata.google.internal/computeMetadata/v1/project/" + field);
        URLConnection con = url.openConnection();
        con.setRequestProperty("Metadata-Flavor", "Google");
        try (InputStream is = con.getInputStream()) {
            return IOUtils.toString(is);
        }
    }

    public static String getGCloudMetaVal(String field) throws MalformedURLException, IOException {
        URL url = new URL("http://metadata.google.internal/computeMetadata/v1/instance/" + field);
        URLConnection con = url.openConnection();
        con.setRequestProperty("Metadata-Flavor", "Google");
        try (InputStream is = con.getInputStream()) {
            return IOUtils.toString(is);
        }
    }

    public List<String> getGCloudZones() throws IOException, GeneralSecurityException {
        List<String> result = new ArrayList<String>();
        Compute.Zones.List request = computeService.zones().list(projectId);

        ZoneList response;
        do {
            response = request.execute();
            if (response.getItems() == null) {
                continue;
            }
            for (Zone z : response.getItems()) {
                result.add(z.getName());
            }
            request.setPageToken(response.getNextPageToken());
        } while (response.getNextPageToken() != null);
        return result;
    }

    public List<Instance> getGCloudInstances(String zone) throws IOException, GeneralSecurityException {
        List<Instance> result = new ArrayList<Instance>();
        Compute.Instances.List request = computeService.instances().list(projectId, zone);
        InstanceList response;
        do {
            response = request.execute();
            if (response.getItems() == null) {
                continue;
            }
            for (Instance i : response.getItems()) {
                result.add(i);

            }
            request.setPageToken(response.getNextPageToken());
        } while (response.getNextPageToken() != null);
        return result;
    }

    public static String getInstanceIp(Instance inst) {
        for (NetworkInterface networkInterface : inst.getNetworkInterfaces()) {
            return networkInterface.getNetworkIP();
        }
        return null;
    }

    public List<String> getGCloudInstanceIPs(String zone) throws IOException, GeneralSecurityException {
        List<String> result = new ArrayList<String>();
        Compute.Instances.List request = computeService.instances().list(projectId, zone);
        InstanceList response;
        do {
            response = request.execute();
            if (response.getItems() == null) {
                continue;
            }
            for (Instance i : response.getItems()) {
                for (NetworkInterface networkInterface : i.getNetworkInterfaces()) {
                    result.add(networkInterface.getNetworkIP());
                    break;
                }
            }
            request.setPageToken(response.getNextPageToken());
        } while (response.getNextPageToken() != null);
        return result;
    }

    public List<String> getAllGCloudIPs() throws IOException, GeneralSecurityException {
        ArrayList<String> result = new ArrayList<String>();
        for (String zone : getGCloudZones()) {
            result.addAll(getGCloudInstanceIPs(zone));
        }
        return result;
    }

    public List<Instance> getAllGCloudInstances() throws IOException, GeneralSecurityException {
        ArrayList<Instance> result = new ArrayList<Instance>();
        for (String zone : getGCloudZones()) {
            result.addAll(getGCloudInstances(zone));
        }
        return result;
    }

    public static Compute createComputeService() throws IOException, GeneralSecurityException {
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();

        GoogleCredential credential = GoogleCredential.getApplicationDefault();
        if (credential.createScopedRequired()) {
            credential = credential.createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));
        }

        return new Compute.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName("Google-ComputeSample/0.1").build();
    }

    public static String getInstanceZone(Instance inst) {
        String zone = inst.getZone();
        String[] split = zone.split("/");
        return split[split.length - 1];
    }

    public boolean start(Instance inst) throws IOException {
        Compute.Instances.Start request = computeService.instances().start(projectId, getInstanceZone(inst),
                inst.getName());
        Operation response = request.execute();

        if ("ERROR".equals(response.getStatus()))
            return false;

        Instance status;
        do {
            Compute.Instances.Get get = computeService.instances().get(projectId, getInstanceZone(inst),
                    inst.getName());
            status = get.execute();
        } while (!"RUNNING".equals(status.getStatus()));

        return true;
    }

    public boolean stop(Instance inst) throws IOException {
        Compute.Instances.Stop request = computeService.instances().stop(projectId, getInstanceZone(inst),
                inst.getName());
        Operation response = request.execute();

        if ("ERROR".equals(response.getStatus()))
            return false;

        Instance status;
        do {
            Compute.Instances.Get get = computeService.instances().get(projectId, getInstanceZone(inst),
                    inst.getName());
            status = get.execute();
        } while ("RUNNING".equals(status.getStatus()));

        return true;
    }
}
