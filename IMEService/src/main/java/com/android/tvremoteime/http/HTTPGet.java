package com.android.tvremoteime.http;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Minimal HTTPS GET helper used by the self-update flow.
 *
 * TLS is verified with the platform default trust manager and hostname
 * verifier. The previous implementation installed a trust-all manager
 * process-wide, which let any network attacker feed this app a forged
 * version.json and a malicious "update" APK.
 */
public class HTTPGet {
    private static final String TAG = "HTTPGet";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/65.0.3325.181 Safari/537.36";

    private static HttpsURLConnection openHttps(String uri) throws Exception {
        URL url = new URL(uri);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("Only https URLs are allowed: " + uri);
        }
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "text/html, application/xhtml+xml, image/jxr, */*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        return conn;
    }

    public static String readString(String uri) {
        HttpsURLConnection conn = null;
        try {
            conn = openHttps(uri);
            if (conn.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                return null;
            }
            int length = conn.getContentLength();
            if (length < 0) {
                length = 4096;
            }
            StringBuilder buffer = new StringBuilder(length);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] tmp = new char[1024];
                int l;
                while ((l = reader.read(tmp)) != -1) {
                    buffer.append(tmp, 0, l);
                }
            }
            return buffer.toString();
        } catch (Exception e) {
            Log.e(TAG, "readString", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static boolean downloadFile(String uri, File file) {
        HttpsURLConnection conn = null;
        try {
            conn = openHttps(uri);
            if (conn.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                return false;
            }
            try (InputStream instream = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(file)) {
                byte[] tmp = new byte[8192];
                int l;
                while ((l = instream.read(tmp)) != -1) {
                    fos.write(tmp, 0, l);
                }
                fos.flush();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "downloadFile", e);
            // Never leave a partial download behind: the update flow
            // treats an existing file as a completed download.
            if (file != null && file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete partial download: " + file);
            }
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
