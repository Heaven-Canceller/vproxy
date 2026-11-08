package io.github.heavencanceller.vproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class ProxyVpnService extends VpnService {
    static volatile String status = "已停止";
    static volatile boolean running;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile int pid = -1;
    private ParcelFileDescriptor tunnel;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (running || pid > 0) return START_NOT_STICKY;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("proxy", "VPN 连接", NotificationManager.IMPORTANCE_LOW));
        Notification notification = notification("正在启动代理…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        else startForeground(1, notification);
        running = true;
        status = "正在启动…";
        stopping.set(false);
        worker.execute(() -> startEngine(intent.getStringExtra("config")));
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProxyVpnService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "proxy").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("VProxy").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "断开", stop).build()).build();
    }

    private void startEngine(String input) {
        try {
            JSONObject config = new JSONObject(input);
            if (config.has("error")) throw new Exception(config.optString("error"));
            Builder builder = new Builder().setSession("VProxy").setMtu(1500)
                .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
                .addDnsServer("172.19.0.2");
            builder.addDisallowedApplication(getPackageName());
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            tunnel = builder.establish();
            if (tunnel == null) throw new Exception("VPN 授权已失效，请重新连接");
            JSONObject tun = new JSONObject().put("enable", true).put("stack", "gvisor")
                .put("auto-route", false).put("auto-detect-interface", false)
                .put("file-descriptor", tunnel.getFd()).put("mtu", 1500)
                .put("dns-hijack", new org.json.JSONArray().put("any:53"));
            config.put("tun", tun);
            config.put("ipv6", true);
            config.put("bind-address", "127.0.0.1");
            config.put("find-process-mode", "off");
            File directory = new File(getFilesDir(), "core");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("无法创建内核数据目录");
            File configFile = new File(directory, "config.json");
            Files.write(configFile.toPath(), NativeCore.engineConfig(config).getBytes(StandardCharsets.UTF_8));
            File log = new File(directory, "mihomo.log");
            Files.write(log.toPath(), new byte[0]);
            String engine = new File(getApplicationInfo().nativeLibraryDir, "libmihomo.so").getAbsolutePath();
            if (stopping.get()) return;
            pid = NativeCore.spawn(engine, directory.getAbsolutePath(), configFile.getAbsolutePath(), log.getAbsolutePath(), tunnel.getFd());
            if (pid < 0) throw new Exception("启动内核失败，系统错误码 " + (-pid));
            final int process = pid;
            new Thread(() -> {
                int code = NativeCore.waitExit(process);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (pid == process) {
                        pid = -1;
                        if (!stopping.get()) status = "内核已退出 (" + code + ")，请查看日志";
                        shutdown();
                    }
                });
            }, "mihomo-exit").start();
            if (stopping.get()) NativeCore.terminate(process, true);
            boolean ready = false;
            String secret = config.getString("secret");
            for (int attempt = 0; attempt < 50 && !stopping.get(); attempt++) {
                ready = false;
                try {
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:9090/version").openConnection(java.net.Proxy.NO_PROXY);
                    connection.setConnectTimeout(300);
                    connection.setReadTimeout(300);
                    connection.setRequestProperty("Authorization", "Bearer " + secret);
                    try { ready = connection.getResponseCode() == 200; } finally { connection.disconnect(); }
                } catch (Exception ignored) {}
                if (ready) {
                    String startup = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
                    if (startup.contains("Start TUN listening error:")) throw new Exception("TUN 初始化失败，请查看日志");
                    ready = startup.contains("[TUN] Tun adapter listening at:");
                    if (ready) break;
                }
                Thread.sleep(200);
            }
            if (!ready && !stopping.get()) throw new Exception("内核未就绪，请查看日志");
            if (!stopping.get()) {
                status = "VPN 已连接 · 本地代理 127.0.0.1:9980";
                getSystemService(NotificationManager.class).notify(1, notification(status));
            }
        } catch (Exception error) {
            if (!stopping.get()) status = "连接失败：" + error.getMessage();
            shutdown();
        } finally {
            if (stopping.get()) closeTunnel();
        }
    }

    private synchronized void closeTunnel() {
        if (tunnel != null) {
            try { tunnel.close(); } catch (Exception ignored) {}
            tunnel = null;
        }
    }

    private void shutdown() {
        stopping.set(true);
        int process = pid;
        if (process > 0) {
            NativeCore.terminate(process, false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (pid == process) NativeCore.terminate(process, true);
            }, 1500);
        }
        closeTunnel();
        running = false;
        if (!status.startsWith("连接失败") && !status.startsWith("内核已退出")) status = "已停止";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onRevoke() { shutdown(); super.onRevoke(); }
    @Override public void onDestroy() { shutdown(); worker.shutdownNow(); super.onDestroy(); }
}
