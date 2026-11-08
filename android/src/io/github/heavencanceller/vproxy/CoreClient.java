package io.github.heavencanceller.vproxy;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class CoreClient {
    private CoreClient() {}
    static Object call(Context context, String method, String path, JSONObject body) throws Exception {
        String raw = NativeCore.request(context.getFilesDir().getAbsolutePath(),
            context.getApplicationInfo().nativeLibraryDir, method, path, body == null ? "" : body.toString());
        if (raw == null) throw new IOException("Native management returned no response");
        JSONObject response = new JSONObject(raw);
        if (response.has("error")) throw new IOException(response.optString("error"));
        if (!response.optBoolean("ok")) throw new IOException(response.optString("message", "操作失败"));
        Object data=response.opt("data");
        android.content.SharedPreferences prefs=context.getSharedPreferences("MainActivity",0);
        if(path.equals("/android/backup")&&data instanceof JSONObject){
            ((JSONObject)data).put("android",new JSONObject().put("selectedId",prefs.getString("selectedId",""))
                .put("selectedName",prefs.getString("selectedName",""))
                .put("bypass_apps",prefs.getString("bypass_apps",""))
                .put("android_failover",prefs.getBoolean("android_failover",false)));
        }
        if(path.equals("/android/restore")&&body!=null){
            JSONObject settings=body.optJSONObject("android");
            if(settings!=null)prefs.edit().putString("selectedId",settings.optString("selectedId"))
                .putString("selectedName",settings.optString("selectedName"))
                .putString("bypass_apps",settings.optString("bypass_apps"))
                .putBoolean("android_failover",settings.optBoolean("android_failover")).apply();
        }
        return data;
    }
    static JSONObject object(Context c, String method, String path, JSONObject body) throws Exception {
        Object value = call(c, method, path, body);
        if (!(value instanceof JSONObject)) throw new IOException("Expected an object from " + path);
        return (JSONObject)value;
    }
    static JSONObject config(Context c, String nodeId) throws Exception {
        JSONObject config=object(c,"POST","/android/compile",new JSONObject().put("node_id",nodeId).put("kernel","mihomo")
            .put("candidates",c.getSharedPreferences("MainActivity",0).getBoolean("android_failover",false))).getJSONObject("config");
        if(config.optBoolean("_vproxy_geo_required")&&!config.optBoolean("_vproxy_geo_ready"))call(c,"POST","/android/geo",new JSONObject());
        return config;
    }
    static String read(InputStream source, int limit) throws Exception {
        if (source == null) throw new IOException("Cannot open document");
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > limit) throw new IOException("文件超过 " + limit / 1048576 + " MiB 上限");
                output.write(buffer,0,n);
            }
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static String encode(String value) {
        try { return java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20"); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
}
