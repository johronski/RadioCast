package se.radiocast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final class Station {
        final String name;
        final String url;
        Station(String name, String url) { this.name = name; this.url = url; }
    }

    private static final String[][] DEFAULT_STATIONS = {
            {"Radio Nova", "https://novazz.ice.infomaniak.ch/novazz-128.mp3"},
            {"Nouvo Nova (ny musik)", "https://nova-nouvo.ice.infomaniak.ch/nova-nouvo-128.mp3"},
            {"Mouv'", "https://icecast.radiofrance.fr/mouv-midfi.mp3"},
            {"FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3"},
            {"France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3"},
    };
    private static final String ENTER_IP = "Ange IP-adress…";

    private final List<CastDevice> devices = new ArrayList<>();
    private final List<Station> stations = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private ArrayAdapter<String> stationAdapter;
    private ArrayAdapter<String> pickerAdapter;
    private ListView stationList;
    private TextView status;
    private TextView castTarget;
    private ImageButton castButton;
    private CastDiscovery discovery;
    private CastController cast;
    private boolean autoConnectTried;

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
                runOnUiThread(() -> showCastState(state, device));
            }
            @Override public void onStatus(String message) { say(message); }
        });

        pickerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        refreshPicker();

        stationAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, new ArrayList<>());
        stationList.setAdapter(stationAdapter);
        stationList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        stationList.setOnItemClickListener((p, v, pos, id) -> {
            prefs.edit().putInt("station", pos).apply();
            // Tapping a station while casting switches to it right away.
            if (cast.getState() == CastController.State.CONNECTED) play();
        });
        stationList.setOnItemLongClickListener((p, v, pos, id) -> { confirmDelete(pos); return true; });
        loadStations();

        for (String ip : prefs.getStringSet("ips", new HashSet<>())) {
            addDevice("Högtalare " + ip, ip, 8009);
        }

        castButton.setOnClickListener(v -> showCastPicker(null));
        findViewById(R.id.add_station).setOnClickListener(v -> askForStation());
        findViewById(R.id.play).setOnClickListener(v -> play());
        findViewById(R.id.stop).setOnClickListener(v -> cast.stop());

        SeekBar volume = findViewById(R.id.volume);
        volume.setProgress(prefs.getInt("volume", 50));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("volume", s.getProgress()).apply();
                cast.setVolume(s.getProgress() / 100.0);
            }
        });

        discovery = new CastDiscovery(this, new CastDiscovery.Listener() {
            @Override public void onFound(String name, String host, int port) { addDevice(name, host, port); }
            @Override public void onError(String message) { say(message); }
        });

        showCastState(CastController.State.DISCONNECTED, null);
        say("Tryck på cast-knappen uppe till höger för att välja högtalare.");
    }

    @Override
    protected void onStart() {
        super.onStart();
        discovery.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        discovery.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Leaving the app does not stop the radio on the speaker.
        if (cast.getState() != CastController.State.DISCONNECTED) cast.disconnect(false);
        worker.shutdown();
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
                castTarget.setText("Ingen högtalare vald");
                break;
        }
    }

    /** The cast dialog. The list updates while the search finds speakers. */
    private void showCastPicker(Runnable afterConnect) {
        discovery.start();
        refreshPicker();
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        CastDevice current = cast.getDevice();
        if (cast.getState() == CastController.State.CONNECTED && current != null) {
            b.setTitle("Castar till " + current.name);
            b.setPositiveButton("Sluta casta", (d, w) -> cast.disconnect(true));
        } else {
            b.setTitle(devices.isEmpty() ? "Söker efter högtalare…" : "Casta till");
        }
        b.setAdapter(pickerAdapter, (d, which) -> {
            if (which >= devices.size()) {
                askForIp(afterConnect);
            } else {
                CastDevice dev = devices.get(which);
                prefs.edit().putString("device", dev.key()).apply();
                cast.connect(dev, afterConnect == null ? null : () -> runOnUiThread(afterConnect));
            }
        });
        b.setNegativeButton("Stäng", null);
        b.show();
    }

    private void addDevice(String name, String host, int port) {
        CastDevice found = null;
        String key = host + ":" + port;
        for (CastDevice d : devices) {
            if (d.key().equals(key)) { found = d; break; }
        }
        if (found == null) {
            found = new CastDevice(name, host, port);
            devices.add(found);
        } else if (!name.startsWith("Högtalare ")) {
            found.name = name;
        }
        refreshPicker();
        // Reconnect to the speaker used last time, once, without asking.
        if (!autoConnectTried && key.equals(prefs.getString("device", null))
                && cast.getState() == CastController.State.DISCONNECTED) {
            autoConnectTried = true;
            cast.connect(found, null);
        }
    }

    private void refreshPicker() {
        pickerAdapter.clear();
        for (CastDevice d : devices) pickerAdapter.add(d.name);
        pickerAdapter.add(ENTER_IP);
        pickerAdapter.notifyDataSetChanged();
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
                    prefs.edit().putStringSet("ips", ips).putString("device", ip + ":8009").apply();
                    autoConnectTried = true;
                    addDevice("Högtalare " + ip, ip, 8009);
                    for (CastDevice dev : devices) {
                        if (dev.key().equals(ip + ":8009")) {
                            cast.connect(dev, afterConnect == null ? null : () -> runOnUiThread(afterConnect));
                        }
                    }
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    // ----------------------------------------------------------------- playback

    private void play() {
        Station s = selectedStation();
        if (s == null) { say("Välj en kanal först."); return; }
        if (cast.getState() == CastController.State.DISCONNECTED) {
            // Not connected yet: pick a speaker, then start the station.
            showCastPicker(this::play);
            return;
        }
        cast.play(s.url, s.name);
    }

    // ----------------------------------------------------------------- stations

    private void loadStations() {
        stations.clear();
        String json = prefs.getString("stations", null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    stations.add(new Station(o.getString("name"), o.getString("url")));
                }
            } catch (JSONException ignored) {
                stations.clear();
            }
        }
        if (stations.isEmpty()) {
            for (String[] s : DEFAULT_STATIONS) stations.add(new Station(s[0], s[1]));
        }
        stationAdapter.clear();
        for (Station s : stations) stationAdapter.add(s.name);
        stationAdapter.notifyDataSetChanged();
        int sel = Math.min(prefs.getInt("station", 0), stations.size() - 1);
        stationList.setItemChecked(Math.max(sel, 0), true);
    }

    private void saveStations() {
        JSONArray arr = new JSONArray();
        try {
            for (Station s : stations) arr.put(new JSONObject().put("name", s.name).put("url", s.url));
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
                    stations.add(new Station(n, u));
                    saveStations();
                    prefs.edit().putInt("station", stations.size() - 1).apply();
                    loadStations();
                    say(n + " är sparad.");
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private void confirmDelete(int pos) {
        if (pos < 0 || pos >= stations.size()) return;
        Station s = stations.get(pos);
        new AlertDialog.Builder(this)
                .setTitle("Ta bort " + s.name + "?")
                .setPositiveButton("Ta bort", (d, w) -> {
                    stations.remove(pos);
                    saveStations();
                    prefs.edit().putInt("station", 0).apply();
                    loadStations();
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private Station selectedStation() {
        int i = stationList.getCheckedItemPosition();
        return (i >= 0 && i < stations.size()) ? stations.get(i) : null;
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
