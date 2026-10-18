package se.radiocast;

/** A Chromecast on the local network. */
public final class CastDevice {
    public volatile String name;
    public volatile String host;
    public volatile int port;
    /** Unique id announced by the speaker; stays the same when the IP address changes. "" if unknown. */
    public volatile String id = "";
    /** What it is doing, e.g. "spelar Spotify" or "ledig". "" until known. */
    public volatile String status = "";
    /** A speaker group made in Google Home: several speakers playing in sync. */
    public volatile boolean group;

    public CastDevice(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** "Kök · spelar Spotify" for the speaker list. */
    public String label() {
        return status.isEmpty() ? name : name + "  \u00B7  " + status;
    }

    /** Identifies a speaker. Speaker groups share the host's IP but use their own port. */
    public String key() {
        return host + ":" + port;
    }
}
