package ir.meelano.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real product photos from Atiran (dbo.ka_image: shka, pic).
 *
 * Photos are loaded lazily, only for the cards on screen, in batches of up to 24 per query on a thread of their own
 * (the app's database queue is never blocked). Each photo is shrunk once to a small JPEG kept in memory and in the
 * app cache folder, keyed by product code + photo size, so a photo changed in Atiran is fetched again. A card only
 * receives the photo of the product it still shows (lists reuse views while scrolling).
 */
final class MeelanoAtiranImages {
    interface ConnectionSource { Connection open() throws Exception; }

    private static final int BATCH = 24;
    private final File dir;
    private final ConnectionSource source;
    private final int targetPx;
    private final LruCache<String, Bitmap> memory;
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor();
    private final ExecutorService decoder = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<ImageView, String> showing = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, List<WeakReference<ImageView>>> waiting = new HashMap<>();
    private final LinkedHashMap<Long, String> pending = new LinkedHashMap<>();
    private final Set<String> missing = Collections.synchronizedSet(new HashSet<>());
    private boolean draining = false;

    MeelanoAtiranImages(File cacheDir, ConnectionSource source, int targetPx) {
        this.dir = new File(cacheDir, "atiran_images");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.source = source;
        this.targetPx = Math.max(96, Math.min(targetPx, 480));
        int maxKb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 10, 24 * 1024);
        memory = new LruCache<String, Bitmap>(Math.max(4 * 1024, maxKb)) {
            @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getByteCount() / 1024); }
        };
    }

    static String key(long shka, long length) { return shka + "_" + length; }

    /** The card stops waiting for a photo (it now shows a product without an Atiran photo). */
    void forget(ImageView view) { if (view != null) showing.remove(view); }

    boolean isMissing(long shka, long length) { return missing.contains(key(shka, length)); }

    /** Shows the Atiran photo of product {@code shka} in {@code view} as soon as it is available. */
    void load(ImageView view, long shka, long length) {
        if (view == null || shka <= 0 || length <= 0) { forget(view); return; }
        final String k = key(shka, length);
        showing.put(view, k);
        Bitmap cached = memory.get(k);
        if (cached != null && !cached.isRecycled()) { set(view, cached); return; }
        if (missing.contains(k)) return;
        synchronized (waiting) {
            List<WeakReference<ImageView>> list = waiting.get(k);
            boolean first = list == null;
            if (first) { list = new ArrayList<>(); waiting.put(k, list); }
            list.add(new WeakReference<>(view));
            if (!first) return; // already on its way
        }
        decoder.execute(() -> {
            File f = new File(dir, k + ".jpg");
            Bitmap b = f.length() > 0 ? decodeFile(f) : null;
            if (b != null) deliver(k, b);
            else enqueue(shka, k);
        });
    }

    private void enqueue(long shka, String k) {
        synchronized (pending) {
            pending.put(shka, k);
            if (draining) return;
            draining = true;
        }
        fetcher.execute(this::drain);
    }

    private void drain() {
        Connection c = null;
        try {
            while (true) {
                Map<Long, String> batch = new LinkedHashMap<>();
                synchronized (pending) {
                    Iterator<Map.Entry<Long, String>> it = pending.entrySet().iterator();
                    while (it.hasNext() && batch.size() < BATCH) { Map.Entry<Long, String> e = it.next(); batch.put(e.getKey(), e.getValue()); it.remove(); }
                    if (batch.isEmpty()) { draining = false; break; }
                }
                try {
                    if (c == null || c.isClosed()) c = source.open();
                    StringBuilder in = new StringBuilder();
                    for (int i = 0; i < batch.size(); i++) in.append(i == 0 ? "?" : ",?");
                    Set<Long> got = new HashSet<>();
                    try (PreparedStatement ps = c.prepareStatement("SELECT shka, pic FROM dbo.ka_image WHERE shka IN (" + in + ") AND DATALENGTH(pic)>100 ORDER BY shka, rdf DESC")) {
                        int i = 1;
                        for (Long s : batch.keySet()) ps.setLong(i++, s);
                        try (ResultSet r = ps.executeQuery()) {
                            while (r.next()) {
                                long s = r.getLong(1);
                                if (got.contains(s)) continue;
                                byte[] bytes = r.getBytes(2);
                                String k = batch.get(s);
                                Bitmap b = shrink(bytes);
                                if (b == null || k == null) continue;
                                got.add(s);
                                save(k, b);
                                deliver(k, b);
                            }
                        }
                    }
                    for (Map.Entry<Long, String> e : batch.entrySet()) if (!got.contains(e.getKey())) fail(e.getValue());
                } catch (Throwable t) {
                    // Network trouble: give up on this batch for now (the drawn picture stays); a later screen retries.
                    for (String k : batch.values()) { synchronized (waiting) { waiting.remove(k); } }
                    try { if (c != null) c.close(); } catch (Exception ignored) { }
                    c = null;
                    synchronized (pending) { pending.clear(); draining = false; }
                    break;
                }
            }
        } finally {
            try { if (c != null) c.close(); } catch (Exception ignored) { }
        }
    }

    private void fail(String k) {
        missing.add(k);
        synchronized (waiting) { waiting.remove(k); }
    }

    private void deliver(String k, Bitmap b) {
        memory.put(k, b);
        final List<WeakReference<ImageView>> list;
        synchronized (waiting) { list = waiting.remove(k); }
        if (list == null) return;
        ui.post(() -> {
            for (WeakReference<ImageView> ref : list) {
                ImageView v = ref.get();
                if (v != null && k.equals(showing.get(v))) set(v, b);
            }
        });
    }

    private static void set(ImageView v, Bitmap b) {
        v.clearColorFilter();
        v.setImageBitmap(b);
    }

    /** Decodes Atiran's photo (JPEG / PNG / BMP / GIF) no larger than twice the card size, then scales it down. */
    private Bitmap shrink(byte[] bytes) {
        if (bytes == null || bytes.length < 100) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (b == null) return null;
            int side = Math.min(b.getWidth(), b.getHeight());
            if (side > targetPx) {
                float f = targetPx / (float) side;
                Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * f)), Math.max(1, Math.round(b.getHeight() * f)), true);
                if (s != b) b.recycle();
                b = s;
            }
            return b;
        } catch (Throwable t) { return null; }
    }

    private void save(String k, Bitmap b) {
        File f = new File(dir, k + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 88, out); }
        catch (Throwable ignored) { //noinspection ResultOfMethodCallIgnored
            f.delete(); }
    }

    private static Bitmap decodeFile(File f) {
        try { return BitmapFactory.decodeFile(f.getAbsolutePath()); } catch (Throwable t) { return null; }
    }
}
