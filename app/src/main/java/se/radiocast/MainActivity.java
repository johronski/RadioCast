package se.radiocast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Design B: header with the output (cast chip) and a menu, search, order pills, the station list
 * and a now-playing card. Search is a full-screen window; the cast picker is a sheet from the bottom.
 * The cast connection itself lives in {@link Radio}, so it outlives this screen.
 */
public class MainActivity extends Activity {

    private static final Station[] DEFAULT_STATIONS = {
            new Station("Radio Nova", "https://novazz.ice.infomaniak.ch/novazz-128.mp3", "FR", "french", 128, "", "Eklektiskt"),
            new Station("Nouvo Nova (ny musik)", "https://nova-nouvo.ice.infomaniak.ch/nova-nouvo-128.mp3", "FR", "french", 128, "", "Ny musik"),
            new Station("Mouv'", "https://icecast.radiofrance.fr/mouv-midfi.mp3", "FR", "french", 128, "", "Hiphop"),
            new Station("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3", "FR", "french", 128, "", "Eklektiskt"),
            new Station("France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3", "FR", "french", 128, "", "Nyheter och kultur"),
    };

    // Shortcuts in the search window, picked from the Subtest playlist's styles.
    // "mix:a|b" blends several tags, "land:XX" = most popular in a country, otherwise a tag.
    private static final String[][] GENRES = {
            {"Melodisk techno", "melodic"}, {"Synthpop", "synthpop"}, {"Deep house", "deep house"},
            {"Chill", "chillout"}, {"Dubstep", "dubstep"}, {"Drum & bass", "drum and bass"},
            {"Afrobeats", "afro"}, {"Fransk rap", "mix:rap fr|french rap"}, {"Hiphop", "hip hop"},
            {"Elektroniskt", "electronic"}, {"Sverige", "land:SE"}, {"Frankrike", "land:FR"},
    };
    private static final int[][] CHIP_COLORS = {
            {R.color.sage_bg, R.color.sage_fg}, {R.color.sand_bg, R.color.sand_fg},
            {R.color.sky_bg, R.color.sky_fg}, {R.color.rose_bg, R.color.rose_fg},
            {R.color.lavender_bg, R.color.lavender_fg},
    };

    private final List<CastDevice> devices = new ArrayList<>();
    private final List<Station> stations = new ArrayList<>();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /** Asks speakers what they are playing, a few at a time. */
    private final ExecutorService probes = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler reconnectTimers = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private CastDiscovery discovery;
    private Radio radio;
    private CastController cast;
    private CastController.Listener castListener;

    // Main screen
    private View castChip, chipProgress, npCard, tip, empty;
    private ImageView chipIcon;
    private TextView chipText, orderCountry, orderOwn;
    private RecyclerView stationList;
    private StationListAdapter stationAdapter;
    private TextView npInitials, npName, npStatus, npSong;
    private SongWatcher.Listener songListener;
    private ImageView npLogo;
    private View npLive;
    private ImageButton npButton;
    private ProgressBar npProgress;
    private SeekBar volume;
    private boolean draggingVolume;
    private long lastVolumeSent;
    private View snack;
    private TextView snackText, snackAction;
    private Runnable hideSnack;

    // Search window and cast sheet, while open
    private Dialog searchDialog;
    private TextView miniName, miniStatus;
    private ImageButton miniButton;
    private Dialog castSheet;
    private Runnable castSheetAfterConnect;

    private boolean autoConnectTried;
    /** Reconnecting to the last speaker at start: none, trying, or gave up (tap to retry). */
    private enum Reconnect { NONE, TRYING, FAILED }
    private Reconnect reconnect = Reconnect.NONE;
    /** The speaker the user tried last; shown on the chip when it could not be reached. */
    private CastDevice lastAttempt;
    private CastDevice unreachable;
    /** Why the last start failed, shown in the now-playing card until the next start. */
    private String playError;
    /** The phone's own player has sound (not just buffering). */
    private boolean localPlaying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("radiocast", MODE_PRIVATE);
        radio = Radio.get(this);
        cast = radio.cast;

        castChip = findViewById(R.id.cast_chip);
        chipProgress = findViewById(R.id.chip_progress);
        chipIcon = findViewById(R.id.chip_icon);
        chipText = findViewById(R.id.chip_text);
        orderCountry = findViewById(R.id.order_country);
        orderOwn = findViewById(R.id.order_own);
        stationList = findViewById(R.id.stations);
        tip = findViewById(R.id.tip);
        empty = findViewById(R.id.empty);
        npCard = findViewById(R.id.np_card);
        npInitials = findViewById(R.id.np_initials);
        npLogo = findViewById(R.id.np_logo);
        npName = findViewById(R.id.np_name);
        npStatus = findViewById(R.id.np_status);
        npSong = findViewById(R.id.np_song);
        npSong.setOnClickListener(v -> {
            SongWatcher.Song song = radio.songs.current();
            if (song != null) openInSpotify(song.text);
        });
        npLive = findViewById(R.id.np_live);
        npButton = findViewById(R.id.np_button);
        npProgress = findViewById(R.id.np_progress);
        volume = findViewById(R.id.volume);
        snack = findViewById(R.id.snack);
        snackText = findViewById(R.id.snack_text);
        snackAction = findViewById(R.id.snack_action);

        castListener = new CastController.Listener() {
            @Override public void onStateChanged(CastController.State state, CastDevice device) {
                runOnUiThread(() -> {
                    if (state == CastController.State.CONNECTED && device != null) {
                        reconnect = Reconnect.NONE;
                        unreachable = null;
                        // Remember the speaker by its unique id, so it is found again if its IP address changes.
                        prefs.edit().putString("device", device.key()).putString("device_id", device.id)
                                .putString("device_name", device.name).apply();
                        askForNotifications(); // for the casting notification and lock-screen controls
                    }
                    updateCastChip();
                    updateNowPlaying();
                    showVolume();
                    refreshSheet();
                });
            }
            @Override public void onStatus(String message) { notifyUser(message); }
            @Override public void onSpeakerState(boolean playerRunning, String playerState) {
                runOnUiThread(() -> updateNowPlaying());
            }
            @Override public void onSpeakerMedia(String url, String title) {
                runOnUiThread(() -> { syncNowPlaying(); updateNowPlaying(); });
            }
            @Override public void onSpeakerVolume(double level) {
                runOnUiThread(() -> showVolume());
            }
        };
        radio.addListener(castListener);
        songListener = song -> runOnUiThread(this::updateNowPlaying);
        radio.songs.addListener(songListener);

