package android.content;
import java.util.*;
public class SharedPreferences {
  final Map<String,Object> m = new HashMap<>();
  public String getString(String k, String d) { Object v=m.get(k); return v==null?d:(String)v; }
  public long getLong(String k, long d) { Object v=m.get(k); return v==null?d:(Long)v; }
  public int getInt(String k, int d) { Object v=m.get(k); return v==null?d:(Integer)v; }
  public boolean getBoolean(String k, boolean d) { Object v=m.get(k); return v==null?d:(Boolean)v; }
  public Editor edit() { return new Editor(); }
  public class Editor {
    public Editor putString(String k,String v){m.put(k,v);return this;}
    public Editor putLong(String k,long v){m.put(k,v);return this;}
    public Editor putInt(String k,int v){m.put(k,v);return this;}
    public Editor putBoolean(String k,boolean v){m.put(k,v);return this;}
    public Editor remove(String k){m.remove(k);return this;}
    public void apply(){}
  }
}
