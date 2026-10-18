package se.radiocast;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The channel list on the main screen: plain, or under a foldable heading per country. */
public class StationListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onStationTapped(int stationIndex);
        void onHeaderTapped(String countryCode);
    }

    private static final int HEADER = 0, STATION = 1;

    private final Context context;
    private final List<Station> stations;
    private final Listener listener;
    private List<StationGroups.Row> rows = new ArrayList<>();
    private int selected = -1;
    /** Station playing right now (phone or speaker), or -1. */
    private int playing = -1;
    /** Line under the playing station, e.g. "Spelar på Kök". */
    private String playingText = "";

    public StationListAdapter(Context context, List<Station> stations, Listener listener) {
        this.context = context;
        this.stations = stations;
        this.listener = listener;
    }

    public void update(List<StationGroups.Row> rows, int selected) {
        this.rows = rows;
        this.selected = selected;
        notifyDataSetChanged();
    }

    public void setPlaying(int stationIndex, String text) {
        if (stationIndex == playing && text.equals(playingText)) return;
        playing = stationIndex;
        playingText = text;
        notifyDataSetChanged();
    }

    public StationGroups.Row rowAt(int position) { return rows.get(position); }

    /** List position of a station, or -1 if its group is folded. */
    public int positionOf(int stationIndex) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).stationIndex == stationIndex) return i;
        return -1;
    }

    @Override public int getItemCount() { return rows.size(); }
    @Override public int getItemViewType(int position) { return rows.get(position).isHeader() ? HEADER : STATION; }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(context);
        if (viewType == HEADER) return new HeaderHolder(inf.inflate(R.layout.item_header, parent, false));
        return new StationHolder(inf.inflate(R.layout.item_station, parent, false));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        StationGroups.Row row = rows.get(position);
        if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind(row);
        else ((StationHolder) holder).bind(row.stationIndex);
    }

    @Override
    public void onViewRecycled(RecyclerView.ViewHolder holder) {
        if (holder instanceof StationHolder) ((StationHolder) holder).stopBars();
    }

    final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final ImageView chevron;

        HeaderHolder(View v) {
            super(v);
            label = v.findViewById(R.id.label);
            chevron = v.findViewById(R.id.chevron);
            v.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onHeaderTapped(rows.get(p).headerCountry);
            });
        }

        void bind(StationGroups.Row row) {
            String heading = StationGroups.heading(row.headerCountry);
            label.setText(heading.toUpperCase(Locale.ROOT) + "  ·  " + row.count);
            chevron.setImageResource(row.collapsed ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_down);
            itemView.setContentDescription(heading + ", " + row.count + " kanaler. Tryck för att "
                    + (row.collapsed ? "visa." : "dölja."));
        }
    }

    final class StationHolder extends RecyclerView.ViewHolder {
        final TextView initials, name, sub;
        final ImageView logo, playHint;
        final View equalizer;
        final View[] bars;
        final List<ObjectAnimator> animators = new ArrayList<>();

        StationHolder(View v) {
            super(v);
            initials = v.findViewById(R.id.initials);
            logo = v.findViewById(R.id.logo);
            name = v.findViewById(R.id.name);
            sub = v.findViewById(R.id.sub);
            playHint = v.findViewById(R.id.play_hint);
            equalizer = v.findViewById(R.id.equalizer);
            bars = new View[]{v.findViewById(R.id.bar1), v.findViewById(R.id.bar2),
                    v.findViewById(R.id.bar3), v.findViewById(R.id.bar4)};
            v.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onStationTapped(rows.get(p).stationIndex);
            });
        }

        void bind(int index) {
            Station s = stations.get(index);
            boolean isPlaying = index == playing;
            Logos.bind(context, s.logo, s.name, initials, logo, 14);
            name.setText(StationGroups.shortName(s.name));
            // Status and controls live in the player at the bottom; here the row is only marked:
            // the name in the accent colour and the moving bars.
            name.setTextColor(context.getColor(isPlaying ? R.color.accent_text : R.color.fg));
            sub.setText(s.subtitle());
            sub.setTextColor(context.getColor(R.color.muted));
            sub.setVisibility(sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
            itemView.setBackgroundResource(0);
            itemView.setElevation(0);
            playHint.setVisibility(View.GONE);
            equalizer.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
            if (isPlaying) startBars(); else stopBars();
            itemView.setContentDescription(isPlaying
                    ? s.name + ", spelar nu"
                    : "Spela " + s.name + (s.genre.isEmpty() ? "" : ", " + s.genre));
        }

        void startBars() {
            if (!animators.isEmpty()) return;
            float[][] heights = {{0.45f, 1f}, {1f, 0.35f}, {0.3f, 0.8f}, {0.75f, 0.4f}};
            long[] durations = {420, 560, 380, 500};
            for (int i = 0; i < bars.length; i++) {
                View bar = bars[i];
                bar.setPivotY(bar.getLayoutParams().height);
                ObjectAnimator a = ObjectAnimator.ofFloat(bar, "scaleY", heights[i][0], heights[i][1]);
                a.setDuration(durations[i]);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.start();
                animators.add(a);
            }
        }

        void stopBars() {
            for (ObjectAnimator a : animators) a.cancel();
            animators.clear();
            for (View bar : bars) bar.setScaleY(0.6f);
        }
    }
}
