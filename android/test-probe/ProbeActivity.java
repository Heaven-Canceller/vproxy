package io.github.heavencanceller.vproxyprobe;

public final class ProbeActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText("VProxy: testing VPN DNS, IPv4 and IPv6 with a separate app UID.");
        view.setPadding(24, 80, 24, 24);
        setContentView(view);
    }
}
