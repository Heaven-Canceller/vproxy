package io.github.heavencanceller.vproxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private JSONArray nodes = new JSONArray();
    private int selected;
    private EditText input;
    private TextView status;
    private TextView summary;
    private Spinner selector;
    private Button connect;
    private String pendingConfig;
    private String exportText;
    private boolean busy;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!busy) status.setText(ProxyVpnService.status);
            connect.setText(ProxyVpnService.running ? "断开 VPN" : "连接 VPN");
            handler.postDelayed(this, 800);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(24, 40, 65));
        getWindow().setNavigationBarColor(Color.rgb(245, 247, 251));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(30), dp(22), dp(26));
        layout.setBackgroundColor(Color.rgb(245, 247, 251));
        scroll.addView(layout);
        TextView title = text("VProxy", 32, Color.rgb(24, 40, 65));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(title);
        layout.addView(text("ANDROID  ·  Rust 核心 + Mihomo", 13, Color.rgb(75, 94, 121)));
        status = text("已停止", 16, Color.rgb(20, 100, 100));
        status.setPadding(0, dp(24), 0, dp(18));
        layout.addView(status);
        layout.addView(text("导入订阅或节点", 19, Color.rgb(24, 40, 65)));
        input = new EditText(this);
        input.setHint("粘贴 HTTPS 订阅地址、节点链接、Base64 或 Clash 配置");
        input.setMinLines(3);
        input.setMaxLines(7);
        input.setTextSize(14);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        layout.addView(input, full());
        LinearLayout importRow = row();
        button(importRow, "解析 / 拉取", () -> importContent(input.getText().toString().trim()));
        button(importRow, "从文件导入", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 12));
        layout.addView(importRow);
        summary = text("尚未导入节点", 14, Color.rgb(75, 94, 121));
        summary.setPadding(0, dp(18), 0, dp(8));
        layout.addView(summary);
        selector = new Spinner(this);
        layout.addView(selector, full());
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selected = position;
                getPreferences(MODE_PRIVATE).edit().putInt("selected", position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        LinearLayout nodeRow = row();
        button(nodeRow, "TCP 测速", this::measureLatency);
        button(nodeRow, "导出配置", this::chooseExport);
        layout.addView(nodeRow);
        connect = new Button(this);
        connect.setText("连接 VPN");
        connect.setTextColor(Color.WHITE);
        connect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30, 100, 165)));
        connect.setOnClickListener(view -> toggleVpn());
        LinearLayout.LayoutParams connectParams = full();
        connectParams.topMargin = dp(18);
        layout.addView(connect, connectParams);
        layout.addView(text("VPN 接管其他应用的流量；VProxy 自身直连以避免代理回环。本地 HTTP / SOCKS5 端口为 127.0.0.1:9980。", 12, Color.rgb(75, 94, 121)));
        LinearLayout info = row();
        button(info, "运行日志", this::showLogs);
        button(info, "关于 / 开源许可", this::showAbout);
        layout.addView(info);
        setContentView(scroll);
        try {
            File data = new File(getFilesDir(), "nodes.json");
            if (data.isFile()) nodes = new JSONArray(new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8));
        } catch (Exception error) { message("读取节点失败：" + error.getMessage()); }
        selected = getPreferences(MODE_PRIVATE).getInt("selected", 0);
        updateNodes();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 20);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private void button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(13);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void message(String value) { new AlertDialog.Builder(this).setMessage(value).setPositiveButton("确定", null).show(); }
    private void updateNodes() {
        String[] labels = new String[nodes.length()];
        for (int i = 0; i < labels.length; i++) {
            JSONObject node = nodes.optJSONObject(i);
            labels[i] = node.optString("name") + " · " + node.optString("protocol") + " · " + node.optString("server") + ":" + node.optInt("port");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter);
        selected = Math.min(Math.max(selected, 0), Math.max(0, labels.length - 1));
        selector.setSelection(selected);
        summary.setText("节点池 · " + nodes.length() + " 个节点");
    }
    private void work(String label, ThrowingSupplier task, java.util.function.Consumer<String> done) {
        if (busy) return;
        busy = true; status.setText(label);
        worker.execute(() -> {
            try {
                String result = task.get();
                handler.post(() -> { busy = false; if (!isFinishing()) done.accept(result); });
            } catch (Throwable error) {
                handler.post(() -> { busy = false; if (!isFinishing()) message("操作失败：" + error.getMessage()); });
            }
        });
    }
    interface ThrowingSupplier { String get() throws Exception; }

    private static String readLimited(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > 4 * 1024 * 1024) throw new IOException("输入超过 4 MiB 上限");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
    private String fetch(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new IOException("订阅地址须使用 HTTPS");
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "VProxy-Android/1.16.7");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("订阅服务器返回 HTTP " + connection.getResponseCode());
            return readLimited(connection.getInputStream());
        } finally { connection.disconnect(); }
    }
    private void importContent(String value) {
        if (value.isEmpty()) { message("请先粘贴订阅内容或选择文件"); return; }
        work("正在解析订阅…", () -> {
            String content = value.startsWith("https://") && !value.contains("\n") ? fetch(value) : value;
            return NativeCore.parse(content);
        }, result -> {
            try {
                JSONArray parsed = new JSONArray(result);
                if (parsed.length() == 0) { message("未识别到节点，请检查订阅格式"); return; }
                nodes = parsed; selected = 0;
                Files.write(new File(getFilesDir(), "nodes.json").toPath(), nodes.toString().getBytes(StandardCharsets.UTF_8));
                input.setText(""); updateNodes();
                Toast.makeText(this, "已导入 " + nodes.length() + " 个节点", Toast.LENGTH_LONG).show();
            } catch (Exception error) { message("解析失败：" + error.getMessage()); }
        });
    }
    private JSONObject selectedNode() throws Exception {
        if (nodes.length() == 0) throw new Exception("请先导入节点");
        return nodes.getJSONObject(selected);
    }
    private void measureLatency() {
        work("正在测量 TCP 延迟…", () -> {
            JSONObject node = selectedNode();
            long start = android.os.SystemClock.elapsedRealtime();
            try (Socket socket = new Socket(Proxy.NO_PROXY)) {
                socket.connect(new InetSocketAddress(node.getString("server"), node.getInt("port")), 4000);
            }
            return "TCP 握手：" + (android.os.SystemClock.elapsedRealtime() - start) + " ms\n该结果仅代表端口连通性，VPN 连接后的实际代理可用性需另行验证。";
        }, this::message);
    }
    private void chooseExport() {
        new AlertDialog.Builder(this).setTitle("导出内核配置").setItems(new String[]{"Mihomo", "sing-box", "Xray"}, (dialog, index) -> {
            String kernel = new String[]{"mihomo", "sing-box", "xray"}[index];
            work("正在编译配置…", () -> NativeCore.compile(selectedNode().toString(), kernel), result -> {
                try {
                    if (result.startsWith("{") && new JSONObject(result).has("error")) throw new Exception(new JSONObject(result).getString("error"));
                    exportText = kernel.equals("mihomo") ? NativeCore.engineConfig(new JSONObject(result)) : result;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "vproxy-" + kernel + ".json"), 13);
                } catch (Exception error) { message(error.getMessage()); }
            });
        }).show();
    }
    private void toggleVpn() {
        if (ProxyVpnService.running) { startService(new Intent(this, ProxyVpnService.class).setAction("stop")); return; }
        work("正在生成代理配置…", () -> NativeCore.compile(selectedNode().toString(), "mihomo"), result -> {
            try {
                JSONObject config = new JSONObject(result);
                if (config.has("error")) throw new Exception(config.getString("error"));
                pendingConfig = result;
                Intent prepare = VpnService.prepare(this);
                if (prepare != null) startActivityForResult(prepare, 11); else startVpn();
            } catch (Exception error) { message(error.getMessage()); }
        });
    }
    private void startVpn() {
        startForegroundService(new Intent(this, ProxyVpnService.class).putExtra("config", pendingConfig));
        pendingConfig = null;
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 11) {
            if (result == RESULT_OK) startVpn(); else { pendingConfig = null; message("未获得 VPN 授权"); }
        } else if (request == 12 && result == RESULT_OK && data != null) {
            work("正在读取文件…", () -> readLimited(getContentResolver().openInputStream(data.getData())), this::importContent);
        } else if (request == 13 && result == RESULT_OK && data != null && exportText != null) {
            try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                stream.write(exportText.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "配置已导出，文件包含节点凭据，请妥善保管", Toast.LENGTH_LONG).show();
            } catch (Exception error) { message("导出失败：" + error.getMessage()); }
            finally { exportText = null; }
        }
    }
    private void showLogs() {
        try {
            File log = new File(getFilesDir(), "core/mihomo.log");
            String value = "暂无运行日志";
            if (log.isFile()) {
                try (RandomAccessFile file = new RandomAccessFile(log, "r")) {
                    file.seek(Math.max(0, file.length() - 24000));
                    byte[] bytes = new byte[(int)(file.length() - file.getFilePointer())];
                    file.readFully(bytes); value = new String(bytes, StandardCharsets.UTF_8);
                }
            }
            TextView content = text(value, 11, Color.DKGRAY); content.setTextIsSelectable(true); content.setPadding(dp(14), dp(8), dp(14), dp(8));
            ScrollView scroll = new ScrollView(this); scroll.addView(content);
            new AlertDialog.Builder(this).setTitle("Mihomo 运行日志").setView(scroll).setPositiveButton("关闭", null).show();
        } catch (Exception error) { message(error.getMessage()); }
    }
    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("VProxy Android 1.16.7-android.1")
            .setMessage("Android 移植版 · arm64-v8a\n\nVProxy 核心：mateo-ki/vproxy，MIT\nMihomo：MetaCubeX/mihomo v1.19.33，GPL-3.0\n\n本应用不提供代理节点。节点凭据仅保存在应用私有目录。\n\n这是首版移植，桌面版的多内核管理、自动订阅调度和智能分流界面尚未迁移。")
            .setNeutralButton("许可证", (dialog, which) -> {
                try { message(readLimited(getAssets().open("THIRD_PARTY_NOTICES.txt"))); } catch (Exception error) { message(error.getMessage()); }
            }).setPositiveButton("关闭", null).show();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacks(refresh); worker.shutdownNow(); super.onDestroy(); }
}
