package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;

public class HttpIface {
    private HttpClientConnectionManager connManager;
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    private int connectionTimeout;
    private int socketTimeout;

    public HttpIface(int connectionTimeout, int socketTimeout) {
        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(10);
        connManager.setDefaultMaxPerRoute(10);
        this.connManager = connManager;
        this.keepAliveStrategy = new MyKeepAliveStrategy();
        this.connectionTimeout = connectionTimeout;
        this.socketTimeout = socketTimeout;
    }

    private static class MyKeepAliveStrategy implements ConnectionKeepAliveStrategy {
        @Override
        public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
            HeaderElementIterator it = new BasicHeaderElementIterator(response.headerIterator(HTTP.CONN_KEEP_ALIVE));
            while (it.hasNext()) {
                HeaderElement he = it.nextElement();
                String param = he.getName();
                String value = he.getValue();
                if (value != null && param.equalsIgnoreCase("timeout")) {
                    return Long.parseLong(value) * 1000;
                }
            }
            return 5 * 1000;
        }
    };

    public InputStream openUrlStream(URL url) throws IllegalStateException, IOException {

        HttpResponse response = openConnection(url);
        return response.getEntity().getContent();
    }

    public HttpResponse openConnection(URL url) throws IOException, ClientProtocolException {
        CloseableHttpClient client = getClient();

        HttpGet httpget = new HttpGet(url.toExternalForm());
        httpget.setConfig(getRequestConfig());
        HttpResponse response = client.execute(httpget);
        return response;
    }

    public HttpResponse upload(URL url, File uploadFile, String name) throws ClientProtocolException, IOException {
        CloseableHttpClient client = getClient();
        HttpPost httpPost = new HttpPost(url.toExternalForm());

        FileBody uploadFilePart = new FileBody(uploadFile);
        MultipartEntity reqEntity = new MultipartEntity();
        reqEntity.addPart("file", uploadFilePart);
        httpPost.setConfig(getRequestConfig());
        httpPost.setEntity(reqEntity);

        return client.execute(httpPost);
    }

    private RequestConfig getRequestConfig() {
        RequestConfig requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Integer.MAX_VALUE)
                .setConnectTimeout(connectionTimeout).setSocketTimeout(socketTimeout).build();
        return requestConfig;
    }

    private CloseableHttpClient getClient() {
        CloseableHttpClient client = HttpClients.custom().setKeepAliveStrategy(keepAliveStrategy)
                .setConnectionManager(connManager).build();
        return client;
    }

    public HttpResponse postString(URL url, String val) throws ClientProtocolException, IOException {
        CloseableHttpClient client = getClient();

        HttpPost httpPost = new HttpPost(url.toExternalForm());
        httpPost.setConfig(getRequestConfig());

        StringEntity entity = new StringEntity(val);
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");
        httpPost.setHeader("Content-type", "application/json");
        return client.execute(httpPost);
    }
}
