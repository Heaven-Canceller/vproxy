package io.github.heavencanceller.vproxy;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;

/** Local fixtures only: no subscription credentials or public proxy traffic. */
public final class SmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int pid = -1;
        try {
            JSONArray parsed = new JSONArray(NativeCore.parse("ss://YWVzLTEyOC1nY206cGFzc3dvcmQ=@127.0.0.1:8388#SmokeSS"));
            require(parsed.length() == 1, "SS parsing");
            require(parsed.getJSONObject(0).getString("server").equals("127.0.0.1"), "SS server");
            JSONArray http = new JSONArray(NativeCore.parse("http://fixture@127.0.0.1:17891#LocalFixture"));
            require(http.length() == 1, "HTTP parsing");
            JSONObject config = new JSONObject(NativeCore.compile(http.getJSONObject(0).toString(), "mihomo"));
            require(!config.has("error") && config.getJSONArray("proxies").length() == 1, "Mihomo compilation");
            require(config.getString("secret").length() > 20, "controller token");
            for (String compiler : new String[]{"sing-box", "xray"}) {
                JSONObject compilerNode = compiler.equals("sing-box")
                    ? new JSONArray(NativeCore.parse("hysteria2://fixture@127.0.0.1:443#SmokeHy2")).getJSONObject(0)
                    : parsed.getJSONObject(0);
                JSONObject compiled = new JSONObject(NativeCore.compile(compilerNode.toString(), compiler));
                require(!compiled.has("error"), compiler + " compilation: " + compiled.optString("error"));
            }
            config.put("rules", new JSONArray().put("MATCH,PROXY"));
            config.put("dns", new JSONObject().put("enable", false));
            config.put("sniffer", new JSONObject().put("enable", false));
            config.put("find-process-mode", "off");
            File directory = new File(getTargetContext().getFilesDir(), "smoke"); directory.mkdirs();
            File configFile = new File(directory, "config.json");
            Files.write(configFile.toPath(), NativeCore.engineConfig(config).getBytes(StandardCharsets.UTF_8));
            try (ServerSocket upstream = new ServerSocket(17891, 8, InetAddress.getByName("127.0.0.1"))) {
                upstream.setSoTimeout(20000);
                Thread fixture = new Thread(() -> {
                    try (Socket socket = upstream.accept()) {
                        socket.setSoTimeout(10000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String first = reader.readLine();
                        while (!"".equals(reader.readLine())) {}
                        OutputStream out = socket.getOutputStream();
                        if (first.startsWith("CONNECT ")) {
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                            reader.readLine(); while (!"".equals(reader.readLine())) {}
                        }
                        byte[] body = "vproxy-smoke-ok".getBytes(StandardCharsets.US_ASCII);
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(body); out.flush();
                    } catch (Exception error) { android.util.Log.e("VProxySmoke", "Fixture failed", error); }
                }, "local-http-proxy"); fixture.start();
                String engine = new File(getTargetContext().getApplicationInfo().nativeLibraryDir, "libmihomo.so").getAbsolutePath();
                pid = NativeCore.spawn(engine, directory.getAbsolutePath(), configFile.getAbsolutePath(), new File(directory, "core.log").getAbsolutePath(), -1);
                require(pid > 0, "engine spawn: " + pid);
                boolean ready = false;
                for (int i = 0; i < 100; i++) {
                    try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", 9980), 100); ready = true; break; }
                    catch (IOException ignored) { Thread.sleep(100); }
                }
                require(ready, "mixed listener readiness");
                try (Socket connection = new Socket()) {
                    connection.connect(new InetSocketAddress("127.0.0.1", 9980), 5000);
                    connection.setSoTimeout(10000);
                    connection.getOutputStream().write("GET http://198.51.100.7/fixture HTTP/1.1\r\nHost: 198.51.100.7\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) { byte[] buffer = new byte[1024]; int n; while ((n = input.read(buffer)) != -1) body.write(buffer, 0, n); }
                    require(body.toString("UTF-8").contains("vproxy-smoke-ok"), "actual proxied response: " + body.toString("UTF-8"));
                }
                fixture.join(1000);
            }
            NativeCore.terminate(pid, false);
            int exit = NativeCore.waitExit(pid); pid = -1;
            require(exit == 0 || exit == 143, "engine stop: " + exit);
            result.putString("stream", "PASS: parser, three compilers, token, engine start, real local HTTP proxy response, engine stop\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        } finally {
            if (pid > 0) { NativeCore.terminate(pid, true); NativeCore.waitExit(pid); }
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
