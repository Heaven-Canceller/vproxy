package io.github.heavencanceller.vproxyprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class TunnelProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
          startActivitySync(new android.content.Intent(getTargetContext(), ProbeActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
          waitForIdleSync();
          // Instrumentation starts before Android's UID network policy settles.
          Thread.sleep(500);
          android.net.ConnectivityManager connectivity = getTargetContext().getSystemService(android.net.ConnectivityManager.class);
          android.net.Network network = connectivity.getActiveNetwork();
          android.net.NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
          if (capabilities == null || !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) throw new Exception("VPN is not the probe UID's active network");
          InetAddress[] answers = network.getAllByName("android-vproxy-smoke.example.com");
          boolean fakeIp = false;
          for (InetAddress answer : answers) {
            byte[] bytes = answer.getAddress();
            fakeIp |= bytes.length == 4 && (bytes[0] & 255) == 198 && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19);
          }
          if (!fakeIp) throw new Exception("Android resolver did not receive a VPN fake-IP DNS answer");
          for (String address : new String[]{"198.51.100.7", "2001:db8::7"}) {
           try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, 80), 10000);
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(("GET /tunnel HTTP/1.1\r\nHost: " + address + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int n;
            while ((n = socket.getInputStream().read(buffer)) != -1) response.write(buffer, 0, n);
            if (!response.toString("UTF-8").contains("vproxy-tun-ok")) throw new Exception("Unexpected tunnel response: " + response.toString("UTF-8"));
           }
          }
            result.putString("stream", "PASS: Android resolver received VPN DNS answer; independent application UID received vproxy-tun-ok over IPv4 and IPv6 through the VPN\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n" + android.util.Log.getStackTraceString(error)); finish(0, result);
        }
    }
}
