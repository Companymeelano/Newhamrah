package ir.meelano.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Fresh position for the store attendance: listens to GPS and network for up to {@code timeoutMs},
 * keeps the most accurate fix and returns early once it is accurate enough (≤ 25 m).
 */
final class MeelanoGeo {
    private MeelanoGeo() { }

    interface Callback {
        /** location is null when no fix was received; error explains why. */
        void done(Location location, String error);
    }

    static float distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        float[] r = new float[1];
        Location.distanceBetween(lat1, lng1, lat2, lng2, r);
        return r[0];
    }

    static boolean enabled(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return false; }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    static void fresh(Context c, long timeoutMs, Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { cb.done(null, "سرویس موقعیت در این گوشی در دسترس نیست."); return; }
        final Location[] best = new Location[1];
        final boolean[] finished = new boolean[1];
        final LocationListener[] holder = new LocationListener[1];
        final Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            try { lm.removeUpdates(holder[0]); } catch (Exception ignored) { }
            if (best[0] == null) {
                // A very recent last-known fix (≤ 2 minutes) is still "here".
                Location last = null;
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null && System.currentTimeMillis() - l.getTime() < 120_000 && (last == null || l.getAccuracy() < last.getAccuracy())) last = l;
                    } catch (Exception ignored) { }
                }
                cb.done(last, last == null ? "موقعیت گوشی پیدا نشد. «موقعیت مکانی» را روشن کنید و کنار پنجره یا در فضای باز دوباره امتحان کنید." : null);
            } else cb.done(best[0], null);
        };
        holder[0] = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (l == null) return;
                if (best[0] == null || l.getAccuracy() < best[0].getAccuracy()) best[0] = l;
                if (best[0].getAccuracy() <= 25f) main.post(finish);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };
        boolean any = false;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(p)) { lm.requestLocationUpdates(p, 500L, 0f, holder[0], Looper.getMainLooper()); any = true; }
            } catch (Exception ignored) { }
        }
        if (!any) { finished[0] = true; cb.done(null, "«موقعیت مکانی» گوشی خاموش است؛ آن را روشن کنید."); return; }
        main.postDelayed(finish, timeoutMs);
    }
}
