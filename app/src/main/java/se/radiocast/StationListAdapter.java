package se.radiocast;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The channel list on the main screen: plain, or with a foldable heading per country. */
public class StationListAdapter extends BaseAdapter {

    private final Context context;
    private final List<Station> stations;
    private List<StationGroups.Row> rows = new ArrayList<>();
    private int selected = -1;
    private boolean grouped;

    public StationListAdapter(Context context, List<Station> stations) {
        this.context = context;
        this.stations = stations;
    }

    public void update(List<StationGroups.Row> rows, int selected, boolean grouped) {
        this.rows = rows;
        this.selected = selected;
        this.grouped = grouped;
        notifyDataSetChanged();
    }

    /** List position of a station, or -1 if its group is folded. */
    public int positionOf(int stationIndex) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).stationIndex == stationIndex) return i;
        return -1;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public StationGroups.Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return rows.get(position).isHeader() ? 0 : 1; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        StationGroups.Row row = rows.get(position);
        return row.isHeader() ? header(row, convertView) : station(row, convertView);
    }

    private View header(StationGroups.Row row, View convertView) {
        TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(context);
        t.setPadding(dp(14), dp(14), dp(14), dp(6));
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(context.getColor(R.color.muted));
        String arrow = row.collapsed ? "▸  " : "▾  ";
        t.setText(arrow + StationGroups.heading(row.headerCountry).toUpperCase(Locale.ROOT) + "  ·  " + row.count);
        t.setContentDescription(StationGroups.heading(row.headerCountry) + ", " + row.count + " kanaler. Tryck för att "
                + (row.collapsed ? "visa." : "dölja."));
        return t;
    }

    private View station(StationGroups.Row row, View convertView) {
        LinearLayout box;
        TextView name, code;
        if (convertView instanceof LinearLayout) {
            box = (LinearLayout) convertView;
            name = (TextView) box.getChildAt(0);
            code = (TextView) box.getChildAt(1);
        } else {
            box = new LinearLayout(context);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setMinimumHeight(dp(48));
            name = new TextView(context);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextSize(16);
            code = new TextView(context);
            code.setTextSize(12);
            code.setTextColor(context.getColor(R.color.muted));
            box.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(code);
        }
        Station s = stations.get(row.stationIndex);
        boolean isSelected = row.stationIndex == selected;
        box.setPadding(dp(grouped ? 26 : 14), dp(8), dp(14), dp(8));
        name.setText(StationGroups.shortName(s.name));
        name.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        name.setTextColor(context.getColor(isSelected ? R.color.accent : R.color.fg));
        // The country is already in the heading when grouped.
        code.setText(grouped ? "" : s.countryCode);
        box.setContentDescription(s.name + (isSelected ? ", vald" : ""));
        return box;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
