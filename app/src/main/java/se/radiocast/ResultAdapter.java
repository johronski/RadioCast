package se.radiocast;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Search results as cards: a short name and "SE · 128 kbps".
 * Tapping a card unfolds the details and the Provlyssna / + Lägg till buttons.
 */
public class ResultAdapter extends BaseAdapter {

    public interface Listener {
        void onTry(RadioSearch.Result r);
        void onAdd(RadioSearch.Result r);
    }

    private final Context context;
    private final List<RadioSearch.Result> results;
    private final Listener listener;
    private final Set<Integer> expanded = new HashSet<>();

    public ResultAdapter(Context context, List<RadioSearch.Result> results, Listener listener) {
        this.context = context;
        this.results = results;
        this.listener = listener;
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
        View v = convertView != null ? convertView
                : LayoutInflater.from(context).inflate(R.layout.item_result, parent, false);
        RadioSearch.Result r = results.get(position);
        boolean open = expanded.contains(position);

        TextView initials = v.findViewById(R.id.initials);
        ImageView logo = v.findViewById(R.id.logo);
        TextView name = v.findViewById(R.id.name);
        TextView sub = v.findViewById(R.id.sub);
        ImageView chevron = v.findViewById(R.id.chevron);
        View details = v.findViewById(R.id.details);
        TextView detailsText = v.findViewById(R.id.details_text);

        Logos.bind(context, r.favicon, r.name, initials, logo, 13);
        name.setText(open ? r.name : StationGroups.shortName(r.name));
        name.setMaxLines(open ? 3 : 1);
        name.setEllipsize(open ? null : android.text.TextUtils.TruncateAt.END);
        sub.setText(r.shortDetails());
        sub.setVisibility(r.shortDetails().isEmpty() ? View.GONE : View.VISIBLE);
        chevron.setImageResource(open ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
        details.setVisibility(open ? View.VISIBLE : View.GONE);
        detailsText.setText(r.longDetails());
        detailsText.setVisibility(r.longDetails().isEmpty() ? View.GONE : View.VISIBLE);

        View head = v.findViewById(R.id.head);
        head.setContentDescription(StationGroups.shortName(r.name) + ". " + (open ? "Tryck för att visa mindre." : "Tryck för att visa mer."));
        head.setOnClickListener(x -> {
            if (!expanded.remove(position)) expanded.add(position);
            notifyDataSetChanged();
        });
        v.findViewById(R.id.try_button).setOnClickListener(x -> listener.onTry(r));
        v.findViewById(R.id.add_button).setOnClickListener(x -> listener.onAdd(r));
        return v;
    }
}
