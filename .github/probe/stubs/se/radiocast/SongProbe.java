package se.radiocast;
import java.lang.reflect.*;
public class SongProbe {
  public static void main(String[] a) throws Exception {
    Radio r = new Radio(); android.content.Context c = new android.content.Context();
    SongWatcher w = new SongWatcher(c, r);
    Method fp = SongWatcher.class.getDeclaredMethod("fromPattern", String.class); fp.setAccessible(true);
    Method look = SongWatcher.class.getDeclaredMethod("lookup", String.class, String.class); look.setAccessible(true);
    String[][] st = {
      {"Radio Nova","https://novazz.ice.infomaniak.ch/novazz-128.mp3"},
      {"Nouvo Nova","https://nova-nouvo.ice.infomaniak.ch/nova-nouvo-128.mp3"},
      {"Mouv'","https://icecast.radiofrance.fr/mouv-midfi.mp3"},
      {"FIP","https://icecast.radiofrance.fr/fip-midfi.mp3"},
      {"France Inter","https://icecast.radiofrance.fr/franceinter-midfi.mp3"},
      {"Sveriges Radio - P3","https://live1.sr.se/p3-aac-320"},{"P3 b","https://sverigesradio.se/topsy/direkt/164-hi-aac"},{"P3 c","http://http-live.sr.se/p3-mp3-192"},
      {"P3 id","https://sverigesradio.se/topsy/direkt/srapi/164.mp3"},
      {"SceneSat","https://streams.scenesat.com/main/hq.mp3"},
    };
    for (String[] s : st) {
      Object f = look.invoke(w, s[1], s[0]);
      if (f == null) { System.out.println(s[0] + ": (ingen källa)"); continue; }
      Field t = f.getClass().getDeclaredField("text"); t.setAccessible(true);
      Field cv = f.getClass().getDeclaredField("cover"); cv.setAccessible(true);
      Field so = f.getClass().getDeclaredField("source"); so.setAccessible(true);
      System.out.println(s[0] + ": [" + so.get(f) + "] " + t.get(f) + "  cover=" + cv.get(f));
    }
    Method bf = SongWatcher.class.getDeclaredMethod("backfill", String.class, String.class); bf.setAccessible(true);
    bf.invoke(w, "https://live1.sr.se/p3-aac-320", "Sveriges Radio - P3");
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm");
    for (String u : new String[]{"https://live1.sr.se/p3-aac-320","https://icecast.radiofrance.fr/fip-midfi.mp3"}) {
      System.out.println("== historik " + u);
      for (SongWatcher.Song x : w.recent(u)) System.out.println("  " + f.format(new java.util.Date(x.at)) + "  " + x.text);
    }
    System.exit(0);
  }
}
