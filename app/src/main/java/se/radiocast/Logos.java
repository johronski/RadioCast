package se.radiocast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Channel logos: the real logo on a neutral plate when there is one,
 * otherwise initials on a soft pastel tile. Logos are cached in memory and on the phone.
 */
public final class Logos {

    private static final int[][] PASTELS = {
            {R.color.sage_bg, R.color.sage_fg}, {R.color.sand_bg, R.color.sand_fg},
            {R.color.sky_bg, R.color.sky_fg}, {R.color.rose_bg, R.color.rose_fg},
            {R.color.lavender_bg, R.color.lavender_fg},
    };

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(60);
    private static final Set<String> FAILED = new HashSet<>();
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Logos() { }

    /** Shows the channel's logo in {@code logo}, with initials in {@code initials} until (or unless) it loads. */
    public static void bind(Context context, String logoUrl, String name, TextView initials, ImageView logo, float radiusDp) {
        float density = context.getResources().getDisplayMetrics().density;
        int[] pair = PASTELS[Station.paletteIndex(name)];
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(context.getColor(pair[0]));
        tile.setCornerRadius(radiusDp * density);
        initials.setBackground(tile);
        initials.setTextColor(context.getColor(pair[1]));
        String text = Station.initials(name);
        initials.setText(text);
        initials.setTextSize(text.length() >= 5 ? 11 : text.length() == 4 ? 12 : 14);
        initials.setVisibility(View.VISIBLE);

        logo.setTag(logoUrl);
        logo.setVisibility(View.GONE);
        logo.setImageDrawable(null);
        if (logoUrl == null || logoUrl.isEmpty()) return;
        synchronized (FAILED) { if (FAILED.contains(logoUrl)) return; }

        Bitmap cached = MEMORY.get(logoUrl);
        if (cached != null) {
            show(logo, initials, cached);
            return;
        }
        File cacheDir = new File(context.getCacheDir(), "logos");
        LOADER.execute(() -> {
            Bitmap b = load(cacheDir, logoUrl);
            if (b == null) {
                synchronized (FAILED) { FAILED.add(logoUrl); }
                return;
            }
            MEMORY.put(logoUrl, b);
            MAIN.post(() -> {
                // The row may have been reused for another channel meanwhile.
                if (logoUrl.equals(logo.getTag())) show(logo, initials, b);
            });
        });
    }

    private static void show(ImageView logo, TextView initials, Bitmap b) {
        logo.setImageBitmap(b);
        logo.setVisibility(View.VISIBLE);
        initials.setVisibility(View.INVISIBLE);
    }

    private static Bitmap load(File dir, String url) {
        File file = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        byte[] data = null;
        try {
            if (file.exists()) data = Files.readAllBytes(file.toPath());
        } catch (IOException ignored) { }
        if (data == null) {
            data = download(url);
            if (data == null) return null;
            try {
                if (dir.isDirectory() || dir.mkdirs()) {
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
                }
            } catch (IOException ignored) { }
        }
        return decode(data, 160);
    }

    private static byte[] download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "RadioCast/2.0");
            try {
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 2_000_000) return null; // not a logo
                        b.write(buf, 0, n);
                    }
                    return b.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Decodes at roughly the size shown, to save memory. */
    private static Bitmap decode(byte[] data, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
