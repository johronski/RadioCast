package se.radiocast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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

/**
 * Design B: header with cast chip, search, order pills, the station list and a now-playing card.
 * Search is a full-screen window; the cast picker is a sheet from the bottom.
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
            {"För dig", "mix:melodic|synthpop|deep house|chillout|dubstep|afro|rap fr"},
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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /** Asks speakers what they are playing, a few at a time. */
    private final ExecutorService probes = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler reconnectTimers = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private CastDiscovery discovery;
    private CastController cast;

    // Main screen
    private TextView castChip, orderCountry, orderOwn;
    private RecyclerView stationList;
    private StationListAdapter stationAdapter;
    private TextView npInitials, npName, npStatus;
    private ImageView npLogo;
    private View npLive;
    private ImageButton npButton;
    private ProgressBar npProgress;
    private View snack;
    private TextView snackText, snackAction;
    private final Runnable hideSnack = () -> snack.setVisibility(View.GONE);

    // Search window and cast sheet, while open
    private Dialog searchDialog;
    private TextView miniName, miniStatus;
    private ImageButton miniButton;
    private Dialog castSheet;
    private Runnable castSheetAfterConnect;

    /** Index of the chosen station in the list. */
    private int selectedIndex;
    /** What is playing or was last started, including a station only being tried out. */
    private Station nowPlaying;
    private boolean autoConnectTried;
    /** Reconnecting to the last speaker at start: none, trying, or gave up (tap to retry). */
    private enum Reconnect { NONE, TRYING, FAILED }
    private Reconnect reconnect = Reconnect.NONE;
    /** Latest news from the connected speaker: is our player running there, and what is it doing. */
    private boolean speakerPlayerRunning;
    private String speakerPlayerState = "IDLE";
    /** The phone's own player has sound (not just buffering). */
    private boolean localPlaying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("radiocast", MODE_PRIVATE);

        castChip = findViewById(R.id.cast_chip);
        orderCountry = findViewById(R.id.order_country);
        orderOwn = findViewById(R.id.order_own);
        stationList = findViewById(R.id.stations);
        npInitials = findViewById(R.id.np_initials);
        npLogo = findViewById(R.id.np_logo);
        npName = findViewById(R.id.np_name);
        npStatus = findViewById(R.id.np_status);
        npLive = findViewById(R.id.np_live);
        npButton = findViewById(R.id.np_button);
        npProgress = findViewById(R.id.np_progress);
        snack = findViewById(R.id.snack);
        snackText = findViewById(R.id.snack_text);
        snackAction = findViewById(R.id.snack_action);

        cast = new CastController(worker, new CastController.Listener() {
            @Override public void onStateChanged(CastController.State state, CastDevice device) {
                runOnUiThread(() -> {
                    if (state == CastController.State.CONNECTED && device != null) {
                        reconnect = Reconnect.NONE;
                        // Remember the speaker by its unique id, so it is found again if its IP address changes.
                        prefs.edit().putString("device", device.key()).putString("device_id", device.id)
                                .putString("device_name", device.name).apply();
                    }
                    updateCastChip();
                    updateNowPlaying();
                    refreshSheet();
                });
            }
            @Override public void onStatus(String message) { notifyUser(message); }
            @Override public void onSpeakerState(boolean playerRunning, String playerState) {
                runOnUiThread(() -> {
                    speakerPlayerRunning = playerRunning;
                    speakerPlayerState = playerState;
                    updateNowPlaying();
                });
            }
        });

        // Station list
        stationAdapter = new StationListAdapter(this, stations, new StationListAdapter.Listener() {
            @Override public void onStationTapped(int index) {
                selectStation(index);
                playStation(stations.get(index), true); // tapping a channel plays it
            }
            @Override public void onHeaderTapped(String countryCode) { toggleCollapsed(countryCode); }
        });
        stationList.setLayoutManager(new LinearLayoutManager(this));
        stationList.setAdapter(stationAdapter);
        swipeAndDrag().attachToRecyclerView(stationList);
        orderCountry.setOnClickListener(v -> setGrouped(true));
        orderOwn.setOnClickListener(v -> setGrouped(false));
        loadStations();

        // Header and search
        castChip.setOnClickListener(v -> {
            if (reconnect == Reconnect.FAILED) startReconnect();
            else showCastSheet(null);
        });
        findViewById(R.id.search_bar).setOnClickListener(v -> showSearch());

        // Now playing
        npButton.setOnClickListener(v -> togglePlay());
        findViewById(R.id.np_text).setOnLongClickListener(v -> { showTiming(); return true; });
        SeekBar volume = findViewById(R.id.volume);
        volume.setProgress(prefs.getInt("volume", 100));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("volume", s.getProgress()).apply();
                if (cast.getState() == CastController.State.CONNECTED) cast.setVolume(s.getProgress() / 100.0);
                else PlaybackService.setVolume(MainActivity.this, s.getProgress() / 100f);
            }
        });

        discovery = new CastDiscovery(this, new CastDiscovery.Listener() {
            @Override public void onFound(String name, String host, int port, String id, String rs) {
                addDevice(name, host, port, id, rs);
            }
            @Override public void onError(String message) { notifyUser(message); }
        });

        startReconnect();
        for (String ip : prefs.getStringSet("ips", new HashSet<>())) {
            addDevice("Högtalare " + ip, ip, 8009, "", "");
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        updateCastChip();
        updateNowPlaying();
    }

    @Override
    protected void onStart() {
        super.onStart();
        discovery.start();
        PlaybackService.setListener((playing, title, message) -> runOnUiThread(() -> {
            localPlaying = playing;
            updateNowPlaying();
            if (message != null) notifyUser(message);
        }));
    }

    @Override
    protected void onStop() {
        super.onStop();
        discovery.stop();
        PlaybackService.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Leaving the app does not stop the radio on the speaker.
        if (cast.getState() != CastController.State.DISCONNECTED) cast.disconnect(false);
        worker.shutdown();
        net.shutdownNow();
        probes.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        reconnectTimers.removeCallbacksAndMessages(null);
    }

    // ================================================================ now playing

    private boolean casting() { return cast.getState() == CastController.State.CONNECTED; }

    private String targetName() {
        CastDevice d = cast.getDevice();
        return d != null ? d.name : "högtalaren";
    }

    /** Music is playing or about to (buffering), on the speaker or the phone. */
    private boolean musicActive() {
        if (PlaybackService.isActive()) return true;
        if (!casting()) return false;
        return speakerPlayerState.equals("PLAYING") || speakerPlayerState.equals("BUFFERING")
                || speakerPlayerState.equals("PAUSED");
    }

    /** The now-playing card, the mini bar in the search window, and the playing row in the list. */
    private void updateNowPlaying() {
        Station s = nowPlaying != null ? nowPlaying : selectedStation();
        boolean buffering, playing;
        String status;
        if (casting()) {
            playing = speakerPlayerState.equals("PLAYING");
            buffering = speakerPlayerState.equals("BUFFERING");
            if (playing) status = "Spelar på " + targetName();
            else if (buffering) status = "Startar på " + targetName() + "…";
            else if (speakerPlayerRunning) status = "Stoppad · " + targetName() + " är redo";
            else status = "Redo att spela på " + targetName();
        } else if (PlaybackService.isActive()) {
            playing = localPlaying;
            buffering = !localPlaying;
            status = playing ? "Spelar i telefonen" : "Startar i telefonen…";
        } else {
            playing = false;
            buffering = false;
            status = reconnect == Reconnect.TRYING ? "Väntar på " + prefs.getString("device_name", "högtalaren") + "…"
                    : "Tryck för att spela";
        }
        boolean active = playing || buffering;

        if (s != null) {
            Logos.bind(this, s.logo, s.name, npInitials, npLogo, 15);
            npName.setText(StationGroups.shortName(s.name));
        } else {
            npName.setText("Ingen kanal vald");
        }
        npStatus.setText(status);
        npLive.setVisibility(playing ? View.VISIBLE : View.GONE);
        npProgress.setVisibility(buffering ? View.VISIBLE : View.GONE);
        npButton.setImageResource(active ? R.drawable.ic_stop : R.drawable.ic_play);
        npButton.setContentDescription(active ? "Stoppa uppspelning" : "Spela");
        // While buffering, the button sits inside the spinning ring.
        int pad = dp(active && buffering ? 20 : 17);
        npButton.setPadding(pad, pad, pad, pad);
        npButton.setScaleX(buffering ? 0.82f : 1f);
        npButton.setScaleY(buffering ? 0.82f : 1f);

        if (miniName != null) {
            miniName.setText(s != null ? StationGroups.shortName(s.name) : "Ingen kanal vald");
            miniStatus.setText(status);
            miniButton.setImageResource(active ? R.drawable.ic_stop : R.drawable.ic_play);
            miniButton.setContentDescription(active ? "Stoppa uppspelning" : "Spela");
        }

        int index = nowPlaying != null ? stations.indexOf(nowPlaying) : -1;
        stationAdapter.setPlaying(active ? index : -1, status);
    }

    private void togglePlay() {
        if (musicActive()) {
            stopMusic();
            return;
        }
        Station s = nowPlaying != null ? nowPlaying : selectedStation();
        if (s == null) { notifyUser("Välj en kanal i listan först."); return; }
        playStation(s, stations.contains(s));
    }

    /** Stops the music. On a speaker its player stays ready, so the next start is quick. */
    private void stopMusic() {
        PlaybackService.stop(this);
        if (casting()) {
            cast.stop();
            speakerPlayerState = "IDLE"; // the speaker confirms in a moment
        }
        updateNowPlaying();
    }

    /** Plays any station: from the list, or one being tried out from the search (saved = false). */
    private void playStation(Station s, boolean saved) {
        CastController.State state = cast.getState();
        if (state == CastController.State.CONNECTING) {
            notifyUser("Vänta, ansluter till " + targetName() + "…");
            return;
        }
        nowPlaying = s;
        String name = StationGroups.shortName(s.name);
        Timing.current.start(name + (state == CastController.State.CONNECTED ? " på " + targetName() : " i telefonen"));
        if (state == CastController.State.CONNECTED) {
            PlaybackService.stop(this);
            cast.play(s.url, name);
            speakerPlayerState = "BUFFERING"; // until the speaker reports
        } else {
            askForNotifications();
            localPlaying = false;
            PlaybackService.play(this, s.url, name, prefs.getInt("volume", 100) / 100f);
        }
        if (!saved) showSnack("Provlyssnar " + name + ". Den är inte sparad.", "Lägg till", () -> addStation(s, false));
        updateNowPlaying();
    }

    /** When the phone is playing and a speaker is picked, what is playing moves to the speaker. */
    private Runnable handOver(Runnable afterConnect) {
        if (afterConnect != null) return afterConnect;
        if (!PlaybackService.isActive()) return null;
        Station s = nowPlaying;
        return () -> { if (s != null) playStation(s, stations.contains(s)); };
    }

    /** The playback notification needs this on Android 13+. Playback works without it. */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Long press on the now-playing text: how long each step of the last start took. */
    private void showTiming() {
        TextView t = new TextView(this);
        t.setText(Timing.current.summary());
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.fg));
        t.setTextIsSelectable(true);
        new AlertDialog.Builder(this).setTitle("Tid till ljud").setView(padded(t)).setPositiveButton("OK", null).show();
    }

    // ================================================================ messages

    /**
     * Messages from playback and casting. What the now-playing card already shows
     * ("Startar…", "Spelar…") is not repeated; errors and changes are shown briefly at the bottom.
     */
    private void notifyUser(String message) {
        if (message == null || message.isEmpty()) return;
        String[] shownElsewhere = {"Ansluter", "Ansluten", "Startar", "Buffrar", "Spelar", "Redan ansluten", "Stoppad.", "Volym"};
        for (String prefix : shownElsewhere) if (message.startsWith(prefix)) return;
        runOnUiThread(() -> showSnack(message, null, null));
    }

    private void showSnack(String text, String actionLabel, Runnable action) {
        ui.removeCallbacks(hideSnack);
        snackText.setText(text);
        if (actionLabel != null) {
            snackAction.setText(actionLabel);
            snackAction.setVisibility(View.VISIBLE);
            snackAction.setOnClickListener(v -> {
                snack.setVisibility(View.GONE);
                action.run();
            });
        } else {
            snackAction.setVisibility(View.GONE);
        }
        snack.setVisibility(View.VISIBLE);
        snack.announceForAccessibility(text);
        ui.postDelayed(hideSnack, actionLabel != null ? 6000 : 4000);
    }

    // ================================================================ cast chip and sheet

    private void updateCastChip() {
        CastController.State state = cast.getState();
        String last = prefs.getString("device_name", "högtalaren");
        int bg, fg, icon;
        String text, description;
        if (state == CastController.State.CONNECTED) {
            bg = R.drawable.bg_cast_connected; fg = R.color.accent_text; icon = R.drawable.ic_cast_line;
            text = targetName();
            description = "Castar till " + text + ". Tryck för att byta eller stoppa.";
        } else if (state == CastController.State.CONNECTING) {
            bg = R.drawable.bg_cast_idle; fg = R.color.accent_text; icon = R.drawable.ic_cast_line;
            text = "Ansluter…";
            description = "Ansluter till " + targetName();
        } else if (reconnect == Reconnect.TRYING) {
            bg = R.drawable.bg_cast_idle; fg = R.color.accent_text; icon = R.drawable.ic_cast_line;
            text = "Återansluter till " + last + "…";
            description = text;
        } else if (reconnect == Reconnect.FAILED) {
            bg = R.drawable.bg_cast_warn; fg = R.color.live_fg; icon = R.drawable.ic_retry;
            text = "Hittar inte " + last + " · Försök igen";
            description = "Hittar inte " + last + ". Tryck för att försöka igen.";
        } else {
            bg = R.drawable.bg_cast_idle; fg = R.color.accent_text; icon = R.drawable.ic_cast_line;
            text = "Välj högtalare";
            description = "Välj högtalare att casta till";
        }
        castChip.setBackgroundResource(bg);
        castChip.setTextColor(getColor(fg));
        castChip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        castChip.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(fg)));
        castChip.setText(text);
        castChip.setContentDescription(description);
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

        for (CastDevice dev : new ArrayList<>(devices)) {
            boolean isCurrent = connected && current != null && current.key().equals(dev.key());
            View row = inf.inflate(R.layout.item_device, list, false);
            ((TextView) row.findViewById(R.id.name)).setText(dev.name);
            TextView status = row.findViewById(R.id.status);
            String st = isCurrent
                    ? "Ansluten" + (nowPlaying != null && musicActive() ? " · spelar " + StationGroups.shortName(nowPlaying.name) : "")
                    : capitalize(dev.status);
            status.setText(st.isEmpty() ? "Chromecast" : st);
            status.setTextColor(getColor(isCurrent ? R.color.accent_text : R.color.muted));
            ImageView icon = row.findViewById(R.id.icon);
            icon.setBackgroundResource(isCurrent ? R.drawable.bg_icon_circle_accent : R.drawable.bg_icon_circle);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(isCurrent ? R.color.accent_text : R.color.fg)));
            row.findViewById(R.id.check).setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            if (isCurrent) row.setBackgroundResource(R.drawable.bg_device_selected);
            row.setContentDescription(dev.name + ", " + status.getText());
            row.setOnClickListener(v -> {
                d.dismiss();
                if (isCurrent) return;
                reconnect = Reconnect.NONE;
                Runnable after = handOver(castSheetAfterConnect);
                cast.connect(dev, after == null ? null : () -> runOnUiThread(after));
            });
            list.addView(row);
        }

        // This phone: listen here instead; what plays on the speaker moves along.
        View phone = inf.inflate(R.layout.item_device, list, false);
        ((TextView) phone.findViewById(R.id.name)).setText("Den här telefonen");
        boolean onPhone = !connected && PlaybackService.isActive();
        ((TextView) phone.findViewById(R.id.status)).setText(onPhone ? "Spelar här" : "Spela i högtalaren eller hörlurarna");
        ((ImageView) phone.findViewById(R.id.icon)).setImageResource(R.drawable.ic_phone);
        phone.findViewById(R.id.check).setVisibility(onPhone ? View.VISIBLE : View.GONE);
        phone.setOnClickListener(v -> {
            d.dismiss();
            if (!connected) return;
            Station s = nowPlaying;
            boolean wasPlaying = musicActive();
            cast.disconnect(true, () -> runOnUiThread(() -> { if (wasPlaying && s != null) playStation(s, stations.contains(s)); }));
        });
        list.addView(phone);

        ((TextView) d.findViewById(R.id.title)).setText(connected ? "Castar till " + targetName() : "Casta till");
        ((TextView) d.findViewById(R.id.subtitle)).setText(devices.isEmpty() ? "Söker efter enheter…" : "Söker fler enheter…");
        d.findViewById(R.id.connected_actions).setVisibility(connected ? View.VISIBLE : View.GONE);
        ((TextView) d.findViewById(R.id.actions_caption)).setText("Koppla från: radion spelar vidare på " + targetName()
                + ". Stoppa Chromecast-enhet: spelaren på " + targetName() + " stängs och enheten blir ledig.");
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

    private void addDevice(String name, String host, int port, String id, String rs) {
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
        if (!rs.isEmpty()) found.status = describeAnnounced(rs);
        refreshSheet();

        // Reconnect to the speaker used last time, once, without asking.
        String savedId = prefs.getString("device_id", "");
        boolean isLast = (!savedId.isEmpty() && savedId.equals(found.id))
                || found.key().equals(prefs.getString("device", null));
        if (!autoConnectTried && isLast && cast.getState() == CastController.State.DISCONNECTED) {
            autoConnectTried = true;
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
                if (last) { autoConnectTried = true; cast.connect(d, null); break; }
            }
        };
        reconnectTimers.postDelayed(again, 4000);
        reconnectTimers.postDelayed(again, 8000);
        reconnectTimers.postDelayed(() -> {
            if (reconnect == Reconnect.TRYING && cast.getState() == CastController.State.DISCONNECTED) {
                reconnect = Reconnect.FAILED;
                updateCastChip();
                updateNowPlaying();
                notifyUser("Hittade inte " + prefs.getString("device_name", "högtalaren")
                        + ". Är den på och på samma wifi?");
            }
        }, 12000);
        // A speaker added by IP address can be tried right away.
        String key = prefs.getString("device", null);
        for (CastDevice d : devices) {
            if (d.key().equals(key)) { autoConnectTried = true; cast.connect(d, null); break; }
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
                    if (!ip.matches("[0-9a-fA-F.:]+")) { notifyUser("Det ser inte ut som en IP-adress."); return; }
                    Set<String> ips = new HashSet<>(prefs.getStringSet("ips", new HashSet<>()));
                    ips.add(ip);
                    prefs.edit().putStringSet("ips", ips).apply();
                    autoConnectTried = true;
                    reconnect = Reconnect.NONE;
                    addDevice("Högtalare " + ip, ip, 8009, "", "");
                    for (CastDevice dev : devices) {
                        if (dev.key().equals(ip + ":8009")) {
                            Runnable after = handOver(afterConnect);
                            cast.connect(dev, after == null ? null : () -> runOnUiThread(after));
                        }
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
        if (stations.isEmpty()) Collections.addAll(stations, DEFAULT_STATIONS);
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
        selectedIndex = Math.max(0, Math.min(prefs.getInt("station", 0), stations.size() - 1));
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

    /** Redraws the list in the chosen order, and the order pills. */
    private void refreshStations() {
        boolean grouped = grouped();
        Set<String> collapsed = prefs.getStringSet("collapsed", new HashSet<>());
        stationAdapter.update(StationGroups.rows(stations, grouped, collapsed), selectedIndex);
        styleOrderPill(orderCountry, grouped);
        styleOrderPill(orderOwn, !grouped);
        updateNowPlaying();
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

    private void selectStation(int index) {
        selectedIndex = index;
        prefs.edit().putInt("station", index).apply();
        // Make sure the chosen station is visible, unfolding its country if needed.
        Set<String> collapsed = new HashSet<>(prefs.getStringSet("collapsed", new HashSet<>()));
        if (collapsed.remove(stations.get(index).countryCode)) prefs.edit().putStringSet("collapsed", collapsed).apply();
        refreshStations();
    }

    private Station selectedStation() {
        return (selectedIndex >= 0 && selectedIndex < stations.size()) ? stations.get(selectedIndex) : null;
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
            if (nowPlaying != null && nowPlaying.url.equals(s.url)) nowPlaying = s;
            showSnack(name + " är tillagd i din lista.", null, null);
        } else {
            showSnack(name + " finns redan i din lista.", null, null);
        }
        selectStation(index);
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
                if (selectedIndex == a) selectedIndex = b; else if (selectedIndex == b) selectedIndex = a;
                stationAdapter.update(StationGroups.rows(stations, false, new HashSet<>()), selectedIndex);
                moved = true;
                return true;
            }

            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                if (moved) {
                    moved = false;
                    saveStations();
                    prefs.edit().putInt("station", selectedIndex).apply();
                    refreshStations();
                }
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
                int p = vh.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                int index = stationAdapter.rowAt(p).stationIndex;
                Station removed = stations.remove(index);
                int oldSelected = selectedIndex;
                if (selectedIndex > index) selectedIndex--;
                else if (selectedIndex == index) selectedIndex = 0;
                saveStations();
                prefs.edit().putInt("station", selectedIndex).apply();
                refreshStations();
                showSnack(StationGroups.shortName(removed.name) + " är borttagen", "Ångra", () -> {
                    stations.add(Math.min(index, stations.size()), removed);
                    selectedIndex = oldSelected;
                    saveStations();
                    prefs.edit().putInt("station", selectedIndex).apply();
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
            TextView chip = new TextView(this);
            chip.setText(g[0]);
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(18), 0, dp(18), 0);
            chip.setMinWidth(dp(44));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            lp.setMarginEnd(dp(8));
            chips.addView(chip, lp);
            chipViews.add(chip);
            int colorIndex = i == 0 ? -1 : (i - 1) % CHIP_COLORS.length;
            String tag = g[1];
            chip.setOnClickListener(v -> {
                query.setText("");
                for (int k = 0; k < chipViews.size(); k++) styleChip(chipViews.get(k), k == 0 ? -1 : (k - 1) % CHIP_COLORS.length, chipViews.get(k) == chip);
                if (tag.startsWith("land:")) {
                    String cc = tag.substring(5);
                    lookup(() -> RadioSearch.byCountry(cc), "Populäraste i " + g[0], info, adapter, results);
                } else if (tag.startsWith("mix:")) {
                    String[] tags = tag.substring(4).split("\\|");
                    lookup(() -> RadioSearch.mix(tags), g[0], info, adapter, results);
                } else {
                    lookup(() -> RadioSearch.byTag(tag), g[0], info, adapter, results);
                }
            });
            styleChip(chip, colorIndex, i == 0);
        }

        query.setOnEditorActionListener((v, actionId, event) -> {
            String q = query.getText().toString().trim();
            if (q.isEmpty()) { info.setText("Skriv något att söka efter, eller välj en genre."); return true; }
            for (int k = 0; k < chipViews.size(); k++) styleChip(chipViews.get(k), k == 0 ? -1 : (k - 1) % CHIP_COLORS.length, false);
            lookup(() -> RadioSearch.search(q), "”" + q + "”", info, adapter, results);
            return true;
        });
        d.findViewById(R.id.back).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.add_link).setOnClickListener(v -> askForStation());
        d.setOnDismissListener(x -> { searchDialog = null; miniName = null; miniStatus = null; miniButton = null; });
        searchDialog = d;
        updateNowPlaying();
        d.show();

        String[] forYou = GENRES[0][1].substring(4).split("\\|");
        lookup(() -> RadioSearch.mix(forYou), GENRES[0][0], info, adapter, results);
    }

    /** Genre chip: "För dig" (index -1) is dark, the others pastel; the chosen one is dark. */
    private void styleChip(TextView chip, int colorIndex, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        if (selected || colorIndex < 0 && selected) {
            bg.setColor(getColor(R.color.pill_dark_bg));
            chip.setTextColor(getColor(R.color.pill_dark_fg));
        } else if (colorIndex < 0) {
            bg.setColor(getColor(R.color.card));
            bg.setStroke(dp(1), getColor(R.color.line));
            chip.setTextColor(getColor(R.color.fg));
        } else {
            bg.setColor(getColor(CHIP_COLORS[colorIndex][0]));
            chip.setTextColor(getColor(CHIP_COLORS[colorIndex][1]));
        }
        chip.setBackground(bg);
        chip.setSelected(selected);
    }

    private void lookup(Lookup what, String label, TextView info, ResultAdapter adapter, List<RadioSearch.Result> results) {
        info.setText("Söker " + label + "…");
        net.execute(() -> {
            try {
                RadioSearch.Outcome out = what.run();
                runOnUiThread(() -> {
                    results.clear();
                    results.addAll(out.results);
                    adapter.reset();
                    String head = out.note != null ? out.note + " · " : "";
                    info.setText(out.results.isEmpty()
                            ? head + "Inga kanaler hittades för " + label + ". Prova ett annat ord eller en genre."
                            : head + out.results.size() + " kanaler");
                });
            } catch (IOException e) {
                runOnUiThread(() -> info.setText(e.getMessage()));
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
                        notifyUser("Fyll i ett namn och en länk som börjar med http.");
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
