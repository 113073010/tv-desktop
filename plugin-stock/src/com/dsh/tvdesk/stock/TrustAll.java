package com.dsh.tvdesk.stock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/** Android 5 上部分站点证书链校验失败，这里统一放行 */
public class TrustAll {

    private static SSLSocketFactory sFactory;
    private static boolean sBuilt;

    public static synchronized SSLSocketFactory socketFactory() {
        if (sBuilt) return sFactory;
        sBuilt = true;
        try {
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] x, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] x, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            sFactory = c.getSocketFactory();
        } catch (Throwable t) {
            sFactory = null;
        }
        return sFactory;
    }
}
