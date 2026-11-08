package io.github.heavencanceller.vproxy;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class ControllerClient {
    private ControllerClient() {}
    static JSONObject request(String method, String path, JSONObject body) throws Exception {
        String secret = ProxyVpnService.controllerSecret;
        if (!ProxyVpnService.running || secret == null) throw new IOException("请先连接 VPN");
        if (!path.startsWith("/") || path.contains("://")) throw new IOException("Invalid controller path");
        HttpURLConnection connection = (HttpURLConnection)new URL("http://127.0.0.1:" + ProxyVpnService.controllerPort + path).openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod(method); connection.setConnectTimeout(1000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Authorization","Bearer " + secret);
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json");
            try (OutputStream output=connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        try {
            int code=connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("内核接口 HTTP " + code + ": " + CoreClient.read(connection.getErrorStream(),65536));
            if (code == 204) return new JSONObject();
            String raw=CoreClient.read(connection.getInputStream(),4*1024*1024);
            return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } finally { connection.disconnect(); }
    }
}
