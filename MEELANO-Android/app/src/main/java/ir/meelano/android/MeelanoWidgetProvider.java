package ir.meelano.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

public class MeelanoWidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "meelano_android_direct_sql";
    private static final String KEY_WIDGET_SUMMARY = "widget_summary";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context, appWidgetManager, appWidgetIds);
    }

    public static void refresh(Context context) {
        if (context == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MeelanoWidgetProvider.class));
        updateAll(context, manager, ids);
    }

    private static void updateAll(Context context, AppWidgetManager manager, int[] ids) {
        if (context == null || manager == null || ids == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String summary = prefs.getString(KEY_WIDGET_SUMMARY, "برای به‌روزرسانی، اپ Meelano را باز کنید.");
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_meelano);
            views.setTextViewText(R.id.widget_title, "Meelano امروز");
            views.setTextViewText(R.id.widget_summary, summary);
            Intent intent = new Intent(context, MainActivity.class);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(context, 1818, intent, flags);
            views.setOnClickPendingIntent(R.id.widget_root, pi);
            manager.updateAppWidget(id, views);
        }
    }
}
