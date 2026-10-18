package se.radiocast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/** Finds Chromecasts on the local network via mDNS (_googlecast._tcp). */
public class CastDiscovery {

    public interface Listener {
        /**
         * @param id     the speaker's unique id (stays the same when its IP address changes), or ""
         * @param status what it is playing right now, e.g. "Spotify", or "" when idle
         */
        void onFound(String name, String host, int port, String id, String status);
        void onError(String message);
    }

    private static final String SERVICE_TYPE = "_googlecast._tcp.";

    private final NsdManager nsd;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener discoveryListener;

    public CastDiscovery(Context context, Listener listener) {
        this.nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void start() {
        stop();
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) {
                main.post(() -> listener.onError("Sökningen kunde inte starta (kod " + code + ")."));
            }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceFound(NsdServiceInfo info) { enqueue(info); }
            @Override public void onServiceLost(NsdServiceInfo info) { }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException e) {
            discoveryListener = null;
            listener.onError("Sökningen kunde inte starta.");
        }
    }

    public void stop() {
        if (discoveryListener != null) {
            try { nsd.stopServiceDiscovery(discoveryListener); } catch (RuntimeException ignored) { }
            discoveryListener = null;
        }
        synchronized (queue) {
            queue.clear();
        }
    }

    private void enqueue(NsdServiceInfo info) {
        synchronized (queue) {
            queue.add(info);
        }
        resolveNext();
    }

    // NsdManager resolves one service at a time, so resolve them in turn.
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (queue) {
            if (resolving || queue.isEmpty()) return;
            next = queue.poll();
            resolving = true;
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int code) { done(); }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    String name = friendlyName(info);
                    String host = info.getHost() != null ? info.getHost().getHostAddress() : null;
                    int port = info.getPort();
                    String id = attribute(info, "id");
                    String status = attribute(info, "rs");
                    if (host != null) main.post(() -> listener.onFound(name, host, port, id, status));
                    done();
                }
            });
        } catch (RuntimeException e) {
            done();
        }
    }

    private void done() {
        synchronized (queue) {
            resolving = false;
        }
        main.postDelayed(this::resolveNext, 50);
    }

    private static String friendlyName(NsdServiceInfo info) {
        String fn = attribute(info, "fn");
        return fn.isEmpty() ? info.getServiceName() : fn;
    }

    /** A value the speaker announces about itself (fn = name, id = unique id, rs = what it plays). */
    private static String attribute(NsdServiceInfo info, String key) {
        Map<String, byte[]> attrs = info.getAttributes();
        if (attrs == null) return "";
        byte[] v = attrs.get(key);
        return v == null ? "" : new String(v, StandardCharsets.UTF_8).trim();
    }
}
