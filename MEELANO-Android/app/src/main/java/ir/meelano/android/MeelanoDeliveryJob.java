package ir.meelano.android;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.sql.Connection;

/**
 * Staff app: every ~15 minutes (Android's shortest period for background work), even when the app is closed, brings new
 * store invoices into «تحویل بار» and posts phone notifications — new loads, a colleague handing a load over, a cancelled
 * invoice. Runs only for the person signed in last (cleared on sign-out). Reads Atiran only.
 */
public final class MeelanoDeliveryJob extends JobService {
    static final int JOB_ID = 58_001;
    private volatile Thread worker;

    static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, MeelanoDeliveryJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Exception e) { android.util.Log.w("MEELANO_DELIVERY", "schedule: " + e.getMessage()); }
    }

    static void cancel(Context ctx) {
        try { JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE); if (js != null) js.cancel(JOB_ID); } catch (Exception ignored) { }
    }

    @Override public boolean onStartJob(JobParameters params) {
        final SharedPreferences p = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        final String user = p.getString(MeelanoDelivery.PREF_USER, "");
        if (user == null || user.trim().isEmpty()) { cancel(this); return false; }
        worker = new Thread(() -> {
            try (Connection c = MainActivity.backgroundConnection(this)) {
                MeelanoDelivery.sync(c, MeelanoDelivery.today(c));
                JSONObject a = MeelanoDelivery.alerts(c, user.trim().toLowerCase(java.util.Locale.US), p.getLong(MeelanoDelivery.PREF_OPEN_MAX, 0), p.getLong(MeelanoDelivery.PREF_LOG_MAX, 0));
                p.edit().putLong(MeelanoDelivery.PREF_OPEN_MAX, a.optLong("openMax")).putLong(MeelanoDelivery.PREF_LOG_MAX, a.optLong("logMax")).apply();
                MeelanoDelivery.post(this, a.optJSONArray("messages"));
            } catch (Throwable e) {
                android.util.Log.w("MEELANO_DELIVERY", "job: " + e.getMessage());
            } finally {
                jobFinished(params, false);
            }
        }, "meelano-delivery-job");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Thread t = worker; if (t != null) t.interrupt();
        return true;
    }
}
