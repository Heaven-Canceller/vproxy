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
            CoreClient.call(getTargetContext(),"POST","/android/import",new JSONObject().put("content","http://fixture@127.0.0.1:17891#TunnelFixture"));
            JSONObject node=CoreClient.object(getTargetContext(),"GET","/api/nodes?keyword=TunnelFixture",null).getJSONArray("items").getJSONObject(0);
            JSONObject options=CoreClient.object(getTargetContext(),"GET","/api/proxy/config",null);
            options.put("routing_mode","global").put("url_test",false)
                .put("override_yaml","mode: rule\nrules:\n  - DST-PORT,25,REJECT\n  - MATCH,PROXY\n")
                .put("override_script",JSONObject.NULL);
            CoreClient.call(getTargetContext(),"PUT","/api/proxy/config",options);
            String config = CoreClient.config(getTargetContext(),node.getString("id")).put("log-level","debug").toString();
            getTargetContext().getSharedPreferences("MainActivity",0).edit().putBoolean("android_api",true).commit();
            getTargetContext().startForegroundService(new Intent(getTargetContext(), ProxyVpnService.class).putExtra("config", config));
            for (int i = 0; i < 200; i++) {
                if (ProxyVpnService.status.startsWith("VPN 已连接")) break;
                if (ProxyVpnService.status.startsWith("连接失败") || ProxyVpnService.status.startsWith("内核已退出")) throw new Exception(ProxyVpnService.status);
                Thread.sleep(100);
            }
            if (!ProxyVpnService.status.startsWith("VPN 已连接")) throw new Exception("VPN readiness timeout: " + ProxyVpnService.status);
            JSONObject runtime=ControllerClient.request("GET","/proxies",null);
            if(!runtime.getJSONObject("proxies").has("PROXY"))throw new Exception("Runtime proxy group missing");
            JSONObject rules=ControllerClient.request("GET","/rules",null);
            if(!rules.toString().contains("REJECT"))throw new Exception("v2 custom rules did not reach runtime");
            JSONObject group=runtime.getJSONObject("proxies").getJSONObject("PROXY");
            ControllerClient.request("PUT","/proxies/PROXY",new JSONObject().put("name",group.getString("now")));
            ControllerClient.request("GET","/connections",null);
            try(Socket api=new Socket("127.0.0.1",18080)) {
                api.setSoTimeout(5000);
                api.getOutputStream().write(("GET /api/proxy/status HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+LocalApiServer.token(getTargetContext())+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response=new BufferedReader(new InputStreamReader(api.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                if(response==null||!response.contains("200"))throw new Exception("VPN service API not available");
            }
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
            getTargetContext().getSharedPreferences("MainActivity",0).edit().putBoolean("android_api",false).commit();
            options.put("override_yaml",JSONObject.NULL);
            CoreClient.call(getTargetContext(),"PUT","/api/proxy/config",options);
            Thread.sleep(500);
            JSONObject unsupported=new JSONObject(config).put("rules",new JSONArray().put("PROCESS-NAME,example,DIRECT").put("MATCH,PROXY"));
            getTargetContext().startForegroundService(new Intent(getTargetContext(),ProxyVpnService.class).putExtra("config",unsupported.toString()));
            for(int i=0;i<50&&!ProxyVpnService.status.startsWith("连接失败");i++)Thread.sleep(100);
            if(!ProxyVpnService.status.contains("无法匹配其他进程")||ProxyVpnService.running)throw new Exception("Unsupported process rules were not rejected");
            ProxyVpnService.status="已停止";
            result.putString("stream", "PASS: v2 database-compiled config, custom runtime rule, authenticated groups/switch/connections, service-owned API, VPN TUN, separate-UID IPv4/IPv6 traffic, service stopped, unsupported process rules rejected\n");
            finish(-1, result);
        } catch (Throwable error) {
            getTargetContext().startService(new Intent(getTargetContext(), ProxyVpnService.class).setAction("stop"));
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(0, result);
        }
    }
}
