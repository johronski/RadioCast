package se.radiocast;

import java.util.Locale;

/** A radio channel in the user's list (or one being tried out from the search). */
public final class Station {
    public final String name;
    public final String url;
    /** ISO country code such as "SE", or "" when unknown. */
    public final String countryCode;
    public final String language;
    public final int bitrate;

    public Station(String name, String url, String countryCode, String language, int bitrate) {
        this.name = name;
        this.url = url;
        this.countryCode = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        this.language = language == null ? "" : language;
        this.bitrate = bitrate;
    }

    /** For channels saved without a country (older versions, own links): a best guess from the address. */
    public static Station withGuessedCountry(String name, String url) {
        return new Station(name, url, StationGroups.guessCountry(url), "", 0);
    }
}
