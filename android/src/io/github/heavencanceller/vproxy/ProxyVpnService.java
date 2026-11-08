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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class ProxyVpnService extends VpnService {
    static volatile String status = "已停止";
    static volatile boolean running;
    static volatile String controllerSecret;
    static volatile int controllerPort=9090,mixedPort=9980;
    private LocalApiServer localApi;
    private ScheduledExecutorService maintenance;
    static volatile String activeNodeId="",activeNodeName="";
    private int healthFailures;
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
        activeNodeId=intent.getStringExtra("node_id");activeNodeName=intent.getStringExtra("node_name");
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
            org.json.JSONArray routeRules=config.optJSONArray("rules");
            if(routeRules!=null)for(int i=0;i<routeRules.length();i++)if(routeRules.optString(i).contains("PROCESS-"))
                throw new Exception("Android 普通应用无法匹配其他进程，请改用设置中的应用绕过或域名/IP 规则");
            Builder builder = new Builder().setSession("VProxy").setMtu(1500)
                .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
                .addDnsServer("172.19.0.2");
            builder.addDisallowedApplication(getPackageName());
            String bypass=getSharedPreferences("MainActivity",0).getString("bypass_apps","");
            for(String name:bypass.split("\n")) {
                String packageName=name.trim();
                if(!packageName.isEmpty()&&!packageName.equals(getPackageName())) {
                    try { builder.addDisallowedApplication(packageName); }
                    catch(android.content.pm.PackageManager.NameNotFoundException error) { throw new Exception("找不到绕过应用："+packageName); }
                }
            }
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
            String geo=config.optString("_vproxy_geo_dir","");config.remove("_vproxy_geo_dir");
            if(!geo.isEmpty())for(String name:new String[]{"geosite.dat","geoip.dat","country.mmdb"}){
                File source=new File(geo,name);if(source.isFile())Files.copy(source.toPath(),new File(directory,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            mixedPort=config.optInt("mixed-port",9980);
            String controller=config.optString("external-controller","127.0.0.1:9090");
            controllerPort=Integer.parseInt(controller.substring(controller.lastIndexOf(':')+1));
            config.put("external-controller","127.0.0.1:"+controllerPort);
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
            controllerSecret=secret;
            for (int attempt = 0; attempt < 50 && !stopping.get(); attempt++) {
                ready = false;
                try {
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:"+controllerPort+"/version").openConnection(java.net.Proxy.NO_PROXY);
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
                status = "VPN 已连接 · 本地代理 127.0.0.1:"+mixedPort;
                getSystemService(NotificationManager.class).notify(1, notification(status));
                if(getSharedPreferences("MainActivity",0).getBoolean("android_api",false))localApi=new LocalApiServer(this);
                if(getSharedPreferences("MainActivity",0).getBoolean("android_failover",false)) {
                    maintenance=Executors.newSingleThreadScheduledExecutor();
                    maintenance.scheduleWithFixedDelay(this::checkHealth,45,60,TimeUnit.SECONDS);
                }
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
        if(localApi!=null){localApi.close();localApi=null;}
        if(maintenance!=null){maintenance.shutdownNow();maintenance=null;}
        controllerSecret=null;
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

    private void checkHealth() {
        if(stopping.get()||!running)return;
        boolean ok=false;String error="";long start=android.os.SystemClock.elapsedRealtime();
        try {
            java.net.Proxy proxy=new java.net.Proxy(java.net.Proxy.Type.HTTP,new java.net.InetSocketAddress("127.0.0.1",mixedPort));
            java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("https://www.gstatic.com/generate_204").openConnection(proxy);
            c.setConnectTimeout(5000);c.setReadTimeout(10000);
            try{ok=c.getResponseCode()==204;}finally{c.disconnect();}
        }catch(Exception e){error=e.getMessage();}
        try{CoreClient.call(this,"POST","/android/health",new JSONObject().put("ok",ok).put("latency_ms",android.os.SystemClock.elapsedRealtime()-start).put("error",error).put("node_id",activeNodeId).put("node_name",activeNodeName));}catch(Exception ignored){}
        healthFailures=ok?0:healthFailures+1;
        if(healthFailures<2||stopping.get())return;
        try{
            JSONObject proxies=ControllerClient.request("GET","/proxies",null).getJSONObject("proxies");
            JSONObject group=proxies.getJSONObject("PROXY");org.json.JSONArray candidates=group.getJSONArray("all");
            String current=group.optString("now");
            for(int i=0;i<candidates.length();i++) {
                String candidate=candidates.getString(i);if(candidate.equals(current)||candidate.equals("DIRECT"))continue;
                try {
                    JSONObject delay=ControllerClient.request("GET","/proxies/"+CoreClient.encode(candidate)+"/delay?timeout=5000&url="+CoreClient.encode("https://www.gstatic.com/generate_204"),null);
                    if(delay.optInt("delay",0)>0){ControllerClient.request("PUT","/proxies/PROXY",new JSONObject().put("name",candidate));healthFailures=0;activeNodeId="";activeNodeName=candidate;status="VPN 已连接 · 自动切换至 "+candidate;getSystemService(NotificationManager.class).notify(1,notification(status));break;}
                }catch(Exception ignored){}
            }
        }catch(Exception ignored){}
    }

    @Override public void onRevoke() { shutdown(); super.onRevoke(); }
    @Override public void onDestroy() { shutdown(); worker.shutdownNow(); super.onDestroy(); }
}
