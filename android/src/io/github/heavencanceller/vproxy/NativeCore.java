package io.github.heavencanceller.vproxy;

final class NativeCore {
    static {
        System.loadLibrary("vproxy");
        System.loadLibrary("vproxy_process");
    }

    private NativeCore() {}

    static String engineConfig(org.json.JSONObject config) throws org.json.JSONException {
        // GLOBAL defaults to DIRECT; a match-all rule selects the compiled node group.
        config.put("mode", "rule");
        config.put("rules", new org.json.JSONArray().put("MATCH,PROXY"));
        // Match-all mobile routing does not need desktop GeoSite DNS policies.
        org.json.JSONObject dns = config.optJSONObject("dns");
        if (dns != null) dns.remove("nameserver-policy");
        // Android escapes slashes in JSON; Mihomo's YAML reader rejects that escape.
        return config.toString(2).replace("\\/", "/");
    }

    static native String parse(String content);
    static native String compile(String nodeJson, String kernel);
    static native int spawn(String engine, String directory, String config, String log, int tunFd);
    static native int waitExit(int pid);
    static native void terminate(int pid, boolean force);
}
