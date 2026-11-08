package io.github.heavencanceller.vproxy;

final class NativeCore {
    static {
        System.loadLibrary("vproxy");
        System.loadLibrary("vproxy_process");
    }

    private NativeCore() {}

    static String engineConfig(org.json.JSONObject config) throws org.json.JSONException {
        // Preserve v2 rules. Unconfigured global mode initially selects DIRECT.
        if ("global".equals(config.optString("mode"))) {
            config.put("mode", "rule");
            config.put("rules", new org.json.JSONArray().put("MATCH,PROXY"));
        }
        org.json.JSONObject dns = config.optJSONObject("dns");
        if (dns != null && !config.optBoolean("_vproxy_android_v2")) {
            org.json.JSONObject policy = dns.optJSONObject("nameserver-policy");
            if (policy != null) {
                java.util.ArrayList<String> remove = new java.util.ArrayList<>();
                java.util.Iterator<String> keys = policy.keys();
                while (keys.hasNext()) { String key = keys.next(); if (key.startsWith("geosite:")) remove.add(key); }
                for (String key : remove) policy.remove(key);
            }
        }
        config.remove("_vproxy_android_v2");
        config.remove("_vproxy_geo_required");config.remove("_vproxy_geo_ready");config.remove("_vproxy_geo_dir");
        // Android escapes slashes in JSON; Mihomo's YAML reader rejects that escape.
        return config.toString(2).replace("\\/", "/");
    }

    static native String parse(String content);
    static native String compile(String nodeJson, String kernel);
    static native String request(String root, String nativeDir, String method, String path, String body);
    static native int spawn(String engine, String directory, String config, String log, int tunFd);
    static native int waitExit(int pid);
    static native void terminate(int pid, boolean force);
}
