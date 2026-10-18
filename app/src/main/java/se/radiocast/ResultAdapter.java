package se.radiocast;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Search results, compact by default: a short name and "SE · 128".
 * The arrow on the right unfolds the full name, country, language, quality and genres.
 */
public class ResultAdapter extends BaseAdapter {

    private final Context context;
    private final List<RadioSearch.Result> results;
    private final Set<Integer> expanded = new HashSet<>();

    public ResultAdapter(Context context, List<RadioSearch.Result> results) {
        this.context = context;
        this.results = results;
    }

    /** Call after the result list has been replaced. */
    public void reset() {
        expanded.clear();
        notifyDataSetChanged();
    }

    @Override public int getCount() { return results.size(); }
    @Override public RadioSearch.Result getItem(int position) { return results.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row;
        LinearLayout text;
        TextView title, info, more;
        if (convertView instanceof LinearLayout) {
            row = (LinearLayout) convertView;
            text = (LinearLayout) row.getChildAt(0);
            title = (TextView) text.getChildAt(0);
            info = (TextView) text.getChildAt(1);
            more = (TextView) row.getChildAt(1);
        } else {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), 0, dp(8));
            text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(16);
            title.setTextColor(context.getColor(R.color.fg));
            info = new TextView(context);
            info.setTextSize(12);
            info.setTextColor(context.getColor(R.color.muted));
            text.addView(title);
            text.addView(info);
            more = new TextView(context);
            more.setGravity(Gravity.CENTER);
            more.setTextSize(20);
            more.setTextColor(context.getColor(R.color.accent));
            // Not focusable, so tapping the rest of the row still opens the play/add choice.
            more.setFocusable(false);
            row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }

        RadioSearch.Result r = results.get(position);
        boolean open = expanded.contains(position);
        if (open) {
            title.setSingleLine(false);
            title.setEllipsize(null);
            title.setText(r.name);
            info.setText(r.longDetails());
        } else {
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(StationGroups.shortName(r.name));
            info.setText(r.shortDetails());
        }
        info.setVisibility(info.getText().length() == 0 ? View.GONE : View.VISIBLE);
        more.setText(open ? "▴" : "▾");
        more.setContentDescription(open ? "Visa mindre" : "Visa mer om kanalen");
        more.setOnClickListener(v -> {
            if (!expanded.remove(position)) expanded.add(position);
            notifyDataSetChanged();
        });
        return row;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
