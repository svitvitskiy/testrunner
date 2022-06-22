package testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;

public class HttpIface {
    private HttpClientConnectionManager connManager;
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    private int connectionTimeout;
    private int socketTimeout;

    static {
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
    }

    public static class HttpIfaceException extends Exception {
        public HttpIfaceException() {
            super();
        }

        public HttpIfaceException(String message, Throwable cause, boolean enableSuppression,
                boolean writableStackTrace) {
            super(message, cause, enableSuppression, writableStackTrace);
        }

        public HttpIfaceException(String message, Throwable cause) {
            super(message, cause);
        }

        public HttpIfaceException(String message) {
            super(message);
        }

        public HttpIfaceException(Throwable cause) {
            super(cause);
        }
    }

    public HttpIface(int connectionTimeout, int socketTimeout) throws HttpIfaceException {
        try {
            PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(
                    getSocketFactoryRegistry());
            connManager.setMaxTotal(10);
            connManager.setDefaultMaxPerRoute(10);
            this.connManager = connManager;
            this.keepAliveStrategy = new MyKeepAliveStrategy();
            this.connectionTimeout = connectionTimeout;
            this.socketTimeout = socketTimeout;
        } catch (Exception e) {
            throw new HttpIfaceException(e);
        }
    }

    private Registry<ConnectionSocketFactory> getSocketFactoryRegistry()
            throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("https", getSslSocketFactory())
                .register("http", PlainConnectionSocketFactory.getSocketFactory()).build();
        return socketFactoryRegistry;
    }

    private SSLConnectionSocketFactory getSslSocketFactory()
            throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        SSLContextBuilder builder = new SSLContextBuilder();
        builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(builder.build(), new HostnameVerifier() {
            public boolean verify(java.lang.String arg0, javax.net.ssl.SSLSession arg1) {
                return true;
            }
        });
        return sslsf;
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

    public InputStream openUrlStream(URL url) throws HttpIfaceException {

        HttpResponse response = openConnection(url);
        try {
            return response.getEntity().getContent();
        } catch (IOException e) {
            throw new HttpIfaceException(e);
        }
    }

    public HttpResponse openConnection(URL url) throws HttpIfaceException {
        try {
            CloseableHttpClient client = getClient();

            HttpGet httpget = new HttpGet(url.toExternalForm());
            httpget.setConfig(getRequestConfig());
            HttpResponse response = client.execute(httpget);
            return response;
        } catch (Exception e) {
            throw new HttpIfaceException(e);
        }
    }

    public HttpResponse upload(URL url, File uploadFile, String name) throws HttpIfaceException {
        try {
            CloseableHttpClient client = getClient();
            HttpPost httpPost = new HttpPost(url.toExternalForm());

            FileBody uploadFilePart = new FileBody(uploadFile);
            MultipartEntity reqEntity = new MultipartEntity();
            reqEntity.addPart("file", uploadFilePart);
            httpPost.setConfig(getRequestConfig());
            httpPost.setEntity(reqEntity);

            return client.execute(httpPost);
        } catch (Exception e) {
            throw new HttpIfaceException(e);
        }
    }

    private RequestConfig getRequestConfig() {
        RequestConfig requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Integer.MAX_VALUE)
                .setConnectTimeout(connectionTimeout).setSocketTimeout(socketTimeout).build();
        return requestConfig;
    }

    private CloseableHttpClient getClient() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        CloseableHttpClient client = HttpClients.custom().setKeepAliveStrategy(keepAliveStrategy)
                .setSSLSocketFactory(getSslSocketFactory()).setConnectionManager(connManager).build();
        return client;
    }

    public HttpResponse postString(URL url, String val) throws HttpIfaceException {
        try {
            CloseableHttpClient client = getClient();

            HttpPost httpPost = new HttpPost(url.toExternalForm());
            httpPost.setConfig(getRequestConfig());

            StringEntity entity = new StringEntity(val);
            httpPost.setEntity(entity);
            httpPost.setHeader("Accept", "application/json");
            httpPost.setHeader("Content-type", "application/json");
            return client.execute(httpPost);
        } catch (Exception e) {
            throw new HttpIfaceException(e);
        }
    }
}
