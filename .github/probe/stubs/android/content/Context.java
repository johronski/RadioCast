package android.content;
public class Context {
  public static final int MODE_PRIVATE = 0; public static final String CONNECTIVITY_SERVICE = "c"; public static final String POWER_SERVICE = "p";
  public Context getApplicationContext() { return this; }
  final SharedPreferences p = new SharedPreferences();
  public SharedPreferences getSharedPreferences(String n, int m) { return p; }
  public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
  public Object getSystemService(String s) { return s.equals(POWER_SERVICE) ? new android.os.PowerManager() : new android.net.ConnectivityManager(); }
  public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
}
