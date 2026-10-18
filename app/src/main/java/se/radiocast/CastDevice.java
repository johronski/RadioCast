package se.radiocast;

/** A Chromecast on the local network. */
public final class CastDevice {
    public volatile String name;
    public final String host;
    public final int port;

    public CastDevice(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** Identifies a speaker. Speaker groups share the host's IP but use their own port. */
    public String key() {
        return host + ":" + port;
    }
}
