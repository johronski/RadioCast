package se.radiocast;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Recognises the network the phone is on, without location permission (which Android requires for
 * the wifi's name): the router's address and the local address range. Used to remember the last
 * speaker per place and to stay quiet when away from home.
 */
public final class NetworkPlace {

    private NetworkPlace() { }

    /** "192.168.1.1|192.168.1.0/24" for the current wifi or wired network, or null (mobile data, none). */
    public static String current(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network n = cm.getActiveNetwork();
        if (n == null) return null;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        if (caps == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null;
        LinkProperties lp = cm.getLinkProperties(n);
        if (lp == null) return null;
        String gateway = "";
        for (RouteInfo r : lp.getRoutes()) {
            if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                gateway = r.getGateway().getHostAddress();
                break;
            }
        }
        String range = "";
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) {
                range = network(a.getAddress(), a.getPrefixLength()) + "/" + a.getPrefixLength();
                break;
            }
        }
        if (gateway.isEmpty() && range.isEmpty()) return null;
        return gateway + "|" + range;
    }

    /** Whether an IPv4 address (a speaker's) is in the phone's current local address range. */
    public static boolean isLocal(Context context, String host) {
        String place = current(context);
        if (place == null || host == null) return false;
        String range = place.substring(place.indexOf('|') + 1);
        int slash = range.indexOf('/');
        if (slash < 0) return false;
        try {
            int prefix = Integer.parseInt(range.substring(slash + 1));
            return range.substring(0, slash).equals(network(InetAddress.getByName(host), prefix));
        } catch (Exception e) {
            return false;
        }
    }

    private static String network(InetAddress address, int prefix) {
        byte[] b = address.getAddress();
        if (b.length != 4) return "";
        int ip = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        int mask = prefix <= 0 ? 0 : (int) (0xffffffffL << (32 - Math.min(prefix, 32)));
        int net = ip & mask;
        return ((net >>> 24) & 0xff) + "." + ((net >>> 16) & 0xff) + "." + ((net >>> 8) & 0xff) + "." + (net & 0xff);
    }
}