        // Station list
        stationAdapter = new StationListAdapter(this, stations, new StationListAdapter.Listener() {
            @Override public void onStationTapped(int index) { stationTapped(index); }
            @Override public void onHeaderTapped(String countryCode) { toggleCollapsed(countryCode); }
        });
        stationList.setLayoutManager(new LinearLayoutManager(this));
        stationList.setAdapter(stationAdapter);
        swipeAndDrag().attachToRecyclerView(stationList);
        orderCountry.setOnClickListener(v -> setGrouped(true));
        orderOwn.setOnClickListener(v -> setGrouped(false));
        findViewById(R.id.empty_search).setOnClickListener(v -> showSearch());
        findViewById(R.id.tip_close).setOnClickListener(v -> {
            prefs.edit().putBoolean("tip_seen", true).apply();
            tip.setVisibility(View.GONE);
        });
        loadStations();
        syncNowPlaying();

        // Header and search
        castChip.setOnClickListener(v -> {
            if (reconnect == Reconnect.FAILED) startReconnect();
            else if (unreachable != null) connectTo(unreachable, null);
            else showCastSheet(null);
        });
        findViewById(R.id.menu).setOnClickListener(this::showMenu);
        findViewById(R.id.search_bar).setOnClickListener(v -> showSearch());

        // Now playing
        npButton.setOnClickListener(v -> togglePlay());
        findViewById(R.id.np_text).setOnLongClickListener(v -> { showTiming(); return true; });
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (!fromUser) return;
                // Follow the finger, but do not flood the speaker with messages.
                long now = System.currentTimeMillis();
                if (now - lastVolumeSent > 150) { lastVolumeSent = now; applyVolume(value); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { draggingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                draggingVolume = false;
                applyVolume(s.getProgress());
            }
        });

        discovery = new CastDiscovery(this, new CastDiscovery.Listener() {
            @Override public void onFound(String name, String host, int port, String id, String rs, boolean group) {
                addDevice(name, host, port, id, rs, group);
            }
            @Override public void onError(String message) { notifyUser(message); }
        });

        if (!radio.casting()) startReconnect();
        for (String ip : prefs.getStringSet("ips", new HashSet<>())) {
            addDevice("Högtalare " + ip, ip, 8009, "", "", false);
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        updateCastChip();
        updateNowPlaying();
        showVolume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        discovery.start();
        radio.songs.setForeground(true);
        syncNowPlaying();
        PlaybackService.setListener((playing, title, message) -> runOnUiThread(() -> {
            localPlaying = playing;
            if (message != null && message.startsWith("Strömmen")) playError = message;
            updateNowPlaying();
            if (message != null) notifyUser(message);
        }));
    }

    @Override
    protected void onStop() {
        super.onStop();
        discovery.stop();
        radio.songs.setForeground(false);
        PlaybackService.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The cast connection lives on in Radio, so the notification and lock screen keep working.
        radio.removeListener(castListener);
        radio.songs.removeListener(songListener);
        net.shutdownNow();
        probes.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        reconnectTimers.removeCallbacksAndMessages(null);
    }

    // ================================================================ now playing

    private boolean casting() { return radio.casting(); }

    /** The position in the list of the station with this stream address, or -1. */
    private int indexOfUrl(String url) {
        if (url == null) return -1;
        for (int i = 0; i < stations.size(); i++) if (stations.get(i).url.equals(url)) return i;
        return -1;
    }

    private boolean inList(Station s) { return s != null && indexOfUrl(s.url) >= 0; }

    /**
     * Works out what is playing when the screen opens: what the phone plays, what the speaker
     * reported, or else what played last. A station in the list is matched by its stream address,
     * so its row is marked even though the list was loaded anew.
     */
    private void syncNowPlaying() {
        Station s = radio.nowPlaying;
        String url = PlaybackService.currentStream();
        if (url == null && s != null) url = s.url;
        if (url == null) url = prefs.getString("last_played", null);
        int i = indexOfUrl(url);
        if (i >= 0) radio.nowPlaying = stations.get(i);
        else if (s == null && url != null && url.equals(PlaybackService.currentStream()))
            radio.nowPlaying = new Station("Radio", url, "", "", 0);
    }

    private String targetName() { return radio.targetName(); }

    /** Music is playing or about to (buffering), on the speaker or the phone. */
    private boolean musicActive() {
        return PlaybackService.isActive() || radio.speakerMusicActive();
    }

    /** The now-playing card, the mini bar in the search window, and the playing row in the list. */
    private void updateNowPlaying() {
        Station s = radio.nowPlaying;
        boolean buffering, playing;
        String status;
        if (casting()) {
            String st = radio.speakerPlayerState;
            playing = st.equals("PLAYING");
            buffering = st.equals("BUFFERING");
            if (playing) status = "Spelar på " + targetName();
            else if (buffering) status = "Startar på " + targetName() + "…";
            else if (radio.speakerPlayerRunning) status = "Stoppad · " + targetName() + " är redo";
            else status = "Redo att spela på " + targetName();
        } else if (PlaybackService.isActive()) {
            playing = localPlaying;
            buffering = !localPlaying;
            status = playing ? "Spelar i telefonen" : "Startar i telefonen…";
        } else {
            playing = false;
            buffering = false;
            status = "Stoppad";
        }
        boolean active = playing || buffering;
        boolean error = !active && playError != null;
        if (error) status = playError;

        // Hidden until something has been played.
        npCard.setVisibility(s == null ? View.GONE : View.VISIBLE);
        // The song, when the station tells it: tap to look it up in Spotify.
        SongWatcher.Song song = radio.songs.current();
        boolean showSong = active && s != null && song != null;
        if (s != null) {
            Logos.bind(this, showSong && song.cover != null ? song.cover : s.logo, s.name, npInitials, npLogo, 15);
            npName.setText(StationGroups.shortName(s.name));
        }
        npSong.setVisibility(showSong ? View.VISIBLE : View.GONE);
        if (showSong) {
            npSong.setText("\u266A " + song.text);
            npSong.setContentDescription("Spelas nu: " + song.text + ". Tryck för att söka låten i Spotify.");
        }
        npStatus.setText(status);
        npStatus.setTextColor(getColor(error ? R.color.danger : R.color.muted));
        npStatus.setMaxLines(error ? 2 : 1);
        npLive.setVisibility(playing ? View.VISIBLE : View.GONE);
        npProgress.setVisibility(buffering ? View.VISIBLE : View.GONE);
        npButton.setImageResource(active ? R.drawable.ic_stop : R.drawable.ic_play);
        npButton.setContentDescription(active ? "Stoppa uppspelning" : (error ? "Försök igen" : "Spela"));
        // While buffering, the button sits inside the spinning ring.
        npButton.setScaleX(buffering ? 0.82f : 1f);
        npButton.setScaleY(buffering ? 0.82f : 1f);

        if (miniName != null) {
            View bar = searchDialog.findViewById(R.id.mini_bar);
            bar.setVisibility(s == null ? View.GONE : View.VISIBLE);
            miniName.setText(s != null ? StationGroups.shortName(s.name) : "");
            miniStatus.setText(showSong ? song.text : status);
            miniButton.setImageResource(active ? R.drawable.ic_stop : R.drawable.ic_play);
            miniButton.setContentDescription(active ? "Stoppa uppspelning" : "Spela");
        }

        stationAdapter.setPlaying(active && s != null ? indexOfUrl(s.url) : -1, status);
    }

    /** Tapping a channel plays it; tapping the one already playing leaves it playing. */
    private void stationTapped(int index) {
        Station s = stations.get(index);
        Station np = radio.nowPlaying;
        if (np != null && np.url.equals(s.url) && musicActive()) {
            showSnack(StationGroups.shortName(s.name) + " spelar redan.", null, null);
            return;
        }
        playStation(s, true);
    }

    private void togglePlay() {
        if (musicActive()) {
            stopMusic();
            return;
        }
        Station s = radio.nowPlaying;
        if (s == null) { showSnack("Tryck på en kanal i listan för att spela den.", null, null); return; }
        playStation(s, inList(s));
    }

    /** Stops the music. On a speaker its player stays ready, so the next start is quick. */
    private void stopMusic() {
        PlaybackService.stop(this);
        if (casting()) radio.stopOnSpeaker();
        updateNowPlaying();
    }

    /** Plays any station: from the list, or one being tried out from the search (saved = false). */
    private void playStation(Station s, boolean saved) {
        CastController.State state = cast.getState();
        if (state == CastController.State.CONNECTING) {
            showSnack("Vänta, ansluter till " + targetName() + "…", null, null);
            return;
        }
        playError = null;
        radio.nowPlaying = s;
        if (saved) prefs.edit().putString("last_played", s.url).apply();
        String name = StationGroups.shortName(s.name);
        if (state == CastController.State.CONNECTED) {
            PlaybackService.stop(this);
            radio.playOnSpeaker(s);
        } else {
            Timing.current.start(name + " i telefonen");
            askForNotifications();
            localPlaying = false;
            PlaybackService.play(this, s.url, name, prefs.getInt("volume", 100) / 100f);
        }
        if (!saved) showSnack("Provlyssnar " + name + ". Den är inte sparad.", "Lägg till", () -> addStation(s, false));
        expandGroupOf(s);
        updateNowPlaying();
    }

    /** When the phone is playing and a speaker is picked, what is playing moves to the speaker. */
    private Runnable handOver(Runnable afterConnect) {
        if (afterConnect != null) return afterConnect;
        if (!PlaybackService.isActive()) return null;
        Station s = radio.nowPlaying;
        return () -> { if (s != null) playStation(s, inList(s)); };
    }

    /** The volume slider: the speaker's volume while casting, the phone player's otherwise. */
    private void applyVolume(int value) {
        if (casting()) {
            cast.setVolume(value / 100.0);
        } else {
            prefs.edit().putInt("volume", value).apply();
            PlaybackService.setVolume(this, value / 100f);
        }
    }

    /** Shows the speaker's real volume (also when changed elsewhere), or the phone player's. */
    private void showVolume() {
        if (draggingVolume) return;
        double level = radio.speakerVolume;
        volume.setProgress(casting() && level >= 0 ? (int) Math.round(level * 100) : prefs.getInt("volume", 100));
        volume.setContentDescription(casting() ? "Volym på " + targetName() : "Volym i telefonen");
    }

    /** The playback notification needs this on Android 13+. Playback works without it. */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Tid till ljud för senaste start");
        menu.getMenu().add(0, 2, 1, "Visa tipsen igen");
        menu.getMenu().add(0, 4, 2, "Visa låt på låsskärmen (mer data)")
                .setCheckable(true).setChecked(radio.songs.lockScreen());
        menu.getMenu().add(0, 3, 3, "Om RadioCast");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showTiming(); return true;
                case 2:
                    prefs.edit().putBoolean("tip_seen", false).apply();
                    refreshStations();
                    return true;
                case 3: showAbout(); return true;
                case 4:
                    boolean on = !radio.songs.lockScreen();
                    radio.songs.setLockScreen(on);
                    showSnack(on ? "Låten visas på låsskärmen även när appen är stängd. Det drar ungefär 3 MB i timmen."
                            : "Låten hämtas bara när appen är öppen, om kanalen inte har en egen låtlista.", null, null);
                    return true;
                default: return false;
            }
        });
        menu.show();
    }

    /** Searches the song in the Spotify app, or on Spotify's website if the app is missing. */
    private void openInSpotify(String song) {
        String q = Uri.encode(song.replace(" \u2013 ", " "));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + q)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + q)));
            } catch (ActivityNotFoundException e2) {
                showSnack("Hittade varken Spotify eller en webbläsare.", null, null);
            }
        }
    }

    /** How long each step of the last start took. */
    private void showTiming() {
        TextView t = new TextView(this);
        t.setText(Timing.current.summary());
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.fg));
        t.setTextIsSelectable(true);
        new AlertDialog.Builder(this).setTitle("Tid till ljud").setView(padded(t)).setPositiveButton("OK", null).show();
    }

    private void showAbout() {
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        new AlertDialog.Builder(this)
                .setTitle("RadioCast " + version)
                .setMessage("Webbradio i telefonen och på Chromecast.\n\nKanaler och logotyper från radio-browser.info.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ================================================================ messages

    /**
     * Messages from playback and casting. What the screen already shows ("Startar…", "Spelar…")
     * is not repeated; errors that last are kept on the chip or the now-playing card as well.
     */
    private void notifyUser(String message) {
        if (message == null || message.isEmpty()) return;
        String[] shownElsewhere = {"Ansluter", "Ansluten", "Startar", "Buffrar", "Spelar", "Redan ansluten", "Stoppad.", "Volym"};
        for (String prefix : shownElsewhere) if (message.startsWith(prefix)) return;
        runOnUiThread(() -> {
            if (message.startsWith("Når inte") || message.startsWith("Tappade") || message.startsWith("Anslutningen till högtalaren bröts")) {
                unreachable = lastAttempt;
                updateCastChip();
            } else if (message.contains("kunde inte spela") || message.startsWith("Strömmen")) {
                playError = message;
                updateNowPlaying();
            }
            showSnack(message, null, null);
        });
    }

    /** A short message at the bottom, in the search window when it is open. */
    private void showSnack(String text, String actionLabel, Runnable action) {
        View bar = snack;
        TextView label = snackText, button = snackAction;
        if (searchDialog != null) {
            bar = searchDialog.findViewById(R.id.search_snack);
            label = searchDialog.findViewById(R.id.search_snack_text);
            button = searchDialog.findViewById(R.id.search_snack_action);
        }
        if (hideSnack != null) ui.removeCallbacks(hideSnack);
        View shown = bar;
        hideSnack = () -> shown.setVisibility(View.GONE);
        label.setText(text);
        if (actionLabel != null) {
            button.setText(actionLabel);
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(v -> {
                shown.setVisibility(View.GONE);
                action.run();
            });
        } else {
            button.setVisibility(View.GONE);
        }
        bar.setVisibility(View.VISIBLE);
        bar.announceForAccessibility(text);
        ui.postDelayed(hideSnack, actionLabel != null ? 6000 : 4500);
    }

    // ================================================================ cast chip and sheet

    /** The chip always says where the sound goes: the phone, or a speaker. */
    private void updateCastChip() {
        CastController.State state = cast.getState();
        String last = prefs.getString("device_name", "högtalaren");
        int bg = R.drawable.bg_cast_idle, fg = R.color.accent_text, icon = R.drawable.ic_phone;
        boolean spinner = false;
        String text, description;
        CastDevice dev = cast.getDevice();
        if (state == CastController.State.CONNECTED) {
            bg = R.drawable.bg_cast_connected;
            icon = dev != null && dev.group ? R.drawable.ic_group : R.drawable.ic_speaker;
            text = targetName();
            description = "Ljudet går till " + text + ". Tryck för att byta.";
        } else if (state == CastController.State.CONNECTING) {
            spinner = true;
            text = dev != null ? dev.name : "Ansluter";
            description = "Ansluter till " + text;
        } else if (reconnect == Reconnect.TRYING) {
            spinner = true;
            text = last;
            description = "Återansluter till " + last;
        } else if (reconnect == Reconnect.FAILED || unreachable != null) {
            String name = unreachable != null ? unreachable.name : last;
            bg = R.drawable.bg_cast_warn; fg = R.color.live_fg; icon = R.drawable.ic_retry;
            text = "Når inte " + name;
            description = "Når inte " + name + ". Tryck för att försöka igen.";
        } else {
            text = "Telefonen";
            description = "Ljudet går till telefonen. Tryck för att välja en högtalare.";
        }
        castChip.setBackgroundResource(bg);
        chipText.setTextColor(getColor(fg));
        chipText.setText(text);
        chipIcon.setImageResource(icon);
        chipIcon.setImageTintList(ColorStateList.valueOf(getColor(fg)));
        chipIcon.setVisibility(spinner ? View.GONE : View.VISIBLE);
        chipProgress.setVisibility(spinner ? View.VISIBLE : View.GONE);
        castChip.setContentDescription(description);
    }

    private void connectTo(CastDevice dev, Runnable after) {
        reconnect = Reconnect.NONE;
        unreachable = null;
        lastAttempt = dev;
        cast.connect(dev, after == null ? null : () -> runOnUiThread(after));
        updateCastChip();
    }

    /** The cast sheet: every speaker with what it is doing, this phone, and the two ways to let go. */
    private void showCastSheet(Runnable afterConnect) {
        castSheetAfterConnect = afterConnect;
        discovery.start();
        probeSpeakers();
        Dialog d = new Dialog(this, R.style.Sheet);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.sheet_cast);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.findViewById(R.id.enter_ip).setOnClickListener(v -> { d.dismiss(); askForIp(afterConnect); });
        d.findViewById(R.id.disconnect).setOnClickListener(v -> { d.dismiss(); cast.disconnect(false); });
        d.findViewById(R.id.stop_device).setOnClickListener(v -> { d.dismiss(); cast.disconnect(true); });
        d.setOnDismissListener(x -> { castSheet = null; castSheetAfterConnect = null; });
        castSheet = d;
        refreshSheet();
        d.show();
    }

    /** Rebuilds the speaker rows in the cast sheet, if it is open. */
    private void refreshSheet() {
        Dialog d = castSheet;
        if (d == null) return;
        LinearLayout list = d.findViewById(R.id.devices);
        list.removeAllViews();
        boolean connected = casting();
        CastDevice current = cast.getDevice();
        LayoutInflater inf = LayoutInflater.from(this);

        // Groups first, under their own heading, so they stand out from single speakers.
        List<CastDevice> groups = new ArrayList<>(), speakers = new ArrayList<>();
        for (CastDevice dev : devices) (dev.group ? groups : speakers).add(dev);
        List<CastDevice> ordered = new ArrayList<>(groups);
        ordered.addAll(speakers);
        for (CastDevice dev : ordered) {
            if (!groups.isEmpty() && (dev == ordered.get(0) || (!dev.group && dev == speakers.get(0)))) {
                list.addView(sheetHeading(dev.group ? "GRUPPER · SYNKAT LJUD I FLERA RUM" : "HÖGTALARE"));
            }
            boolean isCurrent = connected && current != null && current.key().equals(dev.key());
            View row = inf.inflate(R.layout.item_device, list, false);
            ((TextView) row.findViewById(R.id.name)).setText(dev.name);
            row.findViewById(R.id.badge).setVisibility(dev.group ? View.VISIBLE : View.GONE);
            TextView status = row.findViewById(R.id.status);
            Station np = radio.nowPlaying;
            String st = isCurrent
                    ? "Ansluten" + (np != null && musicActive() ? " · spelar " + StationGroups.shortName(np.name)
                        : (dev.status.startsWith("spelar ") ? " · " + dev.status : ""))
                    : capitalize(dev.status);
            status.setText(st.isEmpty() ? (dev.group ? "Spelar i alla högtalare i gruppen" : "Chromecast") : st);
            status.setTextColor(getColor(isCurrent ? R.color.accent_text : R.color.muted));
            ImageView icon = row.findViewById(R.id.icon);
            icon.setImageResource(dev.group ? R.drawable.ic_group : R.drawable.ic_speaker);
            icon.setBackgroundResource(isCurrent ? R.drawable.bg_icon_circle_accent : R.drawable.bg_icon_circle);
            icon.setImageTintList(ColorStateList.valueOf(getColor(isCurrent ? R.color.accent_text : R.color.fg)));
            row.findViewById(R.id.check).setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            if (isCurrent) row.setBackgroundResource(R.drawable.bg_device_selected);
            row.setContentDescription((dev.group ? "Grupp " : "") + dev.name + ", " + status.getText());
            row.setOnClickListener(v -> {
                d.dismiss();
                if (isCurrent) return;
                connectTo(dev, handOver(castSheetAfterConnect));
            });
            list.addView(row);
        }

        // This phone: listen here instead; what plays on the speaker moves along.
        if (!groups.isEmpty()) list.addView(sheetHeading("DEN HÄR TELEFONEN"));
        View phone = inf.inflate(R.layout.item_device, list, false);
        ((TextView) phone.findViewById(R.id.name)).setText("Den här telefonen");
        boolean onPhone = !connected;
        ((TextView) phone.findViewById(R.id.status)).setText(onPhone
                ? (PlaybackService.isActive() ? "Spelar här" : "Ljudet går hit") : "Spela i högtalaren eller hörlurarna");
        ((ImageView) phone.findViewById(R.id.icon)).setImageResource(R.drawable.ic_phone);
        phone.findViewById(R.id.check).setVisibility(onPhone ? View.VISIBLE : View.GONE);
        phone.setOnClickListener(v -> {
            d.dismiss();
            if (!connected) return;
            Station s = radio.nowPlaying;
            boolean wasPlaying = musicActive();
            cast.disconnect(true, () -> runOnUiThread(() -> { if (wasPlaying && s != null) playStation(s, inList(s)); }));
        });
        list.addView(phone);

        ((TextView) d.findViewById(R.id.title)).setText(connected ? "Castar till " + targetName() : "Välj var ljudet ska spelas");
        ((TextView) d.findViewById(R.id.subtitle)).setText(devices.isEmpty() ? "Söker efter enheter…" : "Söker fler enheter…");
        d.findViewById(R.id.connected_actions).setVisibility(connected ? View.VISIBLE : View.GONE);
        ((TextView) d.findViewById(R.id.actions_caption)).setText("Koppla från: radion spelar vidare på " + targetName()
                + ". Stoppa Chromecast-enhet: spelaren på " + targetName() + " stängs och enheten blir ledig.");
    }

    private TextView sheetHeading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setLetterSpacing(0.1f);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setTextColor(getColor(R.color.muted));
        t.setPadding(dp(12), dp(12), dp(12), dp(6));
        return t;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /** Asks every known speaker what it is doing; the sheet updates as answers come in. */
    private void probeSpeakers() {
        for (CastDevice d : new ArrayList<>(devices)) {
            String host = d.host;
            int port = d.port;
            probes.execute(() -> {
                CastClient.SpeakerStatus st = CastClient.probe(host, port);
                runOnUiThread(() -> {
                    if (st == null) d.status = "svarar inte";
                    else if (st.idle) d.status = "ledig";
                    else if (st.ours) d.status = "RadioCast: " + st.text;
                    else d.status = "spelar " + st.text;
                    refreshSheet();
                });
            });
        }
    }

    private void addDevice(String name, String host, int port, String id, String rs, boolean group) {
        String key = host + ":" + port;
        CastDevice found = null;
        // Same speaker: same unique id, or (for speakers added by IP address) same address.
        for (CastDevice d : devices) {
            if (!id.isEmpty() && id.equals(d.id)) { found = d; break; }
        }
        if (found == null) {
            for (CastDevice d : devices) {
                if (d.key().equals(key) && (d.id.isEmpty() || id.isEmpty() || d.id.equals(id))) { found = d; break; }
            }
        }
        if (found == null) {
            found = new CastDevice(name, host, port);
            devices.add(found);
        } else {
            if (!name.startsWith("Högtalare ")) found.name = name;
            found.host = host;
            found.port = port;
        }
        if (!id.isEmpty()) found.id = id;
        if (group) found.group = true;
        if (!rs.isEmpty()) found.status = describeAnnounced(rs);
        refreshSheet();

        // Reconnect to the speaker used last time, once, without asking.
        String savedId = prefs.getString("device_id", "");
        boolean isLast = (!savedId.isEmpty() && savedId.equals(found.id))
                || found.key().equals(prefs.getString("device", null));
        if (!autoConnectTried && isLast && cast.getState() == CastController.State.DISCONNECTED) {
            autoConnectTried = true;
            lastAttempt = found;
            cast.connect(found, null);
        }
    }

    /** What a speaker announces it is playing ("Spotify", "Now Casting: Radio Nova") as list text. */
    private static String describeAnnounced(String rs) {
        String t = CastClient.cleanStatus(rs, "");
        if (t.isEmpty()) return rs.equalsIgnoreCase("Ready To Cast") ? "ledig" : "";
        return "spelar " + t;
    }

    /** Looks for the last used speaker at start, retrying for a while, then offers a retry button. */
    private void startReconnect() {
        String name = prefs.getString("device_name", "");
        boolean saved = !name.isEmpty() || prefs.getString("device", null) != null;
        if (!saved || cast.getState() != CastController.State.DISCONNECTED) return;
        if (name.isEmpty()) prefs.edit().putString("device_name", "senaste högtalaren").apply();
        reconnect = Reconnect.TRYING;
        unreachable = null;
        autoConnectTried = false;
        updateCastChip();
        reconnectTimers.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.start();
        // Speakers sometimes answer the search slowly; ask again a couple of times.
        Runnable again = () -> {
            if (reconnect != Reconnect.TRYING || cast.getState() != CastController.State.DISCONNECTED) return;
            autoConnectTried = false;
            if (discovery != null) discovery.start();
            String k = prefs.getString("device", null);
            for (CastDevice d : devices) {
                boolean last = d.key().equals(k) || (!d.id.isEmpty() && d.id.equals(prefs.getString("device_id", "")));
                if (last) { autoConnectTried = true; lastAttempt = d; cast.connect(d, null); break; }
            }
        };
        reconnectTimers.postDelayed(again, 4000);
        reconnectTimers.postDelayed(again, 8000);
        reconnectTimers.postDelayed(() -> {
            if (reconnect == Reconnect.TRYING && cast.getState() == CastController.State.DISCONNECTED) {
                reconnect = Reconnect.FAILED;
                updateCastChip();
                showSnack("Hittade inte " + prefs.getString("device_name", "högtalaren")
                        + ". Är den på och på samma wifi? Tryck på knappen uppe till höger för att försöka igen.", null, null);
            }
        }, 12000);
        // A speaker added by IP address can be tried right away.
        String key = prefs.getString("device", null);
        for (CastDevice d : devices) {
            if (d.key().equals(key)) { autoConnectTried = true; lastAttempt = d; cast.connect(d, null); break; }
        }
    }

    private void askForIp(Runnable afterConnect) {
        EditText input = new EditText(this);
        input.setHint("t.ex. 192.168.1.50");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Högtalarens IP-adress")
                .setMessage("Finns i Google Home: tryck på högtalaren, kugghjulet, Enhetsinformation.")
                .setView(padded(input))
                .setPositiveButton("Anslut", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.matches("[0-9a-fA-F.:]+")) { showSnack("Det ser inte ut som en IP-adress.", null, null); return; }
                    Set<String> ips = new HashSet<>(prefs.getStringSet("ips", new HashSet<>()));
                    ips.add(ip);
                    prefs.edit().putStringSet("ips", ips).apply();
                    autoConnectTried = true;
                    addDevice("Högtalare " + ip, ip, 8009, "", "", false);
                    for (CastDevice dev : devices) {
                        if (dev.key().equals(ip + ":8009")) connectTo(dev, handOver(afterConnect));
                    }
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    // ================================================================ station list

    private void loadStations() {
        stations.clear();
        boolean migrated = false;
        String json = prefs.getString("stations", null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String name = o.getString("name"), url = o.getString("url");
                    if (o.has("cc")) {
                        stations.add(new Station(name, url, o.optString("cc"), o.optString("lang"), o.optInt("br"),
                                o.optString("logo"), o.optString("genre")));
                    } else {
                        // Saved by an older version, without a country: guess it from the address.
                        stations.add(Station.withGuessedCountry(name, url));
                        migrated = true;
                    }
                }
            } catch (JSONException ignored) {
                stations.clear();
            }
        }
        if (json == null) Collections.addAll(stations, DEFAULT_STATIONS);
        // Give the built-in channels their genre if they were saved before genres existed.
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            if (!s.genre.isEmpty()) continue;
            for (Station d : DEFAULT_STATIONS) {
                if (d.url.equals(s.url)) {
                    stations.set(i, new Station(s.name, s.url, s.countryCode, s.language, s.bitrate, s.logo, d.genre));
                    migrated = true;
                }
            }
        }
        if (migrated) saveStations();
        refreshStations();
    }

    private void saveStations() {
        JSONArray arr = new JSONArray();
        try {
            for (Station s : stations) {
                arr.put(new JSONObject().put("name", s.name).put("url", s.url).put("cc", s.countryCode)
                        .put("lang", s.language).put("br", s.bitrate).put("logo", s.logo).put("genre", s.genre));
            }
        } catch (JSONException ignored) { }
        prefs.edit().putString("stations", arr.toString()).apply();
    }

    private boolean grouped() { return prefs.getBoolean("group_by_country", true); }

    private void setGrouped(boolean byCountry) {
        prefs.edit().putBoolean("group_by_country", byCountry).apply();
        refreshStations();
    }

    /** Redraws the list in the chosen order, the order pills, the tip and the empty state. */
    private void refreshStations() {
        boolean grouped = grouped();
        Set<String> collapsed = prefs.getStringSet("collapsed", new HashSet<>());
        stationAdapter.update(StationGroups.rows(stations, grouped, collapsed), -1);
        styleOrderPill(orderCountry, grouped);
        styleOrderPill(orderOwn, !grouped);

        boolean none = stations.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        stationList.setVisibility(none ? View.GONE : View.VISIBLE);
        boolean showTip = !none && !prefs.getBoolean("tip_seen", false);
        tip.setVisibility(showTip ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.tip_text)).setText(grouped
                ? "Tryck på en kanal för att spela. Svep åt vänster för att ta bort."
                : "Tryck på en kanal för att spela. Svep åt vänster för att ta bort, håll och dra för att flytta.");
        if (radio != null) updateNowPlaying();
    }

    private void styleOrderPill(TextView pill, boolean on) {
        pill.setBackgroundResource(on ? R.drawable.bg_pill_dark : R.drawable.bg_pill_outline);
        pill.setTextColor(getColor(on ? R.color.pill_dark_fg : R.color.soft_fg));
        pill.setSelected(on);
        pill.setContentDescription(pill.getText() + (on ? ", vald" : ""));
    }

    private void toggleCollapsed(String country) {
        Set<String> collapsed = new HashSet<>(prefs.getStringSet("collapsed", new HashSet<>()));
        if (!collapsed.remove(country)) collapsed.add(country);
        prefs.edit().putStringSet("collapsed", collapsed).apply();
        refreshStations();
    }

    /** Unfolds the country of a station, so the playing one is never hidden. */
    private void expandGroupOf(Station s) {
        Set<String> collapsed = new HashSet<>(prefs.getStringSet("collapsed", new HashSet<>()));
        if (collapsed.remove(s.countryCode)) {
            prefs.edit().putStringSet("collapsed", collapsed).apply();
            refreshStations();
        }
    }

    private void addStation(Station s, boolean playNow) {
        int index = -1;
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).url.equals(s.url)) { index = i; break; }
        }
        String name = StationGroups.shortName(s.name);
        if (index < 0) {
            stations.add(s);
            saveStations();
            index = stations.size() - 1;
            Station np = radio.nowPlaying;
            if (np != null && np.url.equals(s.url)) {
                radio.nowPlaying = s;
                prefs.edit().putString("last_played", s.url).apply();
            }
            showSnack(name + " är tillagd i din lista.", null, null);
        } else {
            showSnack(name + " finns redan i din lista.", null, null);
        }
        expandGroupOf(stations.get(index));
        refreshStations();
        int pos = stationAdapter.positionOf(index);
        if (pos >= 0) stationList.smoothScrollToPosition(pos);
        if (playNow) playStation(stations.get(index), true);
    }

    /** Swipe a channel left to remove it (with Ångra); in own order, hold and drag to move it. */
    private ItemTouchHelper swipeAndDrag() {
        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        text.setFakeBoldText(true);
        Drawable trash = getDrawable(R.drawable.ic_trash);

        return new ItemTouchHelper(new ItemTouchHelper.Callback() {
            private boolean moved;

            @Override
            public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
                int p = vh.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION || stationAdapter.rowAt(p).isHeader()) return 0;
                int drag = grouped() ? 0 : ItemTouchHelper.UP | ItemTouchHelper.DOWN;
                return makeMovementFlags(drag, ItemTouchHelper.LEFT);
            }

            @Override public boolean isLongPressDragEnabled() { return !grouped(); }

            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
                if (a < 0 || b < 0) return false;
                Collections.swap(stations, a, b); // own order: rows map 1:1 to stations
                stationAdapter.update(StationGroups.rows(stations, false, new HashSet<>()), -1);
                moved = true;
                return true;
            }

            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                if (moved) {
                    moved = false;
                    saveStations();
                    refreshStations();
                }
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
                int p = vh.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                int index = stationAdapter.rowAt(p).stationIndex;
                Station removed = stations.remove(index);
                saveStations();
                refreshStations();
                showSnack(StationGroups.shortName(removed.name) + " är borttagen", "Ångra", () -> {
                    stations.add(Math.min(index, stations.size()), removed);
                    saveStations();
                    refreshStations();
                });
            }

            @Override
            public void onChildDraw(Canvas c, RecyclerView rv, RecyclerView.ViewHolder vh, float dX, float dY,
                                    int actionState, boolean active) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    View v = vh.itemView;
                    float r = dp(18);
                    red.setColor(getColor(R.color.danger));
                    c.drawRoundRect(new RectF(v.getRight() + dX, v.getTop(), v.getRight(), v.getBottom()), r, r, red);
                    int size = dp(20);
                    int cy = (v.getTop() + v.getBottom()) / 2;
                    String label = "Ta bort";
                    float textW = text.measureText(label);
                    int iconLeft = (int) (v.getRight() - dp(20) - textW - dp(6) - size);
                    if (trash != null) {
                        trash.setTint(0xFFFFFFFF);
                        trash.setBounds(iconLeft, cy - size / 2, iconLeft + size, cy + size / 2);
                        trash.draw(c);
                    }
                    text.setColor(0xFFFFFFFF);
                    c.drawText(label, iconLeft + size + dp(6), cy + text.getTextSize() / 3, text);
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, active);
            }
        });
    }

    // ================================================================ search window

    private interface Lookup { RadioSearch.Outcome run() throws IOException; }

    /** Each new search gets a number, so a slow old answer never replaces a newer one. */
    private final AtomicInteger searchSeq = new AtomicInteger();

    private void showSearch() {
        Dialog d = new Dialog(this, R.style.FullScreen);
        d.setContentView(R.layout.screen_search);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        EditText query = d.findViewById(R.id.query);
        TextView info = d.findViewById(R.id.info);
        ListView list = d.findViewById(R.id.results);
        LinearLayout chips = d.findViewById(R.id.chips);
        miniName = d.findViewById(R.id.mini_name);
        miniStatus = d.findViewById(R.id.mini_status);
        miniButton = d.findViewById(R.id.mini_button);
        miniButton.setOnClickListener(v -> togglePlay());

        List<RadioSearch.Result> results = new ArrayList<>();
        ResultAdapter adapter = new ResultAdapter(this, results, new ResultAdapter.Listener() {
            @Override public void onTry(RadioSearch.Result r) { playStation(r.toStation(), false); }
            @Override public void onAdd(RadioSearch.Result r) { addStation(r.toStation(), false); }
        });
        list.setAdapter(adapter);

        List<TextView> chipViews = new ArrayList<>();
        for (int i = 0; i < GENRES.length; i++) {
            String[] g = GENRES[i];
            TextView chip = pill(g[0]);
            chips.addView(chip, pillParams());
            chipViews.add(chip);
            String tag = g[1];
            chip.setOnClickListener(v -> {
                query.setText("");
                for (int k = 0; k < chipViews.size(); k++) styleChip(chipViews.get(k), k % CHIP_COLORS.length, chipViews.get(k) == chip);
                if (tag.startsWith("land:")) {
                    String cc = tag.substring(5);
                    lookup(() -> RadioSearch.byCountry(cc), "Populäraste i " + g[0], info, adapter, results, null);
                } else if (tag.startsWith("mix:")) {
                    String[] tags = tag.substring(4).split("\\|");
                    lookup(() -> RadioSearch.mix(tags), g[0], info, adapter, results, null);
                } else {
                    lookup(() -> RadioSearch.byTag(tag), g[0], info, adapter, results, null);
                }
            });
            styleChip(chip, i % CHIP_COLORS.length, false);
        }

        // Search while typing, a moment after the last keystroke.
        Runnable[] pending = new Runnable[1];
        Runnable runQuery = () -> {
            String q = query.getText().toString().trim();
            if (q.length() < 2) return;
            for (int k = 0; k < chipViews.size(); k++) styleChip(chipViews.get(k), k % CHIP_COLORS.length, false);
            lookup(() -> RadioSearch.search(q), "”" + q + "”", info, adapter, results, q);
        };
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (pending[0] != null) ui.removeCallbacks(pending[0]);
                pending[0] = runQuery;
                if (s.toString().trim().length() >= 2) ui.postDelayed(runQuery, 450);
                showRecent(d, query, s.toString().trim().isEmpty());
            }
        });
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (pending[0] != null) ui.removeCallbacks(pending[0]);
            runQuery.run();
            return true;
        });
        d.findViewById(R.id.back).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.add_link).setOnClickListener(v -> askForStation());
        d.setOnDismissListener(x -> {
            if (pending[0] != null) ui.removeCallbacks(pending[0]);
            searchDialog = null; miniName = null; miniStatus = null; miniButton = null;
        });
        searchDialog = d;
        info.setText("Välj en genre eller skriv ett namn.");
        showRecent(d, query, true);
        updateNowPlaying();
        d.show();
    }

    /** The last searches as tappable pills, while the search box is empty. */
    private void showRecent(Dialog d, EditText query, boolean show) {
        LinearLayout box = d.findViewById(R.id.recent);
        View scroll = d.findViewById(R.id.recent_scroll);
        box.removeAllViews();
        List<String> recent = recentSearches();
        if (!show || recent.isEmpty()) { scroll.setVisibility(View.GONE); return; }
        TextView label = new TextView(this);
        label.setText("Senaste:");
        label.setTextColor(getColor(R.color.muted));
        label.setTextSize(13);
        label.setPadding(0, 0, dp(8), 0);
        box.addView(label);
        for (String q : recent) {
            TextView p = pill(q);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(getColor(R.color.card));
            bg.setStroke(dp(1), getColor(R.color.line));
            p.setBackground(bg);
            p.setTextColor(getColor(R.color.fg));
            p.setOnClickListener(v -> { query.setText(q); query.setSelection(q.length()); });
            box.addView(p, pillParams());
        }
        scroll.setVisibility(View.VISIBLE);
    }

    private List<String> recentSearches() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("recent_searches", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (JSONException ignored) { }
        return out;
    }

    private void rememberSearch(String q) {
        List<String> recent = recentSearches();
        recent.removeIf(x -> x.equalsIgnoreCase(q));
        recent.add(0, q);
        while (recent.size() > 6) recent.remove(recent.size() - 1);
        prefs.edit().putString("recent_searches", new JSONArray(recent).toString()).apply();
    }

    private TextView pill(String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(18), 0, dp(18), 0);
        chip.setMinWidth(dp(44));
        return chip;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMarginEnd(dp(8));
        return lp;
    }

    /** Genre chip: pastel, or dark when it is the chosen one. */
    private void styleChip(TextView chip, int colorIndex, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        if (selected) {
            bg.setColor(getColor(R.color.pill_dark_bg));
            chip.setTextColor(getColor(R.color.pill_dark_fg));
        } else {
            bg.setColor(getColor(CHIP_COLORS[colorIndex][0]));
            chip.setTextColor(getColor(CHIP_COLORS[colorIndex][1]));
        }
        chip.setBackground(bg);
        chip.setSelected(selected);
    }

    private void lookup(Lookup what, String label, TextView info, ResultAdapter adapter,
                        List<RadioSearch.Result> results, String remember) {
        int seq = searchSeq.incrementAndGet();
        info.setText("Söker " + label + "…");
        net.execute(() -> {
            try {
                RadioSearch.Outcome out = what.run();
                runOnUiThread(() -> {
                    if (seq != searchSeq.get()) return; // a newer search has started
                    results.clear();
                    results.addAll(out.results);
                    adapter.reset();
                    if (remember != null && !out.results.isEmpty()) rememberSearch(remember);
                    String head = out.note != null ? out.note + " · " : "";
                    info.setText(out.results.isEmpty()
                            ? head + "Inga kanaler hittades för " + label + ". Prova ett annat ord eller en genre."
                            : head + out.results.size() + " kanaler");
                });
            } catch (IOException e) {
                runOnUiThread(() -> { if (seq == searchSeq.get()) info.setText(e.getMessage()); });
            }
        });
    }

    /** Add a channel by pasting its stream address. */
    private void askForStation() {
        EditText name = new EditText(this);
        name.setHint("Namn, t.ex. Radio Nova");
        EditText url = new EditText(this);
        url.setHint("Strömlänk, https://…mp3");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(name);
        box.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("Lägg till med egen länk")
                .setMessage("Klistra in en direktlänk till strömmen. Den slutar ofta på .mp3, .aac eller .m3u8.")
                .setView(padded(box))
                .setPositiveButton("Lägg till", (dlg, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (n.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
                        showSnack("Fyll i ett namn och en länk som börjar med http.", null, null);
                        return;
                    }
                    addStation(Station.withGuessedCountry(n, u), false);
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    // ================================================================ utils

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View padded(View v) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(10), dp(20), 0);
        wrap.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }
}
