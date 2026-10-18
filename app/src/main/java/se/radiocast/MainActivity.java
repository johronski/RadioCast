package se.radiocast;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final Station[] DEFAULT_STATIONS = {
            new Station("Radio Nova", "https://novazz.ice.infomaniak.ch/novazz-128.mp3", "FR", "french", 128),
            new Station("Nouvo Nova (ny musik)", "https://nova-nouvo.ice.infomaniak.ch/nova-nouvo-128.mp3", "FR", "french", 128),
            new Station("Mouv'", "https://icecast.radiofrance.fr/mouv-midfi.mp3", "FR", "french", 128),
            new Station("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3", "FR", "french", 128),
            new Station("France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3", "FR", "french", 128),
    };
    private static final String ENTER_IP = "Ange IP-adress…";

    // Shortcuts in the search window, picked from the Subtest playlist's styles.
    // "mix:a|b" blends several tags, "land:XX" = most popular in a country, otherwise a tag.
    private static final String[][] GENRES = {
            {"För dig", "mix:melodic|synthpop|deep house|chillout|dubstep|afro|rap fr"},
            {"Melodisk techno", "melodic"}, {"Synthpop", "synthpop"}, {"Deep house", "deep house"},
            {"Chill", "chillout"}, {"Dubstep", "dubstep"}, {"Drum & bass", "drum and bass"},
            {"Afrobeats", "afro"}, {"Fransk rap", "mix:rap fr|french rap"}, {"Hiphop", "hip hop"},
            {"Elektroniskt", "electronic"}, {"Sverige", "land:SE"}, {"Frankrike", "land:FR"},
    };

    private final List<CastDevice> devices = new ArrayList<>();
    private final List<Station> stations = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /** Asks speakers what they are playing, a few at a time. */
    private final ExecutorService probes = Executors.newFixedThreadPool(3);

    private SharedPreferences prefs;
    private StationListAdapter stationAdapter;
    private ArrayAdapter<String> pickerAdapter;
    private Button groupToggle;
    /** Index of the chosen station in the list. */
    private int selectedIndex;
    /** What is playing or was last started, including a station only being tried out. */
    private Station nowPlaying;
    private ListView stationList;
    private TextView status;
    private TextView castTarget;
    private ImageButton castButton;
    private CastDiscovery discovery;
    private CastController cast;
    private boolean autoConnectTried;
    /** Reconnecting to the last speaker at start: none, trying, or gave up (tap to retry). */
    private enum Reconnect { NONE, TRYING, FAILED }
    private Reconnect reconnect = Reconnect.NONE;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("radiocast", MODE_PRIVATE);

        stationList = findViewById(R.id.stations);
        status = findViewById(R.id.status);
        castTarget = findViewById(R.id.cast_target);
        castButton = findViewById(R.id.cast_button);

        cast = new CastController(worker, new CastController.Listener() {
            @Override public void onStateChanged(CastController.State state, CastDevice device) {
                runOnUiThread(() -> {
                    if (state == CastController.State.CONNECTED && device != null) {
                        reconnect = Reconnect.NONE;
                        // Remember the speaker by its unique id, so it is found again if its IP address changes.
                        prefs.edit().putString("device", device.key()).putString("device_id", device.id)
                                .putString("device_name", device.name).apply();
                    }
                    showCastState(state, device);
                });
            }
            @Override public void onStatus(String message) { say(message); }
        });

        pickerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        refreshPicker();

        stationAdapter = new StationListAdapter(this, stations);
        stationList.setAdapter(stationAdapter);
        stationList.setOnItemClickListener((p, v, pos, id) -> {
            StationGroups.Row row = stationAdapter.getItem(pos);
            if (row.isHeader()) {
                toggleCollapsed(row.headerCountry);
                return;
            }
            selectStation(row.stationIndex);
            // Tapping a station while something plays switches to it right away.
            if (cast.getState() == CastController.State.CONNECTED || PlaybackService.isActive()) play();
        });
        stationList.setOnItemLongClickListener((p, v, pos, id) -> {
            StationGroups.Row row = stationAdapter.getItem(pos);
            if (!row.isHeader()) confirmDelete(row.stationIndex);
            return true;
        });
        groupToggle = findViewById(R.id.group_toggle);
        groupToggle.setOnClickListener(v -> {
            prefs.edit().putBoolean("group_by_country", !prefs.getBoolean("group_by_country", false)).apply();
            refreshStations();
        });
        loadStations();

        startReconnect();
        for (String ip : prefs.getStringSet("ips", new HashSet<>())) {
            addDevice("Högtalare " + ip, ip, 8009, "", "");
        }

        castButton.setOnClickListener(v -> showCastPicker(null));
        castTarget.setOnClickListener(v -> {
            if (reconnect == Reconnect.FAILED) startReconnect();
            else showCastPicker(null);
        });
        status.setOnLongClickListener(v -> { showTiming(); return true; });
        findViewById(R.id.add_station).setOnClickListener(v -> askForStation());
        findViewById(R.id.search_station).setOnClickListener(v -> showSearch());
        findViewById(R.id.play).setOnClickListener(v -> play());
        // Stops the music only. The player on the speaker stays ready, so the next Play is quick.
        findViewById(R.id.stop).setOnClickListener(v -> {
            if (cast.getState() == CastController.State.CONNECTED) cast.stop();
            PlaybackService.stop(this);
        });

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
            @Override public void onError(String message) { say(message); }
        });

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        showCastState(CastController.State.DISCONNECTED, null);
        if (reconnect == Reconnect.NONE) {
            say("Tryck Spela för att lyssna i telefonen, eller cast-knappen för att välja högtalare.");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        discovery.start();
        PlaybackService.setListener((playing, title, message) -> runOnUiThread(() -> {
            if (message != null) status.setText(message);
            if (cast.getState() == CastController.State.DISCONNECTED) {
                showCastState(CastController.State.DISCONNECTED, null);
            }
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
    }

    // -------------------------------------------------------------- cast button

    private void showCastState(CastController.State state, CastDevice device) {
        switch (state) {
            case CONNECTED:
                castButton.setImageResource(R.drawable.ic_cast_connected);
                castButton.setAlpha(1f);
                castButton.setContentDescription("Castar till " + device.name + ". Tryck för att byta eller sluta.");
                castTarget.setText("Castar till " + device.name);
                break;
            case CONNECTING:
                castButton.setImageResource(R.drawable.ic_cast);
                castButton.setAlpha(0.45f);
                castButton.setContentDescription("Ansluter");
                castTarget.setText(device != null ? "Ansluter till " + device.name + "…" : "Ansluter…");
                break;
            default:
                castButton.setImageResource(R.drawable.ic_cast);
                castButton.setAlpha(1f);
                castButton.setContentDescription("Välj högtalare att casta till");
                String last = prefs.getString("device_name", "");
                if (reconnect == Reconnect.TRYING) castTarget.setText("Återansluter till " + last + "…");
                else if (reconnect == Reconnect.FAILED) castTarget.setText("Hittar inte " + last + " · tryck för att försöka igen");
                else castTarget.setText(PlaybackService.isActive() ? "Spelar i telefonen" : "Ingen högtalare vald");
                break;
        }
    }

    /** The cast dialog. The list updates while the search finds speakers, with what each one is playing. */
    private void showCastPicker(Runnable afterConnect) {
        discovery.start();
        refreshPicker();
        probeSpeakers();
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        CastDevice current = cast.getDevice();
        if (cast.getState() == CastController.State.CONNECTED && current != null) {
            b.setTitle("Castar till " + current.name);
            // Two ways to let go: keep the radio playing there, or turn the speaker's player off.
            b.setPositiveButton("Stäng av på högtalaren", (d, w) -> cast.disconnect(true));
            b.setNeutralButton("Koppla från", (d, w) -> cast.disconnect(false));
        } else {
            b.setTitle(devices.isEmpty() ? "Söker efter högtalare…" : "Casta till");
        }
        b.setAdapter(pickerAdapter, (d, which) -> {
            if (which >= devices.size()) {
                askForIp(afterConnect);
            } else {
                CastDevice dev = devices.get(which);
                reconnect = Reconnect.NONE;
                Runnable after = handOver(afterConnect);
                cast.connect(dev, after == null ? null : () -> runOnUiThread(after));
            }
        });
        b.setNegativeButton("Stäng", null);
        b.show();
    }

    /** Asks every known speaker what it is doing; the list updates as answers come in. */
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
                    refreshPicker();
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
        refreshPicker();

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
        showCastState(CastController.State.DISCONNECTED, null);
        // Speakers sometimes answer the search slowly; ask again a couple of times.
        ui.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.start();
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
        ui.postDelayed(again, 4000);
        ui.postDelayed(again, 8000);
        ui.postDelayed(() -> {
            if (reconnect == Reconnect.TRYING && cast.getState() == CastController.State.DISCONNECTED) {
                reconnect = Reconnect.FAILED;
                showCastState(CastController.State.DISCONNECTED, null);
                say("Hittade inte " + prefs.getString("device_name", "högtalaren")
                        + ". Kontrollera att den är på och på samma wifi, och tryck på texten under RadioCast för att försöka igen.");
            }
        }, 12000);
        // A speaker added by IP address can be tried right away.
        String key = prefs.getString("device", null);
        for (CastDevice d : devices) {
            if (d.key().equals(key)) { autoConnectTried = true; cast.connect(d, null); break; }
        }
    }

    private void refreshPicker() {
        pickerAdapter.clear();
        for (CastDevice d : devices) pickerAdapter.add(d.label());
        pickerAdapter.add(ENTER_IP);
        pickerAdapter.notifyDataSetChanged();
    }

    /** Long press on the status line: how long each step of the last start took. */
    private void showTiming() {
        TextView t = new TextView(this);
        t.setText(Timing.current.summary());
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle("Tid till ljud")
                .setView(padded(t))
                .setPositiveButton("OK", null)
                .show();
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
                    if (!ip.matches("[0-9a-fA-F.:]+")) { say("Det ser inte ut som en IP-adress."); return; }
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

    // ----------------------------------------------------------------- playback

    /** Plays the chosen station from the list. */
    private void play() {
        Station s = selectedStation();
        if (s == null) { say("Välj en kanal först."); return; }
        playStation(s, true);
    }

    /** Plays any station: from the list, or one being tried out from the search (saved = false). */
    private void playStation(Station s, boolean saved) {
        CastController.State state = cast.getState();
        if (state == CastController.State.CONNECTING) {
            say("Vänta, ansluter till högtalaren…");
            return;
        }
        nowPlaying = s;
        String name = StationGroups.shortName(s.name);
        CastDevice target = cast.getDevice();
        Timing.current.start(name + (state == CastController.State.CONNECTED && target != null
                ? " på " + target.name : " i telefonen"));
        if (state == CastController.State.CONNECTED) {
            PlaybackService.stop(this);
            cast.play(s.url, name);
        } else {
            // No speaker chosen: play on the phone.
            askForNotifications();
            say("Startar " + name + " i telefonen…");
            PlaybackService.play(this, s.url, name, prefs.getInt("volume", 100) / 100f);
            castTarget.setText("Spelar i telefonen");
        }
        if (!saved) say("Provlyssnar " + name + ". Den är inte sparad i din lista.");
    }

    /** When the phone is playing and a speaker is picked, what is playing moves to the speaker. */
    private Runnable handOver(Runnable afterConnect) {
        if (afterConnect != null) return afterConnect;
        if (!PlaybackService.isActive()) return null;
        Station s = nowPlaying;
        return () -> {
            if (s != null) playStation(s, stations.contains(s));
            else play();
        };
    }

    /** The playback notification needs this on Android 13+. Playback works without it. */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ----------------------------------------------------------------- stations

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
                        stations.add(new Station(name, url, o.optString("cc"), o.optString("lang"), o.optInt("br")));
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
        if (stations.isEmpty()) {
            for (Station s : DEFAULT_STATIONS) stations.add(s);
        }
        if (migrated) saveStations();
        selectedIndex = Math.max(0, Math.min(prefs.getInt("station", 0), stations.size() - 1));
        refreshStations();
    }

    /** Redraws the list in the chosen order (own order, or grouped by country). */
    private void refreshStations() {
        boolean grouped = prefs.getBoolean("group_by_country", false);
        Set<String> collapsed = prefs.getStringSet("collapsed", new HashSet<>());
        stationAdapter.update(StationGroups.rows(stations, grouped, collapsed), selectedIndex, grouped);
        groupToggle.setText(grouped ? "Per land \u25BE" : "Egen ordning \u25BE");
        groupToggle.setContentDescription(grouped
                ? "Kanalerna visas per land. Tryck för egen ordning."
                : "Kanalerna visas i egen ordning. Tryck för att visa per land.");
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

    private void saveStations() {
        JSONArray arr = new JSONArray();
        try {
            for (Station s : stations) {
                arr.put(new JSONObject().put("name", s.name).put("url", s.url)
                        .put("cc", s.countryCode).put("lang", s.language).put("br", s.bitrate));
            }
        } catch (JSONException ignored) { }
        prefs.edit().putString("stations", arr.toString()).apply();
    }

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
                .setTitle("Lägg till kanal")
                .setMessage("Klistra in en direktlänk till strömmen. Länkar finns t.ex. på radio-browser.info.")
                .setView(padded(box))
                .setPositiveButton("Spara", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (n.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
                        say("Fyll i ett namn och en länk som börjar med http.");
                        return;
                    }
                    addStation(Station.withGuessedCountry(n, u), false);
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    // ------------------------------------------------------------------- search

    private interface Lookup { RadioSearch.Outcome run() throws IOException; }

    private void showSearch() {
        int pad = dp(16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText query = new EditText(this);
        query.setHint("T.ex. sverige p3, techno, franska nyheter");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Button go = new Button(this);
        go.setText("Sök");
        row.addView(query, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(go);
        box.addView(row);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chips);
        box.addView(chipScroll);

        TextView info = new TextView(this);
        info.setTextColor(getColor(R.color.muted));
        info.setPadding(0, dp(4), 0, dp(4));
        box.addView(info);

        List<RadioSearch.Result> results = new ArrayList<>();
        ResultAdapter adapter = new ResultAdapter(this, results);
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < results.size()) chooseResult(results.get(pos));
        });
        box.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));

        for (String[] g : GENRES) {
            Button chip = new Button(this, null, android.R.attr.borderlessButtonStyle);
            chip.setText(g[0]);
            chip.setAllCaps(false);
            chip.setTextColor(getColor(R.color.accent));
            String tag = g[1];
            chip.setOnClickListener(v -> {
                query.setText("");
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
            chips.addView(chip);
        }

        go.setOnClickListener(v -> {
            String q = query.getText().toString().trim();
            if (q.isEmpty()) { info.setText("Skriv något att söka efter, eller välj en genre."); return; }
            lookup(() -> RadioSearch.search(q), "\u201d" + q + "\u201d", info, adapter, results);
        });
        query.setOnEditorActionListener((v, actionId, event) -> { go.performClick(); return true; });

        new AlertDialog.Builder(this)
                .setTitle("Hitta radiokanal")
                .setView(box)
                .setNegativeButton("Stäng", null)
                .show();

        String[] forYou = GENRES[0][1].substring(4).split("\\|");
        lookup(() -> RadioSearch.mix(forYou), GENRES[0][0], info, adapter, results);
    }

    private void lookup(Lookup what, String label, TextView info, ResultAdapter adapter,
                        List<RadioSearch.Result> results) {
        info.setText("Söker " + label + "…");
        net.execute(() -> {
            try {
                RadioSearch.Outcome out = what.run();
                List<RadioSearch.Result> found = out.results;
                runOnUiThread(() -> {
                    results.clear();
                    results.addAll(found);
                    adapter.reset();
                    String head = out.note != null ? out.note + ". " : "";
                    info.setText(found.isEmpty()
                            ? head + "Inga kanaler hittades för " + label + ". Prova ett annat ord eller en genre."
                            : head + found.size() + " kanaler. Tryck på en för att spela eller spara, pilen visar mer.");
                });
            } catch (IOException e) {
                runOnUiThread(() -> info.setText(e.getMessage()));
            }
        });
    }

    /** Tapping a search result: try it out, or keep it. */
    private void chooseResult(RadioSearch.Result r) {
        String[] choices = {"Spela nu, utan att spara", "Lägg till och spela", "Lägg till i listan"};
        new AlertDialog.Builder(this)
                .setTitle(StationGroups.shortName(r.name))
                .setItems(choices, (d, which) -> {
                    Station s = r.toStation();
                    if (which == 0) playStation(s, false);
                    else addStation(s, which == 1);
                })
                .setNegativeButton("Avbryt", null)
                .show();
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
            say(name + " är tillagd i din lista.");
        } else {
            say(name + " finns redan i din lista.");
        }
        selectStation(index);
        int pos = stationAdapter.positionOf(index);
        if (pos >= 0) stationList.smoothScrollToPosition(pos);
        if (playNow) play();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void confirmDelete(int index) {
        if (index < 0 || index >= stations.size()) return;
        Station s = stations.get(index);
        new AlertDialog.Builder(this)
                .setTitle("Ta bort " + StationGroups.shortName(s.name) + "?")
                .setPositiveButton("Ta bort", (d, w) -> {
                    stations.remove(index);
                    saveStations();
                    if (selectedIndex > index) selectedIndex--;
                    else if (selectedIndex == index) selectedIndex = 0;
                    prefs.edit().putInt("station", selectedIndex).apply();
                    loadStations();
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private Station selectedStation() {
        return (selectedIndex >= 0 && selectedIndex < stations.size()) ? stations.get(selectedIndex) : null;
    }

    // -------------------------------------------------------------------- utils

    private void say(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private View padded(View v) {
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }
}
