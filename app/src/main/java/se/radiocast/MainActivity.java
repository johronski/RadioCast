package se.radiocast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
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

    static final class Device {
        String name;
        final String host;
        final int port;
        Device(String name, String host, int port) { this.name = name; this.host = host; this.port = port; }
    }

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

    private final List<Device> devices = new ArrayList<>();
    private final List<Station> stations = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayAdapter<String> stationAdapter;
    private Spinner deviceSpinner;
    private ListView stationList;
    private TextView status;
    private CastDiscovery discovery;
    private volatile CastClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("radiocast", MODE_PRIVATE);

        deviceSpinner = findViewById(R.id.devices);
        stationList = findViewById(R.id.stations);
        status = findViewById(R.id.status);

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);

        stationAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, new ArrayList<>());
        stationList.setAdapter(stationAdapter);
        stationList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        stationList.setOnItemClickListener((p, v, pos, id) -> prefs.edit().putInt("station", pos).apply());
        stationList.setOnItemLongClickListener((p, v, pos, id) -> { confirmDelete(pos); return true; });

        loadStations();
        for (String ip : prefs.getStringSet("ips", new HashSet<>())) {
            addDevice("Högtalare " + ip, ip, 8009);
        }

        findViewById(R.id.scan).setOnClickListener(v -> startDiscovery());
        findViewById(R.id.enter_ip).setOnClickListener(v -> askForIp());
        findViewById(R.id.add_station).setOnClickListener(v -> askForStation());
        findViewById(R.id.play).setOnClickListener(v -> play());
        findViewById(R.id.stop).setOnClickListener(v -> stopPlayback());

        SeekBar volume = findViewById(R.id.volume);
        volume.setProgress(prefs.getInt("volume", 50));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                int value = s.getProgress();
                prefs.edit().putInt("volume", value).apply();
                setVolume(value / 100.0);
            }
        });

        discovery = new CastDiscovery(this, new CastDiscovery.Listener() {
            @Override public void onFound(String name, String host, int port) {
                addDevice(name, host, port);
                say("Hittade " + name + ".");
            }
            @Override public void onError(String message) { say(message); }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        startDiscovery();
    }

    @Override
    protected void onStop() {
        super.onStop();
        discovery.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CastClient c = client;
        client = null;
        // Closing the connection does not stop the music on the speaker.
        worker.execute(() -> { if (c != null) c.close(); });
        worker.shutdown();
    }

    // ------------------------------------------------------------------ devices

    private void startDiscovery() {
        say(devices.isEmpty() ? "Söker efter högtalare…" : "Söker efter fler högtalare…");
        discovery.start();
        deviceSpinner.postDelayed(() -> {
            if (devices.isEmpty() && !isFinishing()) {
                say("Ingen högtalare hittad. Kontrollera wifi, eller tryck Ange IP-adress.");
            }
        }, 8000);
    }

    private void addDevice(String name, String host, int port) {
        for (Device d : devices) {
            if (d.host.equals(host)) {
                if (!name.startsWith("Högtalare ")) d.name = name;
                refreshDevices();
                return;
            }
        }
        devices.add(new Device(name, host, port));
        refreshDevices();
        String last = prefs.getString("device", null);
        if (host.equals(last) || devices.size() == 1) {
            deviceSpinner.setSelection(devices.size() - 1);
        }
    }

    private void refreshDevices() {
        int sel = deviceSpinner.getSelectedItemPosition();
        deviceAdapter.clear();
        for (Device d : devices) deviceAdapter.add(d.name);
        deviceAdapter.notifyDataSetChanged();
        if (sel >= 0 && sel < devices.size()) deviceSpinner.setSelection(sel);
    }

    private Device selectedDevice() {
        int i = deviceSpinner.getSelectedItemPosition();
        return (i >= 0 && i < devices.size()) ? devices.get(i) : null;
    }

    private void askForIp() {
        EditText input = new EditText(this);
        input.setHint("t.ex. 192.168.1.50");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Högtalarens IP-adress")
                .setMessage("Finns i Google Home: tryck på högtalaren, kugghjulet, Enhetsinformation.")
                .setView(padded(input))
                .setPositiveButton("Lägg till", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.matches("[0-9a-fA-F.:]+")) { say("Det ser inte ut som en IP-adress."); return; }
                    Set<String> ips = new HashSet<>(prefs.getStringSet("ips", new HashSet<>()));
                    ips.add(ip);
                    prefs.edit().putStringSet("ips", ips).putString("device", ip).apply();
                    addDevice("Högtalare " + ip, ip, 8009);
                    for (int i = 0; i < devices.size(); i++) if (devices.get(i).host.equals(ip)) deviceSpinner.setSelection(i);
                    say("Högtalaren är sparad. Tryck Spela.");
                })
                .setNegativeButton("Avbryt", null)
                .show();
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

    // ----------------------------------------------------------------- playback

    private void play() {
        Device d = selectedDevice();
        Station s = selectedStation();
        if (d == null) { say("Välj en högtalare först, eller tryck Ange IP-adress."); return; }
        if (s == null) { say("Välj en kanal först."); return; }
        prefs.edit().putString("device", d.host).apply();
        say("Startar " + s.name + " på " + d.name + "…");
        worker.execute(() -> {
            try {
                withClient(d, c -> c.play(s.url, s.name, contentType(s.url)));
                say("Spelar " + s.name + " på " + d.name + ".");
            } catch (IOException e) {
                say(e.getMessage() != null ? e.getMessage() : "Det gick inte att nå högtalaren.");
            }
        });
    }

    private void stopPlayback() {
        Device d = selectedDevice();
        if (d == null) return;
        worker.execute(() -> {
            try {
                withClient(d, CastClient::stop);
                say("Stoppad.");
            } catch (IOException e) {
                say(e.getMessage() != null ? e.getMessage() : "Det gick inte att nå högtalaren.");
            }
        });
    }

    private void setVolume(double level) {
        Device d = selectedDevice();
        if (d == null) return;
        worker.execute(() -> {
            try {
                withClient(d, c -> c.setVolume(level));
                say("Volym " + Math.round(level * 100) + " %.");
            } catch (IOException e) {
                say(e.getMessage() != null ? e.getMessage() : "Det gick inte att nå högtalaren.");
            }
        });
    }

    private interface CastAction { void run(CastClient c) throws IOException; }

    /** Runs on the worker thread. Connects if needed and retries once on a dropped connection. */
    private void withClient(Device d, CastAction action) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            CastClient c = client;
            boolean reused = true;
            if (c == null || !c.isConnected() || !c.getHost().equals(d.host)) {
                reused = false;
                if (c != null) c.close();
                c = new CastClient(d.host, d.port, new CastClient.Listener() {
                    @Override public void onMessage(String text) { }
                    @Override public void onDisconnected(String reason) { client = null; }
                });
                try {
                    c.connect();
                } catch (IOException e) {
                    throw new IOException("Når inte " + d.name + ". Är den på och på samma wifi?");
                }
                client = c;
            }
            try {
                action.run(c);
                return;
            } catch (IOException e) {
                if (!c.isConnected()) {
                    c.close();
                    client = null;
                    // A stale connection gets one fresh attempt.
                    if (reused && attempt == 0) continue;
                }
                throw e;
            }
        }
        throw new IOException("Det gick inte att nå högtalaren.");
    }

    private static String contentType(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        if (u.contains(".aac")) return "audio/aac";
        if (u.contains(".ogg")) return "audio/ogg";
        return "audio/mpeg";
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
