package io.github.heavencanceller.vproxy;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** A separate probe UID must send the request through the VPN TUN. */
public final class VpnSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try (ServerSocket upstream = new ServerSocket(17891, 8, InetAddress.getByName("127.0.0.1"))) {
            upstream.setSoTimeout(120000);
            if (VpnService.prepare(getTargetContext()) != null) throw new Exception("VPN consent missing");
            startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            JSONArray parsed = new JSONArray(NativeCore.parse("http://fixture@127.0.0.1:17891#TunnelFixture"));
            String config = NativeCore.compile(parsed.getJSONObject(0).toString(), "mihomo");
            config = new JSONObject(config).put("log-level", "debug").toString();
            getTargetContext().startForegroundService(new Intent(getTargetContext(), ProxyVpnService.class).putExtra("config", config));
            for (int i = 0; i < 200; i++) {
                if (ProxyVpnService.status.startsWith("VPN 已连接")) break;
                if (ProxyVpnService.status.startsWith("连接失败") || ProxyVpnService.status.startsWith("内核已退出")) throw new Exception(ProxyVpnService.status);
                Thread.sleep(100);
            }
            if (!ProxyVpnService.status.startsWith("VPN 已连接")) throw new Exception("VPN readiness timeout: " + ProxyVpnService.status);
            Bundle progress = new Bundle(); progress.putString("stream", "VPN_READY: run the separate TunnelProbe instrumentation now\n"); sendStatus(0, progress);
            int handled = 0;
            long deadline = System.currentTimeMillis() + 120000;
            while (handled < 2 && System.currentTimeMillis() < deadline) {
              try (Socket socket = upstream.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line = input.readLine();
                String header;
                while ((header = input.readLine()) != null && !header.isEmpty()) {}
                OutputStream out = socket.getOutputStream();
                if (line == null || (!line.contains("198.51.100.7") && !line.contains("2001:db8::7"))) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    continue;
                }
                if (line.startsWith("CONNECT ")) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    input.readLine(); while (!"".equals(input.readLine())) {}
                }
                byte[] body = "vproxy-tun-ok".getBytes(StandardCharsets.US_ASCII);
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body); out.flush();
                handled++;
              } catch (SocketTimeoutException ignored) {}
            }
            if (handled != 2) throw new Exception("Expected both IPv4 and IPv6 probe requests");
            Thread.sleep(1500);
            getTargetContext().startService(new Intent(getTargetContext(), ProxyVpnService.class).setAction("stop"));
            for (int i = 0; i < 50 && ProxyVpnService.running; i++) Thread.sleep(100);
            if (ProxyVpnService.running) throw new Exception("Service failed to stop");
            result.putString("stream", "PASS: VPN service, inherited TUN FD, separate-UID IPv4/IPv6 traffic reached local upstream, service stopped\n");
            finish(-1, result);
        } catch (Throwable error) {
            getTargetContext().startService(new Intent(getTargetContext(), ProxyVpnService.class).setAction("stop"));
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(0, result);
        }
    }
}
