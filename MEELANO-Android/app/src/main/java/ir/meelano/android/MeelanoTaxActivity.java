package ir.meelano.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «پخش درخشان مودیان» — a separate app (flavour {@code tax}) that sends Atiran's sales invoices, returns and cancels
 * to the Iranian tax organisation's Moadian system (API v2).
 *
 * <p>Sections (bottom bar): پیشخوان · فاکتورهای فروش · برگشتی و ابطال · پیگیری ارسال‌ها · بیشتر (شناسه کالا، خریداران،
 * واحدها، استعلام بازه‌ای، راه‌اندازی اولیه، کلید و گواهی، پشتیبان کلید، اتصال پایگاه داده، راهنما).</p>
 */
public class MeelanoTaxActivity extends Activity {
    private static final String PREFS = "meelano_tax";
    private static final int REQ_OPEN_FILE = 7101;
    private static final int REQ_SAVE_TEXT = 7102;

    private SharedPreferences prefs;
    private MeelanoTaxUi ui;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private FrameLayout content;
    private LinearLayout navBar;
    private TextView topTitle;
    private TextView topSub;
    private LinearLayout topEnd;
    private ImageView topBack;
    private FrameLayout busyView;
    private TextView busyLabel;
    private int busyCount;
    private volatile boolean schemaReady;
    private int screenToken;

    private MeelanoTaxDb.User user;
    private Map<String, String> settings = new HashMap<>();
    private MeelanoTaxDb.Company company = new MeelanoTaxDb.Company();
    private PrivateKey privateKey;
    private X509Certificate certificate;
    private String tab = "home";
    private final ArrayDeque<Runnable> stack = new ArrayDeque<>();
    private Runnable current;
    private String pendingSaveText;
    private FileCallback pendingFile;
    private String networkNote = "";

    interface FileCallback { void got(byte[] data, String name); }

    interface DbJob<T> { T run(Connection c) throws Exception; }

    interface Job<T> { T run() throws Exception; }

    interface Ok<T> { void done(T value); }

    /** Result of the login step. */
    private static final class Session {
        MeelanoTaxDb.User user;
        Map<String, String> settings;
        MeelanoTaxDb.Company company;
        PrivateKey key;
        X509Certificate cert;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ui = new MeelanoTaxUi(this);
        try {
            getWindow().setStatusBarColor(MeelanoTaxUi.BG);
            getWindow().setNavigationBarColor(MeelanoTaxUi.SURFACE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        } catch (Exception ignored) { }
        root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(MeelanoTaxUi.BG);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                l = s.left; t = s.top; r = s.right; b = s.bottom;
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop(); r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return in;
        });
        buildBusy();
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                if (!goBack()) finish();
            });
        }
        showLogin();
    }

    @Override
    public void onBackPressed() {
        if (!goBack()) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ background work

    private <T> void db(String busy, DbJob<T> job, Ok<T> ok) {
        work(busy, () -> {
            try (Connection c = openDb()) {
                return job.run(c);
            }
        }, ok);
    }

    private <T> void work(String busy, Job<T> job, Ok<T> ok) {
        if (busy != null) showBusy(busy);
        worker.execute(() -> {
            T value = null;
            Throwable error = null;
            try {
                value = job.run();
            } catch (Throwable t) {
                error = t;
            }
            final T v = value;
            final Throwable e = error;
            main.post(() -> {
                if (busy != null) hideBusy();
                if (isFinishing() || isDestroyed()) return;
                if (e != null) showError(e);
                else if (ok != null) ok.done(v);
            });
        });
    }

    private void buildBusy() {
        busyView = new FrameLayout(this);
        busyView.setBackgroundColor(0xB3F2F9F8);
        busyView.setClickable(true);
        busyView.setVisibility(View.GONE);
        LinearLayout box = ui.h();
        box.setBackground(ui.round(MeelanoTaxUi.SURFACE, 18, MeelanoTaxUi.LINE, 1));
        box.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        box.setElevation(ui.dp(6));
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(MeelanoTaxUi.PRIMARY));
        box.addView(pb, MeelanoTaxUi.lp(ui.dp(34), ui.dp(34)));
        busyLabel = ui.text("", 14, MeelanoTaxUi.TEXT, true);
        busyLabel.setPadding(ui.dp(14), 0, 0, 0);
        box.addView(busyLabel);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        p.leftMargin = p.rightMargin = ui.dp(28);
        busyView.addView(box, p);
    }

    private void attachBusy() {
        if (busyView.getParent() != null) ((ViewGroup) busyView.getParent()).removeView(busyView);
        root.addView(busyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showBusy(String text) {
        busyCount++;
        busyLabel.setText(text);
        busyView.setVisibility(View.VISIBLE);
        busyView.bringToFront();
    }

    private void hideBusy() {
        busyCount = Math.max(0, busyCount - 1);
        if (busyCount == 0) busyView.setVisibility(View.GONE);
    }

    private void showError(Throwable e) {
        String msg;
        if (e instanceof MeelanoTaxApi.ApiException) {
            MeelanoTaxApi.ApiException a = (MeelanoTaxApi.ApiException) e;
            msg = a.persian();
            if (a.network) msg += "\n\nنکته: سامانه مودیان فقط از اینترنت داخل ایران در دسترس است؛ اگر VPN روشن است آن را خاموش کنید یا گزینه «عبور از VPN» را در اتصال پایگاه داده روشن بگذارید.";
        } else if (e instanceof SQLException) {
            msg = "ارتباط با پایگاه داده آتیران برقرار نشد یا دستور پایگاه داده خطا داد.\n" + nz(e.getMessage())
                    + (networkNote.isEmpty() ? "" : "\n" + networkNote) + "\n\nتنظیمات را در «بیشتر ← اتصال پایگاه داده» بررسی کنید.";
        } else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            msg = nz(e.getMessage());
        } else if (e instanceof OutOfMemoryError) {
            msg = "حافظه گوشی کافی نیست؛ بازه تاریخ را کوتاه‌تر کنید.";
        } else {
            msg = (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        ui.message("خطا", msg);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ database connection

    private void bindDirectNetwork() {
        networkNote = "";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            if (!vpn) { cm.bindProcessToNetwork(null); return; }
            Network best = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                boolean internet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                boolean isVpn = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                boolean wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                if (internet && !isVpn && (wifi || best == null)) { best = n; if (wifi) break; }
            }
            if (best != null && cm.bindProcessToNetwork(best)) networkNote = "VPN روشن بود و برنامه ارتباط را از شبکه مستقیم گوشی برقرار کرد.";
            else networkNote = "VPN روشن است و مسیر مستقیم پیدا نشد؛ برای ارسال به سامانه مودیان VPN را خاموش کنید.";
        } catch (Exception ignored) { }
    }

    private String[] dbConfig() {
        String[] d = MainActivity.defaultDbConfig();
        String host = prefs.getString("db_host", "").trim();
        String port = prefs.getString("db_port", "").trim();
        String name = prefs.getString("db_name", "").trim();
        String u = prefs.getString("db_user", "").trim();
        String pass = MeelanoTaxVault.get(prefs, "db_pass");
        boolean customUser = !u.isEmpty();
        return new String[]{host.isEmpty() ? d[0] : host, port.isEmpty() ? d[1] : port, name.isEmpty() ? d[2] : name,
                customUser ? u : d[3], customUser ? (pass == null ? "" : pass) : d[4]};
    }

    private Connection openDb() throws Exception {
        if (!"0".equals(prefs.getString("vpn_bypass", "1"))) bindDirectNetwork();
        Class.forName("net.sourceforge.jtds.jdbc.Driver");
        String[] d = dbConfig();
        String url = "jdbc:jtds:sqlserver://" + d[0] + ":" + d[1] + "/" + d[2] + ";loginTimeout=12;socketTimeout=120;appName=MEELANOTax;";
        Properties p = new Properties();
        p.setProperty("user", d[3]);
        p.setProperty("password", d[4]);
        p.setProperty("charset", "UTF-8");
        p.setProperty("sendStringParametersAsUnicode", "true");
        Connection c = DriverManager.getConnection(url, p);
        if (!schemaReady) {
            MeelanoTaxDb.ensureSchema(c);
            schemaReady = true;
        }
        return c;
    }

    // ------------------------------------------------------------------ configuration

    private MeelanoTaxEngine.Config cfg() {
        MeelanoTaxEngine.Config c = MeelanoTaxEngine.Config.from(settings);
        c.key = privateKey;
        c.certDer = certDer();
        c.userName = user == null ? "" : user.name;
        c.atiranUserId = user == null ? 0 : user.id;
        return c;
    }

    private byte[] certDer() {
        try { return certificate == null ? null : certificate.getEncoded(); } catch (Exception e) { return null; }
    }

    private String setting(String k) { return nz(settings.get(k)).trim(); }

    private boolean sandbox() { return !"PRODUCTION".equals(settings.get("env")); }

    private List<String> missingSetup() {
        List<String> out = new ArrayList<>();
        MeelanoTaxEngine.Config c = cfg();
        if (!c.memoryId.matches("[A-Z0-9]{6}")) out.add("شناسه یکتای حافظه مالیاتی");
        if (!(c.tins.length() == 11 || c.tins.length() == 14)) out.add("شماره اقتصادی فروشنده");
        if (privateKey == null) out.add("کلید خصوصی");
        if (certificate == null) out.add("گواهی امضای الکترونیکی");
        return out;
    }

    private MeelanoTaxApi api() {
        List<String> miss = missingSetup();
        if (!miss.isEmpty()) throw new IllegalStateException("راه‌اندازی اولیه کامل نیست. موارد ناقص: " + String.join("، ", miss) + ".\nاز «بیشتر ← راه‌اندازی اولیه» تکمیل کنید.");
        MeelanoTaxEngine.Config c = cfg();
        return new MeelanoTaxApi(c.sandbox, c.memoryId, c.key, c.certDer);
    }

    private static PrivateKey loadKey(SharedPreferences prefs) {
        String pem = MeelanoTaxVault.get(prefs, "private_key_pem");
        if (pem == null) return null;
        try { return MeelanoTaxCrypto.privateKeyFromPem(pem); } catch (Exception e) { return null; }
    }

    private X509Certificate loadCert(Map<String, String> s) {
        String pem = s.get("cert_pem");
        if (pem == null || pem.trim().isEmpty()) pem = prefs.getString("cert_pem", null);
        if (pem == null || pem.trim().isEmpty()) return null;
        try { return MeelanoTaxCrypto.certificateFromText(pem); } catch (Exception e) { return null; }
    }

    private void storeKey(PrivateKey k) throws Exception {
        MeelanoTaxVault.put(prefs, "private_key_pem", MeelanoTaxCrypto.pem("PRIVATE KEY", k.getEncoded()));
        privateKey = k;
    }

    /** Saves the certificate on the phone and (it is public) in the database, so other phones get it too. */
    private void storeCert(Connection c, X509Certificate cert) throws Exception {
        String pem = MeelanoTaxCrypto.pem("CERTIFICATE", cert.getEncoded());
        prefs.edit().putString("cert_pem", pem).apply();
        MeelanoTaxDb.putSetting(c, "cert_pem", pem, userName());
        settings.put("cert_pem", pem);
        certificate = cert;
    }

    private String userName() { return user == null ? "" : user.name; }

    private void putSettings(Map<String, String> values, Runnable after) {
        db("در حال ذخیره…", c -> {
            for (Map.Entry<String, String> e : values.entrySet()) MeelanoTaxDb.putSetting(c, e.getKey(), e.getValue(), userName());
            return MeelanoTaxDb.settings(c);
        }, s -> {
            settings = s;
            toast("ذخیره شد");
            if (after != null) after.run();
        });
    }

    // ------------------------------------------------------------------ navigation & shell

    private void setRoot(View v) {
        root.removeAllViews();
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        attachBusy();
    }

    private boolean goBack() {
        if (user == null) {
            if (current != null && stack.isEmpty() && !isLoginScreen) { showLogin(); return true; }
            return false;
        }
        if (!stack.isEmpty()) {
            current = stack.pop();
            screenToken++;
            current.run();
            return true;
        }
        if (!"home".equals(tab)) { selectTab("home"); return true; }
        return false;
    }

    private boolean isLoginScreen;

    private void push(Runnable screen) {
        if (current != null) stack.push(current);
        current = screen;
        screenToken++;
        screen.run();
    }

    private void refresh() {
        if (current != null) { screenToken++; current.run(); }
    }

    private void selectTab(String t) {
        tab = t;
        stack.clear();
        refreshNav();
        Runnable r;
        switch (t) {
            case "sales": r = this::renderSales; break;
            case "backs": r = this::renderBacks; break;
            case "logs": r = this::renderLogs; break;
            case "more": r = this::renderMore; break;
            default: r = this::renderHome; break;
        }
        current = r;
        screenToken++;
        r.run();
    }

    private void showShell() {
        isLoginScreen = false;
        LinearLayout shell = ui.v();
        shell.setBackgroundColor(MeelanoTaxUi.BG);

        LinearLayout top = ui.h();
        top.setPadding(ui.dp(12), ui.dp(10), ui.dp(14), ui.dp(8));
        topBack = ui.icon(R.drawable.mi_arrow_forward, MeelanoTaxUi.PRIMARY_DARK, 26);
        topBack.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        topBack.setContentDescription("بازگشت");
        topBack.setOnClickListener(v -> goBack());
        LinearLayout.LayoutParams bp = MeelanoTaxUi.lp(ui.dp(38), ui.dp(38));
        bp.setMarginEnd(ui.dp(6));
        top.addView(topBack, bp);
        LinearLayout titles = ui.v();
        topTitle = ui.text("", 18, MeelanoTaxUi.TEXT, true);
        topTitle.setSingleLine(true);
        topSub = ui.caption("");
        topSub.setSingleLine(true);
        titles.addView(topTitle);
        titles.addView(topSub);
        top.addView(titles, MeelanoTaxUi.weight(1));
        topEnd = ui.h();
        top.addView(topEnd);
        shell.addView(top);

        content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navBar = ui.h();
        navBar.setBackgroundColor(MeelanoTaxUi.SURFACE);
        navBar.setElevation(ui.dp(8));
        navBar.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
        shell.addView(navBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setRoot(shell);
        selectTab("home");
    }

    private void refreshNav() {
        if (navBar == null) return;
        navBar.removeAllViews();
        String[][] items = {{"home", "پیشخوان"}, {"sales", "فروش"}, {"backs", "برگشتی"}, {"logs", "پیگیری"}, {"more", "بیشتر"}};
        int[] icons = {R.drawable.mi_home, R.drawable.mi_receipt_long, R.drawable.mi_assignment_return, R.drawable.mi_history, R.drawable.mi_apps};
        for (int i = 0; i < items.length; i++) {
            final String key = items[i][0];
            boolean sel = key.equals(tab);
            LinearLayout it = ui.v();
            it.setGravity(Gravity.CENTER);
            it.setPadding(0, ui.dp(4), 0, ui.dp(4));
            LinearLayout pill = ui.h();
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(ui.round(sel ? MeelanoTaxUi.PRIMARY_SOFT : Color.TRANSPARENT, 16, 0, 0));
            pill.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
            pill.addView(ui.icon(icons[i], sel ? MeelanoTaxUi.PRIMARY : MeelanoTaxUi.MUTED, 22));
            it.addView(pill);
            TextView l = ui.text(items[i][1], 11.5f, sel ? MeelanoTaxUi.PRIMARY_DARK : MeelanoTaxUi.MUTED, sel);
            l.setGravity(Gravity.CENTER);
            it.addView(l);
            it.setContentDescription(items[i][1]);
            it.setOnClickListener(v -> selectTab(key));
            navBar.addView(it, MeelanoTaxUi.weight(1));
        }
    }

    /** Replaces the page content. */
    private void setScreen(String title, String sub, View body, View footer) {
        if (content == null) return;
        topTitle.setText(title);
        topSub.setText(sub == null ? "" : sub);
        topSub.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        topBack.setVisibility(stack.isEmpty() && "home".equals(tab) ? View.GONE : View.VISIBLE);
        topEnd.removeAllViews();
        if (user != null) topEnd.addView(ui.badge(sandbox() ? "آزمایشی" : "اصلی", sandbox() ? "gold" : "ok"));
        content.removeAllViews();
        LinearLayout wrap = ui.v();
        wrap.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (footer != null) {
            footer.setBackgroundColor(MeelanoTaxUi.SURFACE);
            footer.setElevation(ui.dp(6));
            wrap.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(wrap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View loading(String text) {
        LinearLayout l = ui.v();
        l.setGravity(Gravity.CENTER);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(MeelanoTaxUi.PRIMARY));
        l.addView(pb, MeelanoTaxUi.lp(ui.dp(40), ui.dp(40)));
        TextView t = ui.caption(text);
        t.setPadding(0, ui.dp(10), 0, 0);
        l.addView(t);
        return l;
    }

    /** Loads data in the background for the current screen and renders it if the user is still there. */
    private <T> void load(String title, String sub, DbJob<T> job, Ok<T> render) {
        final int token = screenToken;
        setScreen(title, sub, loading("در حال دریافت اطلاعات از آتیران…"), null);
        work(null, () -> {
            try (Connection c = openDb()) { return job.run(c); }
        }, v -> {
            if (token == screenToken) render.done(v);
        });
    }

    // ------------------------------------------------------------------ login

    private void showLogin() {
        isLoginScreen = true;
        user = null;
        current = null;
        stack.clear();
        content = null;
        navBar = null;
        LinearLayout col = ui.page();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(ui.dp(22), ui.dp(36), ui.dp(22), ui.dp(24));

        FrameLayout logoBox = new FrameLayout(this);
        logoBox.setBackground(ui.round(MeelanoTaxUi.SURFACE, 30, MeelanoTaxUi.LINE, 1));
        logoBox.setElevation(ui.dp(4));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logoBox.addView(logo, new FrameLayout.LayoutParams(ui.dp(84), ui.dp(84), Gravity.CENTER));
        col.addView(logoBox, MeelanoTaxUi.lp(ui.dp(112), ui.dp(112)));
        TextView name = ui.text(getString(R.string.app_name), 23, MeelanoTaxUi.PRIMARY_DARK, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, ui.dp(16), 0, ui.dp(2));
        col.addView(name, MeelanoTaxUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = ui.caption("ارسال صورتحساب‌های آتیران به سامانه مودیان — دقیق، امن و مرحله‌به‌مرحله");
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, MeelanoTaxUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(ui.space(22));

        LinearLayout card = ui.card();
        card.addView(ui.heading("ورود با کاربر آتیران"));
        EditText u = ui.input("نام کاربری", prefs.getString("last_user", ""), InputType.TYPE_CLASS_TEXT);
        EditText p = ui.input("رمز عبور", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(ui.field("نام کاربری", u, null));
        card.addView(ui.field("رمز عبور", p, null));
        card.addView(ui.button("ورود", R.drawable.mi_login, MeelanoTaxUi.BTN_PRIMARY, v -> doLogin(u.getText().toString(), p.getText().toString())));
        col.addView(card);
        col.addView(ui.button("اتصال پایگاه داده آتیران", R.drawable.mi_database, MeelanoTaxUi.BTN_GHOST, v -> {
            current = this::showLogin;
            renderDbSettingsStandalone();
        }));
        TextView dev = ui.caption("نسخه " + versionName() + " • توسعه: Milad Yaghoobi");
        dev.setGravity(Gravity.CENTER);
        dev.setPadding(0, ui.dp(18), 0, 0);
        col.addView(dev, MeelanoTaxUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setRoot(ui.scroll(col));
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : MeelanoTaxUi.fa(v.split("-")[0]);
        } catch (Exception e) {
            return "";
        }
    }

    private static String normUser(String s) {
        return MeelanoTaxDb.norm(s == null ? "" : s).trim().toLowerCase(Locale.US);
    }

    private void doLogin(String name, String pass) {
        if (name.trim().isEmpty() || pass.isEmpty()) { ui.message("ورود", "نام کاربری و رمز عبور آتیران را وارد کنید."); return; }
        db("در حال ورود…", c -> {
            MeelanoTaxDb.User u = MeelanoTaxDb.login(c, name, pass);
            if (u == null) throw new IllegalArgumentException("نام کاربری یا رمز عبور آتیران درست نیست.");
            Session s = new Session();
            s.user = u;
            s.settings = MeelanoTaxDb.settings(c);
            String allowed = nz(s.settings.get("allowed_users")).trim();
            if (!allowed.isEmpty()) {
                boolean ok = false;
                for (String a : allowed.split(",")) if (normUser(a).equals(normUser(u.name))) ok = true;
                if (!ok) throw new IllegalArgumentException("کاربر «" + u.name + "» اجازه کار با برنامه مودیان را ندارد.\nمدیر می‌تواند در «راه‌اندازی اولیه ← کاربران مجاز» دسترسی بدهد.");
            }
            s.company = MeelanoTaxDb.company(c);
            // First run: take the taxpayer data Atiran already has.
            Map<String, String> defaults = new LinkedHashMap<>();
            if (empty(s.settings.get("memory_id")) && s.company.memoryId != null) defaults.put("memory_id", s.company.memoryId.trim().toUpperCase(Locale.US));
            if (empty(s.settings.get("economic_code")) && s.company.economicCode != null) defaults.put("economic_code", MeelanoTaxInvoice.digits(s.company.economicCode));
            if (empty(s.settings.get("national_id")) && s.company.nationalId != null) defaults.put("national_id", MeelanoTaxInvoice.digits(s.company.nationalId));
            if (empty(s.settings.get("env"))) defaults.put("env", "SANDBOX");
            if (empty(s.settings.get("deadline_days"))) defaults.put("deadline_days", "12");
            for (Map.Entry<String, String> e : defaults.entrySet()) {
                if (e.getValue() == null) continue;
                MeelanoTaxDb.putSetting(c, e.getKey(), e.getValue(), u.name);
                s.settings.put(e.getKey(), e.getValue());
            }
            s.key = loadKey(prefs);
            s.cert = loadCert(s.settings);
            return s;
        }, s -> {
            user = s.user;
            settings = s.settings;
            company = s.company;
            privateKey = s.key;
            certificate = s.cert;
            prefs.edit().putString("last_user", name.trim()).apply();
            showShell();
            if (!"1".equals(settings.get("setup_done"))) push(this::renderSetup);
        });
    }

    private void logout() {
        ui.confirm("خروج", "از حساب کاربری خارج می‌شوید.", "خروج", this::showLogin);
    }

    // ------------------------------------------------------------------ helpers

    static String nz(String s) { return s == null ? "" : s; }

    static boolean empty(String s) { return s == null || s.trim().isEmpty(); }

    static String fa(long n) { return MeelanoTaxUi.fa(String.valueOf(n)); }

    /** Tehran date/time of epoch millis, Jalali. */
    static String jdate(long millis) {
        if (millis <= 0) return "—";
        long local = millis + 12_600_000L;
        int jdn = (int) (Math.floorDiv(local, 86_400_000L) + 2440588L);
        long sec = Math.floorMod(local, 86_400_000L) / 1000;
        return MeelanoTaxUi.fa(MeelanoJalali.format(jdn) + " " + String.format(Locale.US, "%02d:%02d", sec / 3600, (sec / 60) % 60));
    }

    static String today() { return MeelanoJalali.format(MeelanoJalali.today()); }

    static String daysAgo(int n) { return MeelanoJalali.format(MeelanoJalali.today() - n); }

    /** Start of the current Jalali month as an ISO (Gregorian) date for SQL comparisons. */
    static String monthStartIso() {
        int[] j = MeelanoJalali.fromDay(MeelanoJalali.today());
        int start = MeelanoJalali.toDay(j[0], j[1], 1);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date((start - 2440588L) * 86_400_000L));
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        toast("کپی شد");
    }

    private void shareText(String title, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, title);
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, title));
    }

    private void saveTextFile(String fileName, String text) {
        pendingSaveText = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, fileName);
        try { startActivityForResult(i, REQ_SAVE_TEXT); } catch (Exception e) { ui.message("ذخیره فایل", "برنامه مدیریت فایل روی گوشی پیدا نشد."); }
    }

    private void pickFile(FileCallback cb) {
        pendingFile = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_OPEN_FILE); } catch (Exception e) { ui.message("انتخاب فایل", "برنامه مدیریت فایل روی گوشی پیدا نشد."); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_SAVE_TEXT && pendingSaveText != null) {
            String text = pendingSaveText;
            pendingSaveText = null;
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                if (o == null) throw new IllegalStateException("فایل باز نشد");
                o.write(text.getBytes(StandardCharsets.UTF_8));
                toast("فایل ذخیره شد");
            } catch (Exception e) {
                showError(e);
            }
        } else if (requestCode == REQ_OPEN_FILE && pendingFile != null) {
            FileCallback cb = pendingFile;
            pendingFile = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("فایل باز نشد");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 4 * 1024 * 1024) throw new IllegalArgumentException("فایل بیش از حد بزرگ است.");
                }
                String name = "";
                try (Cursor cur = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) name = nz(cur.getString(0));
                } catch (Exception ignored) { }
                cb.got(b.toByteArray(), name);
            } catch (Exception e) {
                showError(e);
            }
        }
    }

    /** A rounded list card (tap target) with a title row, a subtitle and an optional trailing badge. */
    private LinearLayout listCard(String title, String sub, View badge, View.OnClickListener click) {
        LinearLayout c = ui.v();
        c.setBackground(ui.pressable(ui.round(MeelanoTaxUi.SURFACE, 16, MeelanoTaxUi.LINE, 1), 0x220B6A70));
        c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        LinearLayout top = ui.h();
        TextView t = ui.text(title, 14.5f, MeelanoTaxUi.TEXT, true);
        top.addView(t, MeelanoTaxUi.weight(1));
        if (badge != null) top.addView(badge);
        c.addView(top);
        if (sub != null && !sub.isEmpty()) {
            TextView s = ui.caption(sub);
            s.setPadding(0, ui.dp(3), 0, 0);
            c.addView(s);
        }
        if (click != null) { c.setClickable(true); c.setOnClickListener(click); }
        c.setLayoutParams(ui.full(0, 8));
        return c;
    }

    private String statusFa(String s) {
        if (s == null) return "نامشخص";
        switch (s) {
            case "SENDING": return "در حال ارسال";
            case "PENDING": return "ارسال شد، منتظر نتیجه";
            case "IN_PROGRESS": return "در صف بررسی سامانه";
            case "UNKNOWN": return "نامشخص — نیاز به استعلام";
            case "SUCCESS": return "ثبت شد";
            case "FAILED": return "رد شد";
            case "TIMEOUT": return "پایان مهلت پردازش";
            case "NOT_FOUND": return "به سامانه نرسید";
            case "LOCAL_ERROR": return "خطا پیش از ارسال";
            case "EXTERNAL": return "ثبت دستی (خارج از برنامه)";
            case "VOID": return "کنار گذاشته شد";
            default: return MeelanoTaxApi.statusTitle(s);
        }
    }

    private static String statusTone(String s) {
        if (s == null) return "idle";
        switch (s) {
            case "SUCCESS": case "EXTERNAL": return "ok";
            case "SENDING": case "PENDING": case "IN_PROGRESS": case "UNKNOWN": return "wait";
            case "FAILED": case "LOCAL_ERROR": case "TIMEOUT": case "NOT_FOUND": return "err";
            case "VOID": return "off";
            default: return "idle";
        }
    }

    private static String kartableTone(String k) {
        if (k == null) return "idle";
        if (k.startsWith("APPROVED") || k.startsWith("SYSTEMIC_APPROVED") || k.startsWith("NO_NEED")) return "ok";
        if (k.startsWith("REJECTED") || k.startsWith("CANCELED")) return "err";
        if (k.startsWith("AWAITING")) return "wait";
        return "idle";
    }

    // ================================================================== پیشخوان (dashboard)

    private static final class Dash {
        Map<String, Integer> counts = new HashMap<>();
        long[] month = {0, 0, 0};
        int unsent, near, late, action, failed, pending, sales;
        long unsentAmount;
        int goodsMissing, buyersMissing;
        long lastSerial;
    }

    private void renderHome() {
        String co = company.name == null ? "آتیران" : company.name;
        load("پیشخوان مودیان", co, c -> {
            Dash d = new Dash();
            MeelanoTaxEngine.Config cf = cfg();
            long now = System.currentTimeMillis();
            List<MeelanoTaxDb.Doc> docs = MeelanoTaxDb.sales(c, daysAgo(45), today(), "", 3000);
            d.sales = docs.size();
            Set<Long> weakBuyers = new LinkedHashSet<>();
            for (MeelanoTaxDb.Doc doc : docs) {
                String[] st = MeelanoTaxEngine.state(doc, cf.deadlineDays, now);
                String cat = category(st);
                if ("READY".equals(cat)) {
                    d.unsent++;
                    d.unsentAmount += doc.total;
                    if (st[0].contains("خارج از مهلت")) d.late++;
                    else if (st[0].contains("نزدیک مهلت")) d.near++;
                    boolean ids = MeelanoTaxInvoice.digits(doc.tinb) != null || (MeelanoTaxInvoice.digits(doc.bid) != null && MeelanoTaxInvoice.digits(doc.bpc) != null);
                    if (!ids && doc.custInty != null && doc.custInty == 1) weakBuyers.add(doc.shmo);
                }
                if ("ACTION".equals(cat)) d.action++;
            }
            d.buyersMissing = weakBuyers.size();
            d.counts = MeelanoTaxDb.statusCounts(c);
            d.month = MeelanoTaxDb.acceptedTotals(c, monthStartIso());
            for (MeelanoTaxDb.Good g : MeelanoTaxDb.goods(c, "", true, 2500)) if (g.soldLines > 0) d.goodsMissing++;
            d.lastSerial = MeelanoTaxDb.lastSerial(c, cf.memoryId);
            for (String k : new String[]{"PENDING", "SENDING", "IN_PROGRESS", "UNKNOWN"}) d.pending += d.counts.getOrDefault(k, 0);
            for (String k : new String[]{"FAILED", "LOCAL_ERROR", "TIMEOUT", "NOT_FOUND"}) d.failed += d.counts.getOrDefault(k, 0);
            return d;
        }, d -> setScreen("پیشخوان مودیان", co, ui.scroll(homeView(d)), null));
    }

    private View homeView(Dash d) {
        LinearLayout page = ui.page();

        // Hero
        LinearLayout hero = ui.v();
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{MeelanoTaxUi.PRIMARY, MeelanoTaxUi.PRIMARY_DARK});
        g.setCornerRadius(ui.dp(22));
        hero.setBackground(g);
        hero.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        hero.setElevation(ui.dp(3));
        LinearLayout hr = ui.h();
        hr.addView(ui.icon(R.drawable.mi_verified_user, 0xFFF3DDB0, 28));
        TextView ht = ui.text(company.name == null ? "مودی" : company.name, 17, Color.WHITE, true);
        ht.setPadding(ui.dp(8), 0, 0, 0);
        hr.addView(ht, MeelanoTaxUi.weight(1));
        hero.addView(hr);
        TextView hs = ui.text("شناسه حافظه: " + (empty(setting("memory_id")) ? "تنظیم نشده" : setting("memory_id")) + "   •   کاربر: " + userName(), 12.5f, 0xFFD7EEEC, false);
        hs.setPadding(0, ui.dp(6), 0, 0);
        hero.addView(hs);
        TextView he = ui.text(sandbox() ? "محیط آزمایشی — ارسال‌ها رسمی نیستند" : "محیط اصلی — ارسال‌ها رسمی و قطعی هستند", 12.5f, sandbox() ? 0xFFF3DDB0 : Color.WHITE, true);
        he.setPadding(0, ui.dp(4), 0, 0);
        hero.addView(he);
        hero.setLayoutParams(ui.full(0, 14));
        page.addView(hero);

        // Readiness
        List<String> miss = missingSetup();
        if (!miss.isEmpty() || !"1".equals(settings.get("setup_done"))) {
            LinearLayout rc = ui.softCard(MeelanoTaxUi.GOLD_SOFT, 0xFFEBD9B5);
            rc.addView(ui.heading("راه‌اندازی اولیه"));
            rc.addView(ui.checkLine("شناسه یکتای حافظه", setting("memory_id"), setting("memory_id").matches("[A-Z0-9]{6}"), false));
            rc.addView(ui.checkLine("شماره اقتصادی فروشنده", MeelanoTaxUi.fa(setting("economic_code")), cfg().tins.length() == 14 || cfg().tins.length() == 11, false));
            rc.addView(ui.checkLine("کلید خصوصی روی این گوشی", privateKey == null ? "ساخته یا وارد نشده" : "آماده", privateKey != null, false));
            rc.addView(ui.checkLine("گواهی امضای الکترونیکی", certificate == null ? "وارد نشده" : "معتبر تا " + certUntil(), certificate != null, false));
            rc.addView(ui.button("تکمیل راه‌اندازی", R.drawable.mi_tune, MeelanoTaxUi.BTN_GOLD, v -> push(this::renderSetup)));
            page.addView(rc);
        }

        // Stats
        page.addView(statGrid(
                ui.stat("آماده ارسال (۴۵ روز)", fa(d.unsent), R.drawable.mi_send, d.unsent > 0 ? "primary" : "ok", v -> { salesFilter = "READY"; selectTab("sales"); }),
                ui.stat("در انتظار نتیجه", fa(d.pending), R.drawable.mi_hourglass_top, d.pending > 0 ? "wait" : "ok", v -> { logFilter = "OPEN"; selectTab("logs"); })));
        page.addView(statGrid(
                ui.stat("رد شده / ناموفق", fa(d.failed), R.drawable.mi_error, d.failed > 0 ? "err" : "ok", v -> { logFilter = "FAILED"; selectTab("logs"); }),
                ui.stat("ثبت‌شده این ماه", fa(d.month[0]), R.drawable.mi_task_alt, "ok", v -> { logFilter = "SUCCESS"; selectTab("logs"); })));

        LinearLayout mc = ui.card();
        mc.addView(ui.heading("خلاصه ماه جاری (ثبت‌شده در سامانه)"));
        mc.addView(ui.row("مبلغ صورتحساب‌ها", MeelanoTaxUi.rial(d.month[1])));
        mc.addView(ui.row("مالیات بر ارزش افزوده", MeelanoTaxUi.rial(d.month[2])));
        mc.addView(ui.row("مبلغ فاکتورهای ارسال‌نشده (۴۵ روز)", MeelanoTaxUi.rial(d.unsentAmount)));
        mc.addView(ui.row("آخرین سریال مصرف‌شده", d.lastSerial > 0 ? fa(d.lastSerial) : "—"));
        page.addView(mc);

        if (d.late > 0 || d.near > 0) {
            LinearLayout w = ui.softCard(MeelanoTaxUi.WARN_SOFT, 0xFFF0D9AE);
            w.addView(ui.issue((d.late > 0 ? fa(d.late) + " فاکتور از مهلت " + fa(cfg().deadlineDays) + " روزه ارسال گذشته است (با «قاعده ارسال = ۱» فرستاده می‌شود). " : "")
                    + (d.near > 0 ? fa(d.near) + " فاکتور به پایان مهلت نزدیک است." : ""), false));
            w.addView(ui.button("مشاهده و ارسال", R.drawable.mi_send, MeelanoTaxUi.BTN_SECONDARY, v -> { salesFilter = "READY"; selectTab("sales"); }));
            page.addView(w);
        }
        if (d.action > 0) {
            LinearLayout w = ui.softCard(MeelanoTaxUi.WARN_SOFT, 0xFFF0D9AE);
            w.addView(ui.issue(fa(d.action) + " فاکتور بعد از ثبت در سامانه در آتیران ویرایش یا باطل شده و به صورتحساب اصلاحی یا ابطالی نیاز دارد.", false));
            w.addView(ui.button("مشاهده", R.drawable.mi_edit, MeelanoTaxUi.BTN_SECONDARY, v -> { salesFilter = "ACTION"; selectTab("sales"); }));
            page.addView(w);
        }
        if (d.goodsMissing > 0 || d.buyersMissing > 0) {
            LinearLayout q = ui.card();
            q.addView(ui.heading("کیفیت اطلاعات پایه"));
            if (d.goodsMissing > 0) {
                q.addView(ui.issue(fa(d.goodsMissing) + " کالای فروخته‌شده شناسه ۱۳ رقمی کالا ندارد؛ بدون آن صورتحساب پذیرفته نمی‌شود.", true));
                q.addView(ui.button("تکمیل شناسه کالاها", R.drawable.mi_inventory_2, MeelanoTaxUi.BTN_SECONDARY, v -> { goodsMissingOnly = true; push(this::renderGoods); }));
            }
            if (d.buyersMissing > 0) {
                q.addView(ui.issue(fa(d.buyersMissing) + " خریدار «نوع اول» کد ملی/کد پستی یا شماره اقتصادی ندارد.", false));
                q.addView(ui.button("تکمیل مشخصات خریداران", R.drawable.mi_group, MeelanoTaxUi.BTN_SECONDARY, v -> push(this::renderBuyers)));
            }
            page.addView(q);
        }

        LinearLayout qa = ui.card();
        qa.addView(ui.heading("کارهای سریع"));
        qa.addView(ui.button("استعلام نتیجه همه ارسال‌های در جریان", R.drawable.mi_sync, MeelanoTaxUi.BTN_PRIMARY, v -> inquireOpen(false)));
        qa.addView(ui.button("به‌روزرسانی وضعیت کارپوشه خریداران", R.drawable.mi_fact_check, MeelanoTaxUi.BTN_SECONDARY, v -> refreshKartable()));
        qa.addView(ui.button("آزمون اتصال به سامانه مودیان", R.drawable.mi_verified_user, MeelanoTaxUi.BTN_GHOST, v -> runSystemTest(null)));
        page.addView(qa);
        return page;
    }

    private LinearLayout statGrid(View a, View b) {
        LinearLayout r = ui.h();
        LinearLayout.LayoutParams pa = MeelanoTaxUi.weight(1);
        pa.setMarginEnd(ui.dp(6));
        LinearLayout.LayoutParams pb = MeelanoTaxUi.weight(1);
        pb.setMarginStart(ui.dp(6));
        r.addView(a, pa);
        r.addView(b, pb);
        r.setLayoutParams(ui.full(0, 12));
        return r;
    }

    private String certUntil() {
        if (certificate == null) return "—";
        return jdate(certificate.getNotAfter().getTime()).split(" ")[0];
    }

    /** List filter category of a document state. */
    static String category(String[] st) {
        switch (st[1]) {
            case "wait": return "WAIT";
            case "err": return "ERR";
            case "ok": return "OK";
            case "off": return "OFF";
            case "warn": return st[0].contains("نیاز به") ? "ACTION" : "READY";
            default: return "READY";
        }
    }

    // ================================================================== فاکتورهای فروش

    private String salesFrom = daysAgo(30), salesTo = today(), salesQuery = "", salesFilter = "ALL";
    private final Set<Integer> salesPicked = new LinkedHashSet<>();
    private int salesShown = 60;

    private void renderSales() {
        load("فاکتورهای فروش", "از " + MeelanoTaxUi.fa(salesFrom) + " تا " + MeelanoTaxUi.fa(salesTo), c -> MeelanoTaxDb.sales(c, salesFrom, salesTo, salesQuery, 3000),
                list -> docListScreen(list, false));
    }

    private String backsFrom = daysAgo(60), backsTo = today(), backsQuery = "", backsFilter = "ALL";
    private final Set<String> backsPicked = new LinkedHashSet<>();
    private int backsShown = 60;

    private void renderBacks() {
        load("برگشتی و ابطال", "از " + MeelanoTaxUi.fa(backsFrom) + " تا " + MeelanoTaxUi.fa(backsTo), c -> MeelanoTaxDb.backs(c, backsFrom, backsTo, backsQuery, 3000),
                list -> docListScreen(list, true));
    }

    private static String backKey(MeelanoTaxDb.Doc d) { return d.backKind + ":" + d.no; }

    private void docListScreen(List<MeelanoTaxDb.Doc> all, boolean backs) {
        long now = System.currentTimeMillis();
        int deadline = cfg().deadlineDays;
        String filter = backs ? backsFilter : salesFilter;
        Map<String, Integer> counts = new HashMap<>();
        List<MeelanoTaxDb.Doc> shown = new ArrayList<>();
        List<String[]> states = new ArrayList<>();
        for (MeelanoTaxDb.Doc d : all) {
            String[] st = MeelanoTaxEngine.state(d, deadline, now);
            String cat = category(st);
            counts.put(cat, counts.getOrDefault(cat, 0) + 1);
            if ("ALL".equals(filter) || filter.equals(cat)) { shown.add(d); states.add(st); }
        }
        LinearLayout page = ui.page();

        // Filters
        LinearLayout fc = ui.card();
        LinearLayout dates = ui.h();
        EditText from = ui.ltrInput("از تاریخ", backs ? backsFrom : salesFrom, InputType.TYPE_CLASS_DATETIME);
        EditText to = ui.ltrInput("تا تاریخ", backs ? backsTo : salesTo, InputType.TYPE_CLASS_DATETIME);
        LinearLayout.LayoutParams p1 = MeelanoTaxUi.weight(1);
        p1.setMarginEnd(ui.dp(6));
        dates.addView(ui.field("از تاریخ", from, null), p1);
        dates.addView(ui.field("تا تاریخ", to, null), MeelanoTaxUi.weight(1));
        fc.addView(dates);
        EditText q = ui.input(backs ? "شماره سند، شماره فاکتور یا نام مشتری" : "شماره فاکتور یا نام مشتری", backs ? backsQuery : salesQuery, InputType.TYPE_CLASS_TEXT);
        fc.addView(q, ui.full(0, 4));
        fc.addView(ui.button("جستجو", R.drawable.mi_search, MeelanoTaxUi.BTN_SECONDARY, v -> {
            String f = MeelanoTaxDb.foldDigits(from.getText().toString().trim()), t = MeelanoTaxDb.foldDigits(to.getText().toString().trim());
            if (MeelanoJalali.parse(f) < 0 || MeelanoJalali.parse(t) < 0) { ui.message("تاریخ", "تاریخ را به شکل ۱۴۰۵/۰۷/۰۱ وارد کنید."); return; }
            if (backs) { backsFrom = MeelanoJalali.format(MeelanoJalali.parse(f)); backsTo = MeelanoJalali.format(MeelanoJalali.parse(t)); backsQuery = q.getText().toString().trim(); backsShown = 60; }
            else { salesFrom = MeelanoJalali.format(MeelanoJalali.parse(f)); salesTo = MeelanoJalali.format(MeelanoJalali.parse(t)); salesQuery = q.getText().toString().trim(); salesShown = 60; }
            refresh();
        }));
        page.addView(fc);

        String[][] cats = {{"ALL", "همه"}, {"READY", "آماده ارسال"}, {"WAIT", "در انتظار"}, {"ERR", "رد/ناموفق"}, {"ACTION", "نیاز به اقدام"}, {"OK", "ثبت‌شده"}, {"OFF", "باطل/ابطال"}};
        List<View> chips = new ArrayList<>();
        for (String[] ct : cats) {
            int n = "ALL".equals(ct[0]) ? all.size() : counts.getOrDefault(ct[0], 0);
            chips.add(ui.chip(ct[1] + " " + fa(n), filter.equals(ct[0]), v -> {
                if (backs) { backsFilter = ct[0]; backsShown = 60; } else { salesFilter = ct[0]; salesShown = 60; }
                refresh();
            }));
        }
        page.addView(ui.chipRow(chips.toArray(new View[0])));

        // Selection helpers
        List<MeelanoTaxDb.Doc> pickable = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            String cat = category(states.get(i));
            if ("READY".equals(cat) || "ERR".equals(cat) || "ACTION".equals(cat)) pickable.add(shown.get(i));
        }
        if (!pickable.isEmpty()) {
            LinearLayout sel = ui.h();
            TextView info = ui.caption(fa(pickable.size()) + " سند قابل ارسال در این فهرست");
            sel.addView(info, MeelanoTaxUi.weight(1));
            TextView all2 = ui.chip("انتخاب همه", false, v -> {
                for (MeelanoTaxDb.Doc d : pickable) { if (backs) backsPicked.add(backKey(d)); else salesPicked.add(d.no); }
                refreshListOnly(all, backs);
            });
            sel.addView(all2);
            TextView none = ui.chip("لغو انتخاب", false, v -> { if (backs) backsPicked.clear(); else salesPicked.clear(); refreshListOnly(all, backs); });
            sel.addView(none);
            page.addView(sel);
        }

        if (shown.isEmpty()) {
            LinearLayout e = ui.card();
            e.setGravity(Gravity.CENTER_HORIZONTAL);
            e.addView(ui.icon(R.drawable.mi_inbox, MeelanoTaxUi.MUTED, 40));
            TextView t = ui.caption("سندی با این فیلتر پیدا نشد.");
            t.setGravity(Gravity.CENTER);
            e.addView(t);
            page.addView(e);
        }
        int limit = backs ? backsShown : salesShown;
        for (int i = 0; i < shown.size() && i < limit; i++) page.addView(docCard(shown.get(i), states.get(i), backs, pickable.contains(shown.get(i))));
        if (shown.size() > limit) {
            page.addView(ui.button("نمایش " + fa(Math.min(60, shown.size() - limit)) + " مورد دیگر", R.drawable.mi_more_horiz, MeelanoTaxUi.BTN_GHOST, v -> {
                if (backs) backsShown += 60; else salesShown += 60;
                refreshListOnly(all, backs);
            }));
        }

        // Footer: batch send
        View footer = null;
        int picked = backs ? backsPicked.size() : salesPicked.size();
        if (picked > 0) {
            LinearLayout f = ui.h();
            f.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
            View send = ui.button("ارسال " + fa(picked) + " سند انتخاب‌شده", R.drawable.mi_send, MeelanoTaxUi.BTN_PRIMARY, v -> {
                if (backs) batchBacks(new ArrayList<>(backsPicked)); else batchSales(new ArrayList<>(salesPicked));
            });
            f.addView(send, MeelanoTaxUi.weight(1));
            footer = f;
        }
        String title = backs ? "برگشتی و ابطال" : "فاکتورهای فروش";
        String sub = "از " + MeelanoTaxUi.fa(backs ? backsFrom : salesFrom) + " تا " + MeelanoTaxUi.fa(backs ? backsTo : salesTo) + " • " + fa(all.size()) + " سند";
        setScreen(title, sub, ui.scroll(page), footer);
    }

    /** Re-renders the list from the already loaded documents (selection changes, "more"). */
    private void refreshListOnly(List<MeelanoTaxDb.Doc> all, boolean backs) { docListScreen(all, backs); }

    private View docCard(MeelanoTaxDb.Doc d, String[] st, boolean backs, boolean pickable) {
        LinearLayout c = ui.h();
        c.setGravity(Gravity.TOP);
        c.setBackground(ui.pressable(ui.round(MeelanoTaxUi.SURFACE, 16, MeelanoTaxUi.LINE, 1), 0x220B6A70));
        c.setPadding(ui.dp(10), ui.dp(12), ui.dp(14), ui.dp(12));
        if (pickable) {
            CheckBox cb = new CheckBox(this);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(MeelanoTaxUi.PRIMARY));
            cb.setChecked(backs ? backsPicked.contains(backKey(d)) : salesPicked.contains(d.no));
            cb.setOnCheckedChangeListener((b, on) -> {
                if (backs) { if (on) backsPicked.add(backKey(d)); else backsPicked.remove(backKey(d)); }
                else { if (on) salesPicked.add(d.no); else salesPicked.remove(d.no); }
                refreshFooterCount(backs);
            });
            c.addView(cb);
        } else {
            View sp = new View(this);
            c.addView(sp, MeelanoTaxUi.lp(ui.dp(8), 1));
        }
        LinearLayout col = ui.v();
        LinearLayout top = ui.h();
        TextView t = ui.text(d.title(), 14.5f, MeelanoTaxUi.TEXT, true);
        top.addView(t, MeelanoTaxUi.weight(1));
        top.addView(ui.badge(st[0], st[1]));
        col.addView(top);
        TextView name = ui.text(d.name.isEmpty() ? "—" : d.name, 13, MeelanoTaxUi.TEXT, false);
        name.setPadding(0, ui.dp(3), 0, ui.dp(2));
        name.setSingleLine(true);
        col.addView(name);
        LinearLayout meta = ui.h();
        TextView date = ui.caption(MeelanoTaxUi.fa(d.date) + (d.time.isEmpty() ? "" : "  " + MeelanoTaxUi.fa(d.time)));
        meta.addView(date, MeelanoTaxUi.weight(1));
        TextView amt = ui.text(MeelanoTaxUi.rial(d.total), 13, MeelanoTaxUi.PRIMARY_DARK, true);
        meta.addView(amt);
        col.addView(meta);
        if (backs && d.refSale > 0) col.addView(ui.caption("فاکتور مرجع: " + fa(d.refSale)));
        c.addView(col, MeelanoTaxUi.weight(1));
        c.setOnClickListener(v -> {
            if (backs) push(() -> renderBackDetail(d.backKind, d.no));
            else push(() -> renderSaleDetail(d.no));
        });
        c.setLayoutParams(ui.full(0, 8));
        return c;
    }

    private void refreshFooterCount(boolean backs) {
        // Rebuild only the footer: simplest is a light re-render of the current list screen from cache.
        int picked = backs ? backsPicked.size() : salesPicked.size();
        View wrap = content.getChildAt(0);
        if (!(wrap instanceof LinearLayout)) return;
        LinearLayout w = (LinearLayout) wrap;
        if (w.getChildCount() > 1) w.removeViewAt(1);
        if (picked == 0) return;
        LinearLayout f = ui.h();
        f.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        f.setBackgroundColor(MeelanoTaxUi.SURFACE);
        f.setElevation(ui.dp(6));
        f.addView(ui.button("ارسال " + fa(picked) + " سند انتخاب‌شده", R.drawable.mi_send, MeelanoTaxUi.BTN_PRIMARY, v -> {
            if (backs) batchBacks(new ArrayList<>(backsPicked)); else batchSales(new ArrayList<>(salesPicked));
        }), MeelanoTaxUi.weight(1));
        w.addView(f, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ batch send

    private void batchSales(List<Integer> nos) {
        try { api(); } catch (Exception e) { showError(e); return; }
        db("در حال آماده‌سازی " + fa(nos.size()) + " فاکتور…", c -> {
            Map<String, String> units = MeelanoTaxDb.unitMap(c);
            MeelanoTaxEngine.Config cf = cfg();
            List<MeelanoTaxEngine.Prepared> out = new ArrayList<>();
            for (int no : nos) {
                MeelanoTaxDb.Doc d = MeelanoTaxDb.sale(c, no);
                if (d != null) out.add(MeelanoTaxEngine.prepareSale(c, cf, d, units, false));
            }
            return out;
        }, list -> confirmBatch(list, () -> salesPicked.clear()));
    }

    private void batchBacks(List<String> keys) {
        try { api(); } catch (Exception e) { showError(e); return; }
        db("در حال آماده‌سازی " + fa(keys.size()) + " سند…", c -> {
            Map<String, String> units = MeelanoTaxDb.unitMap(c);
            MeelanoTaxEngine.Config cf = cfg();
            List<MeelanoTaxEngine.Prepared> out = new ArrayList<>();
            for (String k : keys) {
                String[] p = k.split(":");
                MeelanoTaxDb.Doc d = findBack(c, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                if (d != null) out.add(MeelanoTaxEngine.prepareBack(c, cf, d, units, null, 0, false));
            }
            return out;
        }, list -> confirmBatch(list, () -> backsPicked.clear()));
    }

    private static MeelanoTaxDb.Doc findBack(Connection c, int kind, int no) throws Exception {
        for (MeelanoTaxDb.Doc d : MeelanoTaxDb.backs(c, "0000/00/00", "9999/99/99", String.valueOf(no), 200)) {
            if (d.no == no && d.backKind == kind) return d;
        }
        return null;
    }

    private void confirmBatch(List<MeelanoTaxEngine.Prepared> list, Runnable clearSelection) {
        int ready = 0;
        long total = 0, vat = 0;
        LinearLayout body = ui.v();
        List<MeelanoTaxEngine.Prepared> blocked = new ArrayList<>();
        for (MeelanoTaxEngine.Prepared p : list) {
            if (p.sendable()) { ready++; total += p.result.tbill; vat += p.result.tvam; } else blocked.add(p);
        }
        body.addView(ui.row("آماده ارسال", fa(ready) + " سند"));
        body.addView(ui.row("جمع مبلغ", MeelanoTaxUi.rial(total)));
        body.addView(ui.row("جمع مالیات", MeelanoTaxUi.rial(vat)));
        body.addView(ui.row("محیط", sandbox() ? "آزمایشی" : "اصلی (رسمی)"));
        if (!blocked.isEmpty()) {
            body.addView(ui.divider());
            body.addView(ui.text(fa(blocked.size()) + " سند ارسال نمی‌شود:", 13.5f, MeelanoTaxUi.ERR, true));
            int n = 0;
            for (MeelanoTaxEngine.Prepared p : blocked) {
                if (n++ >= 25) { body.addView(ui.caption("…")); break; }
                String why = MeelanoTaxEngine.ACT_NONE.equals(p.action) ? p.message : (p.result == null ? "" : p.result.errorTexts().isEmpty() ? "" : p.result.errorTexts().get(0));
                body.addView(ui.issue(p.doc.title() + ": " + why, !MeelanoTaxEngine.ACT_NONE.equals(p.action)));
            }
        }
        if (ready == 0) { ui.sheet("ارسال گروهی", body, "باشه", null, null); return; }
        final int r = ready;
        ui.sheet("ارسال گروهی به سامانه مودیان", body, "ارسال " + fa(r) + " سند", () -> doSend(list, clearSelection), "انصراف");
    }

    private void doSend(List<MeelanoTaxEngine.Prepared> list, Runnable after) {
        final MeelanoTaxApi api;
        try { api = api(); } catch (Exception e) { showError(e); return; }
        MeelanoTaxEngine.Config cf = cfg();
        db("در حال امضا، رمزنگاری و ارسال…", c -> MeelanoTaxEngine.send(c, cf, api, list), o -> {
            if (after != null) after.run();
            LinearLayout body = ui.v();
            body.addView(ui.row("فرستاده شد", fa(o.sent)));
            body.addView(ui.row("ناموفق", fa(o.failed)));
            body.addView(ui.row("ارسال نشد", fa(o.skipped)));
            if (!o.lines.isEmpty()) {
                body.addView(ui.divider());
                int n = 0;
                for (String s : o.lines) { if (n++ > 40) break; body.addView(ui.caption("• " + s)); }
            }
            if (o.sent > 0) body.addView(ui.issue("نتیجه نهایی حداکثر چند ثانیه دیگر از سامانه دریافت می‌شود (استعلام خودکار).", false));
            ui.sheet("نتیجه ارسال", body, "باشه", null, null);
            refresh();
            if (o.sent > 0) main.postDelayed(() -> { if (!isFinishing() && user != null) inquireOpen(true); }, 12_000L);
        });
    }

    /** Inquiry of every open submission. {@code quiet} = automatic (no dialog when nothing changed). */
    private void inquireOpen(boolean quiet) {
        final MeelanoTaxApi api;
        try { api = api(); } catch (Exception e) { if (!quiet) showError(e); return; }
        MeelanoTaxEngine.Config cf = cfg();
        db(quiet ? null : "در حال استعلام نتیجه از سامانه…", c -> {
            List<MeelanoTaxDb.Log> open = MeelanoTaxDb.openLogs(c, 1000);
            if (open.isEmpty()) return null;
            return MeelanoTaxEngine.inquire(c, cf, api, open);
        }, r -> {
            if (r == null) { if (!quiet) ui.message("استعلام", "ارسالِ در جریانی وجود ندارد."); return; }
            if (quiet && r.success == 0 && r.failed == 0 && r.notFound == 0) return;
            LinearLayout body = ui.v();
            body.addView(ui.row("ثبت شد", fa(r.success)));
            body.addView(ui.row("رد شد", fa(r.failed)));
            body.addView(ui.row("هنوز در صف بررسی", fa(r.waiting)));
            if (r.notFound > 0) body.addView(ui.row("به سامانه نرسیده", fa(r.notFound)));
            if (!r.lines.isEmpty()) {
                body.addView(ui.divider());
                int n = 0;
                for (String s : r.lines) { if (n++ > 40) break; body.addView(ui.caption("• " + s)); }
            }
            if (r.waiting > 0) body.addView(ui.caption("موارد در صف را چند دقیقه بعد دوباره استعلام کنید."));
            ui.sheet("نتیجه استعلام", body, "باشه", null, null);
            refresh();
        });
    }

    private void refreshKartable() {
        final MeelanoTaxApi api;
        try { api = api(); } catch (Exception e) { showError(e); return; }
        db("در حال دریافت وضعیت کارپوشه…", c -> MeelanoTaxEngine.refreshKartable(c, api, MeelanoTaxDb.acceptedWithoutFinalKartable(c, 500)), n -> {
            ui.message("وضعیت کارپوشه", n == 0 ? "تغییری در وضعیت صورتحساب‌ها در کارپوشه خریداران نبود." : "وضعیت " + fa(n) + " صورتحساب به‌روز شد.");
            refresh();
        });
    }

    // ------------------------------------------------------------------ document detail

    private void renderSaleDetail(int no) {
        load("فاکتور فروش " + fa(no), "بررسی و آماده‌سازی", c -> {
            MeelanoTaxDb.Doc d = MeelanoTaxDb.sale(c, no);
            if (d == null) throw new IllegalArgumentException("فاکتور " + no + " در آتیران پیدا نشد.");
            return MeelanoTaxEngine.prepareSale(c, cfg(), d, MeelanoTaxDb.unitMap(c), false);
        }, p -> setScreen("فاکتور فروش " + fa(no), p.doc.name, ui.scroll(detailView(p)), detailFooter(p)));
    }

    private String backManualTaxid = "";
    private String backManualSale = "";
    private boolean backFullReturn;
    private String backManualFor = "";

    private void renderBackDetail(int kind, int no) {
        String key = kind + ":" + no;
        if (!key.equals(backManualFor)) { backManualFor = key; backManualTaxid = ""; backManualSale = ""; backFullReturn = false; }
        String title = MeelanoTaxDb.backKindTitle(kind) + " " + fa(no);
        load(title, "بررسی و آماده‌سازی", c -> {
            MeelanoTaxDb.Doc d = findBack(c, kind, no);
            if (d == null) throw new IllegalArgumentException("سند " + no + " در آتیران پیدا نشد.");
            return MeelanoTaxEngine.prepareBack(c, cfg(), d, MeelanoTaxDb.unitMap(c), backManualTaxid, (int) MeelanoTaxEngine.parseLong(backManualSale, 0), backFullReturn);
        }, p -> setScreen(title, p.doc.name, ui.scroll(detailView(p)), detailFooter(p)));
    }

    private View detailFooter(MeelanoTaxEngine.Prepared p) {
        if (!p.sendable()) return null;
        LinearLayout f = ui.h();
        f.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        f.addView(ui.button(p.actionTitle(), MeelanoTaxEngine.ACT_CANCEL.equals(p.action) ? R.drawable.mi_block : R.drawable.mi_send,
                MeelanoTaxEngine.ACT_CANCEL.equals(p.action) ? MeelanoTaxUi.BTN_DANGER : MeelanoTaxUi.BTN_PRIMARY, v -> confirmSingle(p)), MeelanoTaxUi.weight(1));
        return f;
    }

    private void confirmSingle(MeelanoTaxEngine.Prepared p) {
        LinearLayout body = ui.v();
        body.addView(ui.row("سند", p.doc.title()));
        body.addView(ui.row("نوع ارسال", p.actionTitle()));
        body.addView(ui.row("مبلغ نهایی", MeelanoTaxUi.rial(p.result.tbill)));
        body.addView(ui.row("مالیات", MeelanoTaxUi.rial(p.result.tvam)));
        body.addView(ui.row("محیط", sandbox() ? "آزمایشی" : "اصلی (رسمی)"));
        if (p.result.late) body.addView(ui.issue("خارج از مهلت ارسال است و طبق ماده ۹ با «قاعده ارسال = ۱» فرستاده می‌شود.", false));
        int w = p.result.warningTexts().size();
        if (w > 0) body.addView(ui.issue(fa(w) + " هشدار دارد (پیش از ارسال مرور کنید).", false));
        ui.sheet(p.actionTitle(), body, "ارسال", () -> doSend(Collections.singletonList(p), null), "انصراف");
    }

    private View detailView(MeelanoTaxEngine.Prepared p) {
        MeelanoTaxDb.Doc d = p.doc;
        boolean sale = MeelanoTaxDb.SALE.equals(d.kind);
        LinearLayout page = ui.page();
        String[] st = MeelanoTaxEngine.state(d, cfg().deadlineDays, System.currentTimeMillis());

        LinearLayout head = ui.card();
        LinearLayout hr = ui.h();
        hr.addView(ui.title(d.title()), MeelanoTaxUi.weight(1));
        hr.addView(ui.badge(st[0], st[1]));
        head.addView(hr);
        head.addView(ui.space(6));
        head.addView(ui.row("تاریخ و ساعت", MeelanoTaxUi.fa(d.date + (d.time.isEmpty() ? "" : "  " + d.time))));
        head.addView(ui.row("مشتری", d.name + (d.shmo > 0 ? " (کد " + fa(d.shmo) + ")" : "")));
        head.addView(ui.row("مبلغ در آتیران", MeelanoTaxUi.rial(d.total)));
        head.addView(ui.row("مالیات و عوارض در آتیران", MeelanoTaxUi.rial(d.tax)));
        if (!sale) head.addView(ui.row("فاکتور فروش مرجع", d.refSale > 0 ? fa(d.refSale) : "ثبت نشده"));
        if (sale && !d.active) head.addView(ui.issue("این فاکتور در آتیران باطل شده است.", false));
        if (d.atiranTaxId != null) head.addView(ui.rowLtr("شماره مالیاتی در آتیران", d.atiranTaxId));
        page.addView(head);

        if (sale) {
            LinearLayout b = ui.card();
            b.addView(ui.heading("خریدار"));
            b.addView(ui.row("نوع شخص", MeelanoTaxInvoice.tobTitle(d.tob)));
            b.addView(ui.row("نوع صورتحساب", d.custInty == null ? "خودکار" : (d.custInty == 1 ? "نوع اول (با مشخصات خریدار)" : "نوع دوم")));
            b.addView(ui.rowLtr("کد/شناسه ملی", d.bid));
            b.addView(ui.rowLtr("شماره اقتصادی", d.tinb));
            b.addView(ui.rowLtr("کد پستی", d.bpc));
            if (d.bbc != null) b.addView(ui.rowLtr("کد شعبه خریدار", d.bbc));
            b.addView(ui.button("ویرایش مشخصات مالیاتی خریدار", R.drawable.mi_person_search, MeelanoTaxUi.BTN_GHOST, v -> openBuyerEditor(d.shmo)));
            page.addView(b);
        } else if (d.backKind == 2 || d.backKind == 6 || d.backKind == 8 || d.refSale <= 0) {
            page.addView(backReferenceCard(d));
        }

        // Action & validation
        LinearLayout ac = ui.card();
        ac.addView(ui.heading(MeelanoTaxEngine.ACT_NONE.equals(p.action) ? "وضعیت ارسال" : p.actionTitle()));
        if (MeelanoTaxEngine.ACT_NONE.equals(p.action)) ac.addView(ui.body(p.message));
        for (String n : p.notes) ac.addView(ui.caption("• " + n));
        if (p.irtaxid != null) ac.addView(ui.rowLtr("شماره مالیاتی مرجع", p.irtaxid));
        if (p.result != null) {
            for (MeelanoTaxInvoice.Issue i : p.result.issues) if (i.error) ac.addView(ui.issue(i.toString(), true));
            for (MeelanoTaxInvoice.Issue i : p.result.issues) if (!i.error) ac.addView(ui.issue(i.toString(), false));
            if (!p.result.hasErrors()) ac.addView(ui.checkLine("صورتحساب با قواعد سامانه سازگار است", null, true, false));
            ac.addView(ui.divider());
            Map<String, Object> h = MeelanoTaxJson.obj(p.result.invoice, "header");
            ac.addView(ui.row("نوع صورتحساب", MeelanoTaxJson.num(h, "inty", 1) == 1 ? "نوع اول" : "نوع دوم"));
            ac.addView(ui.row("موضوع", MeelanoTaxInvoice.insTitle((int) MeelanoTaxJson.num(h, "ins", 1))));
            ac.addView(ui.row("مبلغ قبل از تخفیف", MeelanoTaxUi.rial(MeelanoTaxJson.num(h, "tprdis", 0))));
            ac.addView(ui.row("تخفیف", MeelanoTaxUi.rial(MeelanoTaxJson.num(h, "tdis", 0))));
            ac.addView(ui.row("مبلغ پس از تخفیف", MeelanoTaxUi.rial(MeelanoTaxJson.num(h, "tadis", 0))));
            ac.addView(ui.row("مالیات بر ارزش افزوده", MeelanoTaxUi.rial(MeelanoTaxJson.num(h, "tvam", 0))));
            ac.addView(ui.row("مبلغ نهایی صورتحساب", MeelanoTaxUi.rial(MeelanoTaxJson.num(h, "tbill", 0))));
        }
        page.addView(ac);

        // Lines
        if (p.result != null) {
            List<Object> body = MeelanoTaxJson.arr(p.result.invoice, "body");
            LinearLayout lc = ui.card();
            lc.addView(ui.heading("اقلام صورتحساب (" + fa(body == null ? 0 : body.size()) + " ردیف)"));
            int i = 0;
            if (body != null) for (Object o : body) {
                Map<String, Object> m = MeelanoTaxJson.obj(o);
                final MeelanoTaxInvoice.Line src = i < p.result.lines.size() ? p.result.lines.get(i) : null;
                i++;
                lc.addView(lineView(i, m, src));
            }
            page.addView(lc);
        }

        // History
        List<MeelanoTaxDb.Log> hist = new ArrayList<>();
        for (MeelanoTaxDb.Log l : d.chain) if (sale || (l.docKind.equals(d.kind) && l.docNo == d.no && l.backKind == d.backKind)) hist.add(l);
        if (!hist.isEmpty()) {
            LinearLayout hc = ui.card();
            hc.addView(ui.heading(sale ? "سابقه ارسال (زنجیره این فاکتور)" : "سابقه ارسال"));
            for (int k = hist.size() - 1; k >= 0; k--) {
                MeelanoTaxDb.Log l = hist.get(k);
                String what = (MeelanoTaxDb.SALE.equals(l.docKind) ? "فاکتور " : MeelanoTaxDb.backKindTitle(l.backKind) + " ") + fa(l.docNo) + " — " + MeelanoTaxInvoice.insTitle(l.ins);
                hc.addView(listCard(what, (l.taxid == null ? "" : l.taxid + "\n") + MeelanoTaxUi.fa(nz(l.createdAt)), ui.badge(statusFa(l.status), statusTone(l.status)),
                        v -> push(() -> renderLogDetail(l.id))));
            }
            page.addView(hc);
        }

        // Other actions
        LinearLayout oc = ui.card();
        oc.addView(ui.heading("عملیات"));
        MeelanoTaxDb.Log pending = MeelanoTaxEngine.pendingIn(hist);
        MeelanoTaxDb.Log acc = MeelanoTaxEngine.latestAccepted(hist);
        if (pending != null) oc.addView(ui.button("استعلام نتیجه ارسال", R.drawable.mi_sync, MeelanoTaxUi.BTN_PRIMARY, v -> inquireOpen(false)));
        if (sale && acc != null && acc.ins != MeelanoTaxInvoice.INS_CANCEL && pending == null) {
            oc.addView(ui.button("ابطال این فاکتور در سامانه", R.drawable.mi_block, MeelanoTaxUi.BTN_DANGER, v -> askCancel(d)));
            if (acc.taxid != null) oc.addView(ui.button("ثبت پرداخت (تسویه) در سامانه", R.drawable.mi_payments, MeelanoTaxUi.BTN_GHOST, v -> openPayment(acc.taxid, acc.tbill)));
        }
        if (acc == null && pending == null) {
            if (d.atiranTaxId != null && MeelanoTaxCrypto.taxIdValid(d.atiranTaxId)) {
                oc.addView(ui.button("ثبت شماره مالیاتی آتیران در سوابق", R.drawable.mi_download, MeelanoTaxUi.BTN_SECONDARY, v -> saveExternal(d, d.atiranTaxId,
                        sale ? MeelanoTaxInvoice.INS_ORIGINAL : (d.backKind == 8 || d.backKind == 9 ? MeelanoTaxInvoice.INS_CANCEL : MeelanoTaxInvoice.INS_RETURN), d.atiranRefTaxId, null, "از آتیران")));
            }
            oc.addView(ui.button("ثبت دستی (در کارپوشه یا جای دیگر ارسال شده)", R.drawable.mi_edit, MeelanoTaxUi.BTN_GHOST, v -> askExternal(d)));
        }
        if (p.result != null) oc.addView(ui.button("مشاهده متن صورتحساب (JSON)", R.drawable.mi_description, MeelanoTaxUi.BTN_GHOST, v -> showJson(p.result.json)));
        page.addView(oc);
        return page;
    }

    private View lineView(int index, Map<String, Object> m, MeelanoTaxInvoice.Line src) {
        LinearLayout r = ui.v();
        r.setBackground(ui.pressable(ui.round(MeelanoTaxUi.SURFACE_2, 12, MeelanoTaxUi.LINE, 1), 0x220B6A70));
        r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        String sstid = MeelanoTaxJson.str(m, "sstid");
        String name = MeelanoTaxJson.str(m, "sstt");
        r.addView(ui.text(fa(index) + ". " + (name == null ? (src == null ? "" : src.name) : name), 13.5f, MeelanoTaxUi.TEXT, true));
        boolean okId = sstid != null && sstid.length() == 13;
        LinearLayout idr = ui.h();
        idr.addView(ui.caption("شناسه کالا: "));
        TextView idv = ui.ltr(okId ? sstid : "ثبت نشده", 12.5f, okId ? MeelanoTaxUi.TEXT : MeelanoTaxUi.ERR, true);
        idr.addView(idv);
        String mu = MeelanoTaxJson.str(m, "mu");
        if (mu != null) idr.addView(ui.caption("   واحد: " + MeelanoTaxInvoice.unitName(mu)));
        r.addView(idr);
        BigDecimal am = MeelanoTaxJson.dec(m, "am"), fee = MeelanoTaxJson.dec(m, "fee");
        LinearLayout q = ui.h();
        q.addView(ui.caption("مقدار " + MeelanoTaxUi.fa(am == null ? "0" : MeelanoTaxJson.plain(am))), MeelanoTaxUi.weight(1));
        q.addView(ui.caption("فی " + MeelanoTaxUi.money(fee == null ? 0 : fee.longValue())));
        r.addView(q);
        LinearLayout t = ui.h();
        long dis = MeelanoTaxJson.num(m, "dis", 0);
        BigDecimal vra = MeelanoTaxJson.dec(m, "vra");
        t.addView(ui.caption((dis > 0 ? "تخفیف " + MeelanoTaxUi.money(dis) + "  •  " : "") + "مالیات " + MeelanoTaxUi.fa(vra == null ? "0" : MeelanoTaxJson.plain(vra)) + "٪"), MeelanoTaxUi.weight(1));
        t.addView(ui.text(MeelanoTaxUi.rial(MeelanoTaxJson.num(m, "tsstam", 0)), 13, MeelanoTaxUi.PRIMARY_DARK, true));
        r.addView(t);
        if (src != null && src.shka > 0) {
            r.setClickable(true);
            r.setOnClickListener(v -> openGoodEditor(src.shka));
        }
        r.setLayoutParams(ui.full(0, 6));
        return r;
    }

    private View backReferenceCard(MeelanoTaxDb.Doc d) {
        LinearLayout c = ui.card();
        c.addView(ui.heading("صورتحساب مرجع"));
        boolean prevYear = d.backKind == 2 || d.backKind == 8;
        c.addView(ui.caption(prevYear
                ? "این سند مربوط به فروش سال‌های قبل است؛ شماره مالیاتی ۲۲ کاراکتری صورتحساب اصلی را از کارپوشه یا سوابق وارد کنید."
                : "شماره فاکتور فروش مرجع را وارد کنید (یا اگر اصل صورتحساب در جای دیگری ثبت شده، شماره مالیاتی آن را)."));
        EditText sale = ui.ltrInput("شماره فاکتور فروش", backManualSale, InputType.TYPE_CLASS_NUMBER);
        EditText tax = ui.ltrInput("مثال: A11216049B40000000011A", backManualTaxid, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (!prevYear) c.addView(ui.field("شماره فاکتور فروش مرجع", sale, null));
        c.addView(ui.field("شماره مالیاتی صورتحساب مرجع", tax, null));
        CheckBox full = ui.check("همه اقلام صورتحساب مرجع برگشت خورده است (ارسال به‌صورت ابطالی)", backFullReturn);
        if (d.backKind == 2 || d.backKind == 6) c.addView(full);
        c.addView(ui.button("بررسی دوباره با این مرجع", R.drawable.mi_refresh, MeelanoTaxUi.BTN_SECONDARY, v -> {
            backManualSale = MeelanoTaxDb.foldDigits(sale.getText().toString().trim());
            backManualTaxid = tax.getText().toString().trim().toUpperCase(Locale.US);
            backFullReturn = full.isChecked();
            refresh();
        }));
        return c;
    }

    private void askCancel(MeelanoTaxDb.Doc d) {
        db("در حال آماده‌سازی ابطالی…", c -> {
            MeelanoTaxDb.Doc fresh = MeelanoTaxDb.sale(c, d.no);
            return MeelanoTaxEngine.prepareSale(c, cfg(), fresh == null ? d : fresh, MeelanoTaxDb.unitMap(c), true);
        }, p -> {
            if (!p.sendable()) {
                String why = MeelanoTaxEngine.ACT_NONE.equals(p.action) ? p.message : String.join("\n", p.result.errorTexts());
                ui.message("ابطال", why);
                return;
            }
            ui.confirm("ابطال فاکتور " + fa(d.no), "صورتحساب " + p.irtaxid + " در سامانه مودیان ابطال می‌شود. بعد از ابطال، اگر فاکتور را در آتیران ویرایش کنید، نسخه جدید به‌عنوان صورتحساب اصلی فرستاده می‌شود.\n\nادامه می‌دهید؟",
                    "ابطال در سامانه", () -> doSend(Collections.singletonList(p), null));
        });
    }

    private void askExternal(MeelanoTaxDb.Doc d) {
        boolean sale = MeelanoTaxDb.SALE.equals(d.kind);
        LinearLayout body = ui.v();
        body.addView(ui.caption("اگر این سند قبلاً در کارپوشه، آتیران یا برنامه دیگری به سامانه فرستاده و پذیرفته شده، شماره مالیاتی آن را ثبت کنید تا دوباره ارسال نشود و برگشتی/ابطال آن از همین برنامه انجام شود."));
        EditText tax = ui.ltrInput("شماره مالیاتی ۲۲ کاراکتری", d.atiranTaxId == null ? "" : d.atiranTaxId, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        body.addView(ui.field("شماره مالیاتی", tax, null));
        final int[] ins = {sale ? MeelanoTaxInvoice.INS_ORIGINAL : (d.backKind == 8 || d.backKind == 9 ? MeelanoTaxInvoice.INS_CANCEL : MeelanoTaxInvoice.INS_RETURN)};
        LinearLayout insRow = ui.h();
        body.addView(ui.text("موضوع صورتحساب", 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        body.addView(insRow);
        Runnable[] drawIns = new Runnable[1];
        drawIns[0] = () -> {
            insRow.removeAllViews();
            String[] names = {"اصلی", "اصلاحی", "ابطالی", "برگشت از فروش"};
            for (int k = 1; k <= 4; k++) {
                final int kk = k;
                insRow.addView(ui.chip(names[k - 1], ins[0] == k, v -> { ins[0] = kk; drawIns[0].run(); }));
            }
        };
        drawIns[0].run();
        EditText ref = ui.ltrInput("برای اصلاحی/ابطالی/برگشتی", d.atiranRefTaxId == null ? "" : d.atiranRefTaxId, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        body.addView(ui.field("شماره مالیاتی مرجع", ref, null));
        EditText trk = ui.ltrInput("اختیاری", "", InputType.TYPE_CLASS_TEXT);
        body.addView(ui.field("شماره پیگیری", trk, null));
        EditText note = ui.input("اختیاری", "", InputType.TYPE_CLASS_TEXT);
        body.addView(ui.field("توضیح", note, null));
        ui.sheet("ثبت دستی ارسال", body, "ثبت در سوابق", () -> saveExternal(d, tax.getText().toString(), ins[0], ref.getText().toString(), trk.getText().toString(), note.getText().toString()), "انصراف");
    }

    private void saveExternal(MeelanoTaxDb.Doc d, String taxid, int ins, String ref, String tracking, String note) {
        MeelanoTaxEngine.Config cf = cfg();
        db("در حال ثبت…", c -> MeelanoTaxEngine.markExternal(c, cf, d, taxid, ins, ref, tracking, note), id -> {
            toast("در سوابق ثبت شد");
            refresh();
        });
    }

    private void showJson(String json) {
        TextView t = ui.ltr(prettyJson(json), 11.5f, MeelanoTaxUi.TEXT, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        Dialog d = ui.sheet("متن صورتحساب", t, "کپی", () -> copy("invoice", json), "بستن");
        if (d == null) return;
    }

    /** Indented JSON for reading. */
    static String prettyJson(String json) {
        if (json == null) return "";
        StringBuilder b = new StringBuilder();
        int indent = 0;
        boolean str = false, esc = false;
        for (char ch : json.toCharArray()) {
            if (str) {
                b.append(ch);
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') str = false;
                continue;
            }
            switch (ch) {
                case '"': str = true; b.append(ch); break;
                case '{': case '[': indent++; b.append(ch).append('\n').append(pad(indent)); break;
                case '}': case ']': indent = Math.max(0, indent - 1); b.append('\n').append(pad(indent)).append(ch); break;
                case ',': b.append(ch).append('\n').append(pad(indent)); break;
                case ':': b.append(": "); break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    private static String pad(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append("  ");
        return b.toString();
    }

    // ================================================================== پیگیری ارسال‌ها

    private String logFilter = "OPEN", logQuery = "";

    private void renderLogs() {
        load("پیگیری ارسال‌ها", "سوابق ارسال به سامانه مودیان", c -> {
            Object[] r = new Object[2];
            r[0] = MeelanoTaxDb.logs(c, logFilter, logQuery, 400);
            r[1] = MeelanoTaxDb.statusCounts(c);
            return r;
        }, r -> {
            @SuppressWarnings("unchecked") List<MeelanoTaxDb.Log> list = (List<MeelanoTaxDb.Log>) r[0];
            @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) r[1];
            LinearLayout page = ui.page();
            LinearLayout tools = ui.card();
            tools.addView(ui.pair(
                    ui.button("استعلام در جریان‌ها", R.drawable.mi_sync, MeelanoTaxUi.BTN_PRIMARY, v -> inquireOpen(false)),
                    ui.button("وضعیت کارپوشه", R.drawable.mi_fact_check, MeelanoTaxUi.BTN_SECONDARY, v -> refreshKartable())));
            EditText q = ui.input("جستجو: شماره مالیاتی، شماره پیگیری، مشتری یا شماره سند", logQuery, InputType.TYPE_CLASS_TEXT);
            tools.addView(q, ui.full(6, 4));
            tools.addView(ui.button("جستجو", R.drawable.mi_search, MeelanoTaxUi.BTN_GHOST, v -> { logQuery = q.getText().toString().trim(); refresh(); }));
            page.addView(tools);
            int open = 0, failed = 0;
            for (String k : new String[]{"PENDING", "SENDING", "IN_PROGRESS", "UNKNOWN"}) open += counts.getOrDefault(k, 0);
            String[][] f = {{"OPEN", "در جریان " + fa(open)}, {"FAILED", "رد شده " + fa(counts.getOrDefault("FAILED", 0))},
                    {"NOT_FOUND", "نرسیده " + fa(counts.getOrDefault("NOT_FOUND", 0))}, {"LOCAL_ERROR", "خطای پیش از ارسال " + fa(counts.getOrDefault("LOCAL_ERROR", 0))},
                    {"SUCCESS", "ثبت شده " + fa(counts.getOrDefault("SUCCESS", 0))}, {"EXTERNAL", "ثبت دستی " + fa(counts.getOrDefault("EXTERNAL", 0))},
                    {"VOID", "کنار گذاشته " + fa(counts.getOrDefault("VOID", 0))}, {"", "همه"}};
            List<View> chips = new ArrayList<>();
            for (String[] x : f) chips.add(ui.chip(x[1], x[0].equals(logFilter), v -> { logFilter = x[0]; refresh(); }));
            page.addView(ui.chipRow(chips.toArray(new View[0])));
            if (list.isEmpty()) {
                LinearLayout e = ui.card();
                e.setGravity(Gravity.CENTER_HORIZONTAL);
                e.addView(ui.icon(R.drawable.mi_inbox, MeelanoTaxUi.MUTED, 40));
                TextView t = ui.caption("موردی نیست.");
                t.setGravity(Gravity.CENTER);
                e.addView(t);
                page.addView(e);
            }
            for (MeelanoTaxDb.Log l : list) page.addView(logCard(l));
            setScreen("پیگیری ارسال‌ها", fa(list.size()) + " مورد", ui.scroll(page), null);
        });
    }

    private String logTitle(MeelanoTaxDb.Log l) {
        return (MeelanoTaxDb.SALE.equals(l.docKind) ? "فاکتور " : MeelanoTaxDb.backKindTitle(l.backKind) + " ") + fa(l.docNo) + " — " + MeelanoTaxInvoice.insTitle(l.ins);
    }

    private View logCard(MeelanoTaxDb.Log l) {
        LinearLayout c = ui.v();
        c.setBackground(ui.pressable(ui.round(MeelanoTaxUi.SURFACE, 16, MeelanoTaxUi.LINE, 1), 0x220B6A70));
        c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        LinearLayout top = ui.h();
        top.addView(ui.text(logTitle(l), 14, MeelanoTaxUi.TEXT, true), MeelanoTaxUi.weight(1));
        top.addView(ui.badge(statusFa(l.status), statusTone(l.status)));
        c.addView(top);
        if (l.customerName != null) c.addView(ui.caption(l.customerName));
        if (l.taxid != null) c.addView(ui.ltr(l.taxid, 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        LinearLayout meta = ui.h();
        meta.addView(ui.caption(MeelanoTaxUi.fa(nz(l.createdAt))), MeelanoTaxUi.weight(1));
        meta.addView(ui.text(MeelanoTaxUi.rial(l.tbill), 12.5f, MeelanoTaxUi.TEXT, true));
        c.addView(meta);
        if (l.kartable != null && !l.kartable.isEmpty()) c.addView(ui.badge("کارپوشه: " + MeelanoTaxApi.statusTitle(l.kartable.split(" ")[0]), kartableTone(l.kartable)));
        if (l.errors != null && ("FAILED".equals(l.status) || "LOCAL_ERROR".equals(l.status))) c.addView(ui.issue(MeelanoTaxUi.ellipsize(l.errors.split("\n")[0], 140), true));
        c.setClickable(true);
        c.setOnClickListener(v -> push(() -> renderLogDetail(l.id)));
        c.setLayoutParams(ui.full(0, 8));
        return c;
    }

    private void renderLogDetail(long id) {
        load("جزئیات ارسال", null, c -> {
            MeelanoTaxDb.Log l = MeelanoTaxDb.logById(c, id);
            if (l == null) throw new IllegalArgumentException("سابقه پیدا نشد.");
            return l;
        }, l -> {
            LinearLayout page = ui.page();
            LinearLayout h = ui.card();
            LinearLayout hr = ui.h();
            hr.addView(ui.title(logTitle(l)), MeelanoTaxUi.weight(1));
            h.addView(hr);
            h.addView(ui.badge(statusFa(l.status), statusTone(l.status)));
            h.addView(ui.space(6));
            h.addView(ui.rowLtr("شماره مالیاتی", l.taxid));
            if (l.irtaxid != null) h.addView(ui.rowLtr("شماره مالیاتی مرجع", l.irtaxid));
            h.addView(ui.rowLtr("شماره پیگیری سامانه", l.reference));
            h.addView(ui.rowLtr("شناسه درخواست", l.uid));
            h.addView(ui.row("سریال صورتحساب", l.serial > 0 ? fa(l.serial) : "—"));
            h.addView(ui.row("تاریخ صدور", jdate(l.indatim)));
            h.addView(ui.row("زمان ثبت در برنامه", MeelanoTaxUi.fa(nz(l.createdAt))));
            h.addView(ui.row("کاربر", l.user));
            h.addView(ui.row("محیط", "PRODUCTION".equals(l.env) ? "اصلی" : "آزمایشی"));
            h.addView(ui.row("مشتری", l.customerName));
            h.addView(ui.row("مبلغ نهایی", MeelanoTaxUi.rial(l.tbill)));
            h.addView(ui.row("مالیات", MeelanoTaxUi.rial(l.tvam)));
            if (l.kartable != null) h.addView(ui.row("وضعیت در کارپوشه خریدار", MeelanoTaxApi.statusTitle(l.kartable.split(" ")[0])));
            if (l.note != null) h.addView(ui.row("توضیح", l.note));
            page.addView(h);
            if (l.errors != null && !l.errors.isEmpty()) {
                LinearLayout e = ui.softCard(MeelanoTaxUi.ERR_SOFT, 0xFFF3C9C3);
                e.addView(ui.heading("خطاها"));
                for (String s : l.errors.split("\n")) if (!s.trim().isEmpty()) e.addView(ui.issue(s.trim(), true));
                e.addView(ui.caption("راهنما: پس از اصلاح اطلاعات (شناسه کالا، مشخصات خریدار، …) سند را از فهرست دوباره بفرستید؛ شماره مالیاتی جدید ساخته می‌شود."));
                page.addView(e);
            }
            if (l.warnings != null && !l.warnings.isEmpty()) {
                LinearLayout w = ui.softCard(MeelanoTaxUi.WARN_SOFT, 0xFFF0D9AE);
                w.addView(ui.heading("هشدارها"));
                for (String s : l.warnings.split("\n")) if (!s.trim().isEmpty()) w.addView(ui.issue(s.trim(), false));
                page.addView(w);
            }
            LinearLayout a = ui.card();
            a.addView(ui.heading("عملیات"));
            if (l.pending()) a.addView(ui.button("استعلام نتیجه", R.drawable.mi_sync, MeelanoTaxUi.BTN_PRIMARY, v -> inquireOpen(false)));
            if (l.taxid != null) a.addView(ui.button("کپی شماره مالیاتی", R.drawable.mi_content_copy, MeelanoTaxUi.BTN_GHOST, v -> copy("taxid", l.taxid)));
            if (l.accepted() && l.taxid != null && (l.ins == MeelanoTaxInvoice.INS_ORIGINAL || l.ins == MeelanoTaxInvoice.INS_CORRECTION))
                a.addView(ui.button("ثبت پرداخت (تسویه) در سامانه", R.drawable.mi_payments, MeelanoTaxUi.BTN_GHOST, v -> openPayment(l.taxid, l.tbill)));
            if ("FAILED".equals(l.status) || "LOCAL_ERROR".equals(l.status) || "NOT_FOUND".equals(l.status) || "TIMEOUT".equals(l.status)) {
                a.addView(ui.button("کنار گذاشتن این مورد", R.drawable.mi_delete, MeelanoTaxUi.BTN_DANGER, v -> ui.confirm("کنار گذاشتن",
                        "این سابقه ناموفق از فهرست خطاها خارج می‌شود (حذف نمی‌شود).", "کنار بگذار", () -> db("…", c -> { MeelanoTaxDb.deleteDraft(c, l.id); return true; }, x -> refresh()))));
            }
            a.addView(ui.button(MeelanoTaxDb.SALE.equals(l.docKind) ? "رفتن به فاکتور فروش" : "رفتن به سند", R.drawable.mi_receipt_long, MeelanoTaxUi.BTN_SECONDARY, v -> {
                if (MeelanoTaxDb.SALE.equals(l.docKind)) push(() -> renderSaleDetail(l.docNo));
                else push(() -> renderBackDetail(l.backKind, l.docNo));
            }));
            if (l.payload != null) a.addView(ui.button("مشاهده متن ارسال‌شده (JSON)", R.drawable.mi_description, MeelanoTaxUi.BTN_GHOST, v -> showJson(l.payload)));
            page.addView(a);
            setScreen("جزئیات ارسال", l.taxid == null ? "" : l.taxid, ui.scroll(page), null);
        });
    }

    // ================================================================== بیشتر

    private void renderMore() {
        LinearLayout page = ui.page();
        page.addView(ui.heading("اطلاعات پایه مالیاتی"));
        page.addView(statGrid(
                tile("شناسه کالاها", "شناسه ۱۳ رقمی، شرح و نرخ", R.drawable.mi_inventory_2, v -> push(this::renderGoods)),
                tile("مشخصات خریداران", "نوع شخص، کد ملی، اقتصادی", R.drawable.mi_group, v -> push(this::renderBuyers))));
        page.addView(statGrid(
                tile("واحدهای اندازه‌گیری", "تطبیق با کد رسمی واحد", R.drawable.mi_rule, v -> push(this::renderUnits)),
                tile("استعلام بازه‌ای", "نتایج یک بازه زمانی", R.drawable.mi_calendar_month, v -> push(this::renderInquiryRange))));
        page.addView(ui.heading("راه‌اندازی و امنیت"));
        page.addView(statGrid(
                tile("راه‌اندازی اولیه", "مشخصات مودی و قواعد ارسال", R.drawable.mi_tune, v -> push(this::renderSetup)),
                tile("کلید و گواهی امضا", "ساخت کلید، CSR، ورود گواهی", R.drawable.mi_key, v -> push(this::renderKeys))));
        page.addView(statGrid(
                tile("پشتیبان کلید", "رمزدار در پایگاه داده یا فایل", R.drawable.mi_cloud_upload, v -> push(this::renderBackup)),
                tile("اتصال پایگاه داده", "سرور و پایگاه داده آتیران", R.drawable.mi_database, v -> push(this::renderDbSettings))));
        page.addView(statGrid(
                tile("راهنمای سامانه", "مراحل، قواعد و خطاها", R.drawable.mi_help, v -> push(this::renderHelp)),
                tile("خروج", userName(), R.drawable.mi_logout, v -> logout())));
        TextView dev = ui.caption(getString(R.string.app_name) + " • نسخه " + versionName() + " • توسعه: Milad Yaghoobi");
        dev.setGravity(Gravity.CENTER);
        dev.setPadding(0, ui.dp(12), 0, 0);
        page.addView(dev, MeelanoTaxUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setScreen("بیشتر", "بخش‌های تکمیلی", ui.scroll(page), null);
    }

    private View tile(String title, String sub, int icon, View.OnClickListener click) {
        LinearLayout c = ui.v();
        c.setBackground(ui.pressable(ui.round(MeelanoTaxUi.SURFACE, 16, MeelanoTaxUi.LINE, 1), 0x220B6A70));
        c.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        LinearLayout ib = ui.h();
        ib.setGravity(Gravity.CENTER);
        ib.setBackground(ui.round(MeelanoTaxUi.PRIMARY_SOFT, 12, 0, 0));
        ib.addView(ui.icon(icon, MeelanoTaxUi.PRIMARY, 22));
        c.addView(ib, MeelanoTaxUi.lp(ui.dp(40), ui.dp(40)));
        TextView t = ui.text(title, 14, MeelanoTaxUi.TEXT, true);
        t.setPadding(0, ui.dp(8), 0, 0);
        c.addView(t);
        c.addView(ui.caption(sub));
        c.setClickable(true);
        c.setOnClickListener(click);
        c.setMinimumHeight(ui.dp(118));
        return c;
    }

    // ================================================================== شناسه کالاها

    private String goodsQuery = "";
    private boolean goodsMissingOnly = true;
    private final Set<Integer> goodsPicked = new LinkedHashSet<>();

    private void renderGoods() {
        load("شناسه کالاها", null, c -> MeelanoTaxDb.goods(c, goodsQuery, goodsMissingOnly, 1500), list -> {
            LinearLayout page = ui.page();
            LinearLayout info = ui.softCard(MeelanoTaxUi.PRIMARY_SOFT, 0xFFC4E2E0);
            info.addView(ui.body("هر کالا در صورتحساب مودیان باید «شناسه ۱۳ رقمی کالا/خدمت» داشته باشد. شناسه عمومی یا اختصاصی را از سامانه شناسه کالا پیدا کنید و اینجا ثبت کنید. کالاهای مشابه را می‌توانید گروهی انتخاب و یک‌جا ثبت کنید."));
            info.addView(ui.button("جستجوی شناسه در سامانه stuffid.tax.gov.ir", R.drawable.mi_search, MeelanoTaxUi.BTN_GHOST, v -> openUrl("https://stuffid.tax.gov.ir")));
            page.addView(info);
            LinearLayout f = ui.card();
            EditText q = ui.input("نام یا کد کالا", goodsQuery, InputType.TYPE_CLASS_TEXT);
            f.addView(q);
            f.addView(ui.button("جستجو", R.drawable.mi_search, MeelanoTaxUi.BTN_SECONDARY, v -> { goodsQuery = q.getText().toString().trim(); refresh(); }));
            page.addView(f);
            page.addView(ui.chipRow(ui.chip("فقط کالاهای بدون شناسه", goodsMissingOnly, v -> { goodsMissingOnly = true; refresh(); }),
                    ui.chip("همه کالاها", !goodsMissingOnly, v -> { goodsMissingOnly = false; refresh(); })));
            for (MeelanoTaxDb.Good g : list) page.addView(goodRow(g));
            if (list.isEmpty()) page.addView(ui.caption(goodsMissingOnly ? "همه کالاها شناسه دارند." : "کالایی پیدا نشد."));
            View footer = null;
            if (!goodsPicked.isEmpty()) {
                LinearLayout ft = ui.h();
                ft.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
                ft.addView(ui.button("ثبت شناسه برای " + fa(goodsPicked.size()) + " کالا", R.drawable.mi_edit, MeelanoTaxUi.BTN_PRIMARY, v -> bulkGoods(list)), MeelanoTaxUi.weight(1));
                footer = ft;
            }
            setScreen("شناسه کالاها", fa(list.size()) + " کالا", ui.scroll(page), footer);
        });
    }

    private View goodRow(MeelanoTaxDb.Good g) {
        LinearLayout c = ui.h();
        c.setGravity(Gravity.TOP);
        c.setBackground(ui.pressable(ui.round(MeelanoTaxUi.SURFACE, 14, MeelanoTaxUi.LINE, 1), 0x220B6A70));
        c.setPadding(ui.dp(8), ui.dp(10), ui.dp(12), ui.dp(10));
        CheckBox cb = new CheckBox(this);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(MeelanoTaxUi.PRIMARY));
        cb.setChecked(goodsPicked.contains(g.shka));
        cb.setOnCheckedChangeListener((b, on) -> { if (on) goodsPicked.add(g.shka); else goodsPicked.remove(g.shka); refresh(); });
        c.addView(cb);
        LinearLayout col = ui.v();
        col.addView(ui.text(g.name, 13.5f, MeelanoTaxUi.TEXT, true));
        String id = g.sstid != null ? g.sstid : g.atiranCode;
        LinearLayout r = ui.h();
        r.addView(ui.caption("کد " + fa(g.shka) + " • " + nz(g.unit) + " • "));
        r.addView(ui.ltr(id == null ? "بدون شناسه" : id, 12.5f, id == null ? MeelanoTaxUi.ERR : MeelanoTaxUi.PRIMARY_DARK, true));
        col.addView(r);
        BigDecimal rate = g.vra != null ? g.vra : g.atiranRate;
        col.addView(ui.caption("نرخ مالیات: " + MeelanoTaxUi.fa(rate == null ? "0" : MeelanoTaxJson.plain(rate)) + "٪" + (g.mu != null ? " • واحد: " + MeelanoTaxInvoice.unitName(g.mu) : "")
                + (g.soldLines > 0 ? " • " + fa(g.soldLines) + " ردیف فروش" : "")));
        c.addView(col, MeelanoTaxUi.weight(1));
        c.setClickable(true);
        c.setOnClickListener(v -> editGood(g));
        c.setLayoutParams(ui.full(0, 6));
        return c;
    }

    private void openGoodEditor(int shka) {
        db("…", c -> {
            for (MeelanoTaxDb.Good g : MeelanoTaxDb.goods(c, String.valueOf(shka), false, 5)) if (g.shka == shka) return g;
            throw new IllegalArgumentException("کالا پیدا نشد.");
        }, this::editGood);
    }

    private void editGood(MeelanoTaxDb.Good g) {
        LinearLayout body = ui.v();
        body.addView(ui.caption(g.name + " (کد " + fa(g.shka) + ")"));
        EditText id = ui.ltrInput("۱۳ رقم", g.sstid != null ? g.sstid : nz(g.atiranCode), InputType.TYPE_CLASS_NUMBER);
        body.addView(ui.field("شناسه کالا/خدمت", id, "شناسه عمومی یا اختصاصی ثبت‌شده در سامانه شناسه کالا"));
        EditText sstt = ui.input(g.name, nz(g.sstt), InputType.TYPE_CLASS_TEXT);
        body.addView(ui.field("شرح کالا در صورتحساب", sstt, "خالی = نام کالا در آتیران"));
        final String[] mu = {g.mu};
        TextView muv = ui.body(unitLabel(mu[0], g.unit));
        muv.setBackground(ui.round(MeelanoTaxUi.SURFACE_2, 12, MeelanoTaxUi.LINE, 1));
        muv.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        muv.setOnClickListener(v -> pickUnit(code -> { mu[0] = code; muv.setText(unitLabel(code, g.unit)); }));
        body.addView(ui.field("واحد اندازه‌گیری", muv, "خالی = طبق تطبیق واحد «" + nz(g.unit) + "»"));
        EditText vra = ui.ltrInput(g.atiranRate == null ? "" : MeelanoTaxJson.plain(g.atiranRate), g.vra == null ? "" : MeelanoTaxJson.plain(g.vra), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        body.addView(ui.field("نرخ مالیات بر ارزش افزوده (٪)", vra, "خالی = نرخ آتیران (" + MeelanoTaxUi.fa(g.atiranRate == null ? "0" : MeelanoTaxJson.plain(g.atiranRate)) + "٪)"));
        ui.form("شناسه مالیاتی کالا", body, "ذخیره", () -> {
            String s = MeelanoTaxInvoice.digits(id.getText().toString());
            if (s != null && s.length() != 13) { ui.message("شناسه کالا", "شناسه کالا باید دقیقاً ۱۳ رقم باشد."); return false; }
            BigDecimal rate = null;
            String rv = MeelanoTaxDb.foldDigits(vra.getText().toString().trim()).replace('٫', '.');
            if (!rv.isEmpty()) {
                try { rate = new BigDecimal(rv); } catch (Exception e) { ui.message("نرخ مالیات", "نرخ مالیات عدد نیست."); return false; }
                if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) { ui.message("نرخ مالیات", "نرخ باید بین ۰ تا ۱۰۰ باشد."); return false; }
            }
            g.sstid = s;
            g.sstt = sstt.getText().toString().trim();
            g.mu = mu[0];
            g.vra = rate;
            boolean writeAtiran = "1".equals(settings.get("write_atiran_ids"));
            db("در حال ذخیره…", c -> { MeelanoTaxDb.saveGood(c, g, writeAtiran, userName()); return true; }, x -> { toast("ذخیره شد"); refresh(); });
            return true;
        }, "انصراف");
    }

    private void bulkGoods(List<MeelanoTaxDb.Good> list) {
        LinearLayout body = ui.v();
        body.addView(ui.caption("این شناسه برای " + fa(goodsPicked.size()) + " کالای انتخاب‌شده ثبت می‌شود (شرح و نرخ هر کالا تغییر نمی‌کند)."));
        EditText id = ui.ltrInput("۱۳ رقم", "", InputType.TYPE_CLASS_NUMBER);
        body.addView(ui.field("شناسه کالا/خدمت", id, null));
        ui.form("ثبت گروهی شناسه", body, "ثبت", () -> {
            String s = MeelanoTaxInvoice.digits(id.getText().toString());
            if (s == null || s.length() != 13) { ui.message("شناسه کالا", "شناسه کالا باید دقیقاً ۱۳ رقم باشد."); return false; }
            List<MeelanoTaxDb.Good> pick = new ArrayList<>();
            for (MeelanoTaxDb.Good g : list) if (goodsPicked.contains(g.shka)) pick.add(g);
            boolean writeAtiran = "1".equals(settings.get("write_atiran_ids"));
            db("در حال ثبت…", c -> {
                for (MeelanoTaxDb.Good g : pick) { g.sstid = s; MeelanoTaxDb.saveGood(c, g, writeAtiran, userName()); }
                return pick.size();
            }, n -> { goodsPicked.clear(); toast(fa(n) + " کالا ثبت شد"); refresh(); });
            return true;
        }, "انصراف");
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { ui.message("مرورگر", "مرورگری روی گوشی پیدا نشد."); }
    }

    // ================================================================== واحدها

    interface UnitPicked { void picked(String code); }

    private String unitLabel(String code, String atiranUnit) {
        if (code == null || code.isEmpty()) return "طبق تطبیق واحد «" + nz(atiranUnit) + "»";
        return MeelanoTaxInvoice.unitName(code) + " (" + MeelanoTaxUi.fa(code) + ")";
    }

    private void pickUnit(UnitPicked cb) {
        LinearLayout body = ui.v();
        EditText q = ui.input("جستجوی واحد", "", InputType.TYPE_CLASS_TEXT);
        body.addView(q, ui.full(0, 8));
        LinearLayout list = ui.v();
        body.addView(list);
        final Dialog[] dlg = new Dialog[1];
        Runnable draw = () -> {
            list.removeAllViews();
            String s = MeelanoTaxInvoice.normName(q.getText().toString());
            TextView none = ui.body("— بدون تعیین (طبق تطبیق واحد)");
            none.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10));
            none.setOnClickListener(v -> { cb.picked(null); if (dlg[0] != null) dlg[0].dismiss(); });
            list.addView(none);
            for (Map.Entry<String, String> e : MeelanoTaxInvoice.UNITS.entrySet()) {
                if (!s.isEmpty() && !MeelanoTaxInvoice.normName(e.getValue()).contains(s) && !e.getKey().contains(s)) continue;
                TextView t = ui.body(e.getValue() + "  (" + MeelanoTaxUi.fa(e.getKey()) + ")");
                t.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10));
                t.setBackground(ui.pressable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), 0x220B6A70));
                t.setOnClickListener(v -> { cb.picked(e.getKey()); if (dlg[0] != null) dlg[0].dismiss(); });
                list.addView(t);
            }
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { draw.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        draw.run();
        dlg[0] = ui.sheet("انتخاب واحد رسمی", body, null, null, "بستن");
    }

    private void renderUnits() {
        load("واحدهای اندازه‌گیری", null, c -> {
            Object[] r = new Object[2];
            r[0] = MeelanoTaxDb.productUnits(c);
            r[1] = MeelanoTaxDb.unitMap(c);
            return r;
        }, r -> {
            @SuppressWarnings("unchecked") List<String> units = (List<String>) r[0];
            @SuppressWarnings("unchecked") Map<String, String> map = (Map<String, String>) r[1];
            LinearLayout page = ui.page();
            LinearLayout info = ui.softCard(MeelanoTaxUi.PRIMARY_SOFT, 0xFFC4E2E0);
            info.addView(ui.body("واحدهای کالا در آتیران باید با «کد رسمی واحد اندازه‌گیری» سامانه مودیان تطبیق داده شوند. واحدهایی که کد دارند خودکار تطبیق شده‌اند؛ برای بقیه روی واحد بزنید و کد رسمی را انتخاب کنید."));
            page.addView(info);
            for (String u : units) {
                String code = MeelanoTaxInvoice.unitCode(u, map);
                page.addView(listCard(u, code == null ? "بدون کد رسمی — لمس کنید" : MeelanoTaxInvoice.unitName(code) + " (" + MeelanoTaxUi.fa(code) + ")",
                        ui.badge(code == null ? "ناقص" : "تطبیق شده", code == null ? "err" : "ok"),
                        v -> pickUnit(picked -> db("در حال ذخیره…", c -> { MeelanoTaxDb.saveUnit(c, u, picked); return true; }, x -> refresh()))));
            }
            setScreen("واحدهای اندازه‌گیری", fa(units.size()) + " واحد", ui.scroll(page), null);
        });
    }

    // ================================================================== خریداران

    private String buyersQuery = "";
    private boolean buyersWithSales = true;

    private void renderBuyers() {
        load("مشخصات خریداران", null, c -> MeelanoTaxDb.buyers(c, buyersQuery, buyersWithSales, 1200), list -> {
            LinearLayout page = ui.page();
            LinearLayout info = ui.softCard(MeelanoTaxUi.PRIMARY_SOFT, 0xFFC4E2E0);
            info.addView(ui.body("در صورتحساب «نوع اول»، خریدار حقیقی به کد ملی و کد پستی (یا شماره اقتصادی) و خریدار حقوقی به شماره اقتصادی/شناسه ملی نیاز دارد. خریدارانی که این اطلاعات را ندارند با «نوع دوم» فرستاده می‌شوند (قابل تغییر در راه‌اندازی)."));
            page.addView(info);
            LinearLayout f = ui.card();
            EditText q = ui.input("نام یا کد مشتری", buyersQuery, InputType.TYPE_CLASS_TEXT);
            f.addView(q);
            f.addView(ui.button("جستجو", R.drawable.mi_search, MeelanoTaxUi.BTN_SECONDARY, v -> { buyersQuery = q.getText().toString().trim(); refresh(); }));
            page.addView(f);
            page.addView(ui.chipRow(ui.chip("فقط دارای فاکتور", buyersWithSales, v -> { buyersWithSales = true; refresh(); }),
                    ui.chip("همه مشتریان", !buyersWithSales, v -> { buyersWithSales = false; refresh(); })));
            for (MeelanoTaxDb.BuyerRow b : list) {
                boolean ids = MeelanoTaxInvoice.digits(b.tinb) != null || (MeelanoTaxInvoice.digits(b.bid) != null && MeelanoTaxInvoice.digits(b.bpc) != null);
                String sub = MeelanoTaxInvoice.tobTitle(b.tob) + " • کد " + fa(b.shmo) + (b.invoices > 0 ? " • " + fa(b.invoices) + " فاکتور" : "")
                        + (b.bid != null ? "\nکد ملی: " + MeelanoTaxUi.fa(b.bid) : "") + (b.tinb != null ? "\nاقتصادی: " + MeelanoTaxUi.fa(b.tinb) : "") + (b.bpc != null ? "\nکد پستی: " + MeelanoTaxUi.fa(b.bpc) : "");
                page.addView(listCard(b.name, sub, ui.badge(ids ? "نوع اول آماده" : "فقط نوع دوم", ids ? "ok" : "idle"), v -> editBuyer(b)));
            }
            setScreen("مشخصات خریداران", fa(list.size()) + " مشتری", ui.scroll(page), null);
        });
    }

    private void openBuyerEditor(long shmo) {
        db("…", c -> {
            for (MeelanoTaxDb.BuyerRow b : MeelanoTaxDb.buyers(c, String.valueOf(shmo), false, 5)) if (b.shmo == shmo) return b;
            throw new IllegalArgumentException("مشتری پیدا نشد.");
        }, this::editBuyer);
    }

    private void editBuyer(MeelanoTaxDb.BuyerRow b) {
        LinearLayout body = ui.v();
        body.addView(ui.caption(b.name + " (کد " + fa(b.shmo) + ")"));
        final int[] tob = {b.tob == null ? 0 : b.tob};
        final int[] inty = {b.inty == null ? 0 : b.inty};
        body.addView(ui.text("نوع شخص", 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        LinearLayout tobRow = ui.v();
        body.addView(tobRow);
        body.addView(ui.text("نوع صورتحساب", 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        LinearLayout intyRow = ui.v();
        body.addView(intyRow);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            tobRow.removeAllViews();
            List<View> a = new ArrayList<>();
            for (int k = 1; k <= 5; k++) { final int kk = k; a.add(ui.chip(MeelanoTaxInvoice.tobTitle(k), tob[0] == k, v -> { tob[0] = kk; draw[0].run(); })); }
            tobRow.addView(ui.chipRow(a.toArray(new View[0])));
            intyRow.removeAllViews();
            intyRow.addView(ui.chipRow(ui.chip("خودکار", inty[0] == 0, v -> { inty[0] = 0; draw[0].run(); }),
                    ui.chip("نوع اول", inty[0] == 1, v -> { inty[0] = 1; draw[0].run(); }),
                    ui.chip("نوع دوم", inty[0] == 2, v -> { inty[0] = 2; draw[0].run(); })));
        };
        draw[0].run();
        EditText bid = ui.ltrInput("۱۰ رقم (حقیقی) یا ۱۱ رقم (حقوقی)", nz(b.bid), InputType.TYPE_CLASS_NUMBER);
        EditText tinb = ui.ltrInput("۱۴ رقم", nz(b.tinb), InputType.TYPE_CLASS_NUMBER);
        EditText bpc = ui.ltrInput("۱۰ رقم", nz(b.bpc), InputType.TYPE_CLASS_NUMBER);
        EditText bbc = ui.ltrInput("اختیاری", nz(b.bbc), InputType.TYPE_CLASS_NUMBER);
        body.addView(ui.field("کد ملی / شناسه ملی", bid, null));
        body.addView(ui.field("شماره اقتصادی", tinb, null));
        body.addView(ui.field("کد پستی", bpc, null));
        body.addView(ui.field("کد شعبه خریدار", bbc, null));
        ui.form("مشخصات مالیاتی خریدار", body, "ذخیره", () -> {
            String vb = MeelanoTaxInvoice.digits(bid.getText().toString()), vt = MeelanoTaxInvoice.digits(tinb.getText().toString());
            String vp = MeelanoTaxInvoice.digits(bpc.getText().toString()), vbb = MeelanoTaxInvoice.digits(bbc.getText().toString());
            if (vb != null && !(vb.length() == 10 || vb.length() == 11 || vb.length() == 12)) { ui.message("کد ملی", "کد ملی ۱۰ رقم و شناسه ملی ۱۱ رقم است."); return false; }
            if (vb != null && vb.length() == 10 && !MeelanoTaxInvoice.nationalCodeValid(vb)) { ui.message("کد ملی", "رقم کنترل کد ملی درست نیست؛ دوباره بررسی کنید."); return false; }
            if (vt != null && !(vt.length() == 14 || vt.length() == 11 || vt.length() == 10)) { ui.message("شماره اقتصادی", "شماره اقتصادی ۱۴ رقم است (یا شناسه ملی ۱۱ رقمی)."); return false; }
            if (vp != null && vp.length() != 10) { ui.message("کد پستی", "کد پستی باید ۱۰ رقم باشد."); return false; }
            b.tob = tob[0] == 0 ? null : tob[0];
            b.inty = inty[0] == 0 ? null : inty[0];
            b.bid = vb;
            b.tinb = vt;
            b.bpc = vp;
            b.bbc = vbb;
            boolean writeAtiran = "1".equals(settings.get("write_atiran_ids"));
            db("در حال ذخیره…", c -> { MeelanoTaxDb.saveBuyer(c, b, writeAtiran, userName()); return true; }, x -> { toast("ذخیره شد"); refresh(); });
            return true;
        }, "انصراف");
    }

    // ================================================================== استعلام بازه‌ای

    private String inqFrom = today(), inqTo = today(), inqStatus = "";
    private List<MeelanoTaxApi.Inquiry> inqResult;

    private void renderInquiryRange() {
        LinearLayout page = ui.page();
        LinearLayout f = ui.card();
        f.addView(ui.caption("نتیجه همه صورتحساب‌های ارسالی در یک بازه (حداکثر ۷ روز) را از سامانه می‌گیرد و وضعیت سوابق در جریان را به‌روز می‌کند."));
        EditText from = ui.ltrInput("۱۴۰۵/۰۷/۰۱", inqFrom, InputType.TYPE_CLASS_DATETIME);
        EditText to = ui.ltrInput("۱۴۰۵/۰۷/۰۷", inqTo, InputType.TYPE_CLASS_DATETIME);
        LinearLayout dates = ui.h();
        LinearLayout.LayoutParams p1 = MeelanoTaxUi.weight(1);
        p1.setMarginEnd(ui.dp(6));
        dates.addView(ui.field("از تاریخ", from, null), p1);
        dates.addView(ui.field("تا تاریخ", to, null), MeelanoTaxUi.weight(1));
        f.addView(dates);
        String[][] sts = {{"", "همه"}, {"SUCCESS", "موفق"}, {"FAILED", "رد شده"}, {"IN_PROGRESS", "در صف"}, {"TIMEOUT", "پایان مهلت"}};
        List<View> chips = new ArrayList<>();
        for (String[] s : sts) chips.add(ui.chip(s[1], s[0].equals(inqStatus), v -> { inqStatus = s[0]; inqFrom = from.getText().toString(); inqTo = to.getText().toString(); refresh(); }));
        f.addView(ui.chipRow(chips.toArray(new View[0])));
        f.addView(ui.button("استعلام", R.drawable.mi_sync, MeelanoTaxUi.BTN_PRIMARY, v -> {
            inqFrom = MeelanoTaxDb.foldDigits(from.getText().toString().trim());
            inqTo = MeelanoTaxDb.foldDigits(to.getText().toString().trim());
            long start = MeelanoTaxInvoice.tehranMillis(inqFrom, "00:00"), end = MeelanoTaxInvoice.tehranMillis(inqTo, "23:59:59");
            if (start <= 0 || end <= 0) { ui.message("تاریخ", "تاریخ را به شکل ۱۴۰۵/۰۷/۰۱ وارد کنید."); return; }
            end = Math.min(end, System.currentTimeMillis());
            if (end < start) { ui.message("تاریخ", "ابتدای بازه باید قبل از انتهای آن باشد."); return; }
            if (end - start > 7L * 86_400_000L) { ui.message("تاریخ", "بازه استعلام حداکثر ۷ روز است."); return; }
            final MeelanoTaxApi api;
            try { api = api(); } catch (Exception e) { showError(e); return; }
            final long s0 = start, e0 = end;
            MeelanoTaxEngine.Config cf = cfg();
            db("در حال استعلام…", c -> {
                List<MeelanoTaxApi.Inquiry> all = new ArrayList<>();
                for (int page2 = 1; page2 <= 10; page2++) {
                    List<MeelanoTaxApi.Inquiry> part = api.inquiryByTime(s0, e0, inqStatus, page2, 100);
                    all.addAll(part);
                    if (part.size() < 100) break;
                }
                MeelanoTaxEngine.applyResults(c, cf, all);
                return all;
            }, res -> { inqResult = res; refresh(); });
        }));
        page.addView(f);
        if (inqResult != null) {
            LinearLayout rc = ui.card();
            rc.addView(ui.heading("نتیجه: " + fa(inqResult.size()) + " صورتحساب"));
            for (MeelanoTaxApi.Inquiry q : inqResult) {
                LinearLayout r = ui.v();
                r.setPadding(0, ui.dp(6), 0, ui.dp(6));
                LinearLayout top = ui.h();
                top.addView(ui.ltr(nz(q.taxId != null ? q.taxId : q.referenceNumber), 12, MeelanoTaxUi.TEXT, true), MeelanoTaxUi.weight(1));
                top.addView(ui.badge(statusFa(q.status), statusTone(q.status)));
                r.addView(top);
                for (String[] e : q.errors) r.addView(ui.issue(e[1] + (e[0].isEmpty() ? "" : " (کد " + e[0] + ")"), true));
                rc.addView(r);
                rc.addView(ui.divider());
            }
            page.addView(rc);
        }
        setScreen("استعلام بازه‌ای", "حداکثر ۷ روز", ui.scroll(page), null);
    }

    // ================================================================== ثبت پرداخت

    private void openPayment(String taxid, long amount) {
        LinearLayout body = ui.v();
        body.addView(ui.rowLtr("شماره مالیاتی", taxid));
        EditText am = ui.ltrInput("ریال", String.valueOf(amount), InputType.TYPE_CLASS_NUMBER);
        body.addView(ui.field("مبلغ پرداخت‌شده (ریال)", am, null));
        EditText date = ui.ltrInput("۱۴۰۵/۰۷/۰۱", today(), InputType.TYPE_CLASS_DATETIME);
        body.addView(ui.field("تاریخ پرداخت", date, null));
        final String[] method = {"CASH"};
        String[][] ms = {{"CASH", "نقد"}, {"POS", "کارتخوان"}, {"CARD", "کارت به کارت"}, {"TRANSFER", "حواله/انتقال"}, {"INTERNET", "درگاه اینترنتی"}, {"CHEQUE", "چک"}, {"BARTER", "تهاتر"}, {"OTHER", "سایر"}};
        LinearLayout mrow = ui.v();
        body.addView(ui.text("روش پرداخت", 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        body.addView(mrow);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            mrow.removeAllViews();
            List<View> a = new ArrayList<>();
            for (String[] m : ms) a.add(ui.chip(m[1], m[0].equals(method[0]), v -> { method[0] = m[0]; draw[0].run(); }));
            mrow.addView(ui.chipRow(a.toArray(new View[0])));
        };
        draw[0].run();
        EditText term = ui.ltrInput("اختیاری", "", InputType.TYPE_CLASS_TEXT);
        EditText ref = ui.ltrInput("اختیاری", "", InputType.TYPE_CLASS_TEXT);
        body.addView(ui.field("شماره پایانه", term, null));
        body.addView(ui.field("شماره پیگیری پرداخت", ref, null));
        ui.form("ثبت پرداخت صورتحساب", body, "ثبت در سامانه", () -> {
            long a = MeelanoTaxEngine.parseLong(am.getText().toString(), -1);
            long when = MeelanoTaxInvoice.tehranMillis(MeelanoTaxDb.foldDigits(date.getText().toString().trim()), "12:00");
            if (a <= 0) { ui.message("پرداخت", "مبلغ پرداخت را درست وارد کنید."); return false; }
            if (when <= 0) { ui.message("پرداخت", "تاریخ پرداخت را به شکل ۱۴۰۵/۰۷/۰۱ وارد کنید."); return false; }
            final MeelanoTaxApi api;
            try { api = api(); } catch (Exception e) { showError(e); return false; }
            long w = Math.min(when, System.currentTimeMillis());
            work("در حال ثبت پرداخت…", () -> api.registerPayment(taxid, a, w, method[0], term.getText().toString().trim(), ref.getText().toString().trim()), m -> {
                String st = MeelanoTaxJson.str(m, "requestStatus");
                List<Object> errs = MeelanoTaxJson.arr(m, "error");
                StringBuilder b = new StringBuilder("SUCCESS".equals(st) ? "پرداخت در سامانه ثبت شد." : "ثبت پرداخت پذیرفته نشد.");
                if (errs != null) for (Object o : errs) {
                    Map<String, Object> e = MeelanoTaxJson.obj(o);
                    b.append("\n• ").append(e == null ? String.valueOf(o) : nz(MeelanoTaxJson.str(e, "message")) + " " + nz(MeelanoTaxJson.str(e, "code")));
                }
                ui.message("ثبت پرداخت", b.toString());
            });
            return true;
        }, "انصراف");
    }

    // ================================================================== راه‌اندازی اولیه

    private static final class SetupInfo {
        String server;
        List<String> missing = new ArrayList<>();
        List<String[]> users = new ArrayList<>();
        long lastSerial;
    }

    private void renderSetup() {
        load("راه‌اندازی اولیه", "مشخصات مودی و قواعد ارسال", c -> {
            SetupInfo s = new SetupInfo();
            s.server = MeelanoTaxDb.serverVersion(c);
            s.missing = MeelanoTaxDb.missingTables(c);
            try { s.users = MeelanoTaxDb.sysUsers(c); } catch (Exception ignored) { }
            s.lastSerial = MeelanoTaxDb.lastSerial(c, setting("memory_id"));
            settings = MeelanoTaxDb.settings(c);
            company = MeelanoTaxDb.company(c);
            return s;
        }, this::setupView);
    }

    private void setupView(SetupInfo info) {
        LinearLayout page = ui.page();
        LinearLayout intro = ui.softCard(MeelanoTaxUi.PRIMARY_SOFT, 0xFFC4E2E0);
        intro.addView(ui.body("برای ارسال صورتحساب به سامانه مودیان این مراحل را به ترتیب کامل کنید:\n"
                + "۱. اتصال به پایگاه داده آتیران\n۲. مشخصات مودی (شناسه حافظه، شماره اقتصادی، شناسه ملی)\n۳. ساخت کلید و دریافت گواهی امضا، و ثبت آن در کارپوشه\n۴. قواعد ارسال و کاربران مجاز\n۵. آزمون اتصال به سامانه و پایان راه‌اندازی"));
        page.addView(intro);

        // 1. Database
        LinearLayout dbc = ui.card();
        dbc.addView(stepTitle(1, "پایگاه داده آتیران", info.missing.isEmpty()));
        String[] d = dbConfig();
        dbc.addView(ui.rowLtr("سرور", d[0] + ":" + d[1]));
        dbc.addView(ui.rowLtr("پایگاه داده", d[2]));
        dbc.addView(ui.rowLtr("نسخه SQL Server", info.server));
        if (info.missing.isEmpty()) dbc.addView(ui.checkLine("همه جدول‌های مورد نیاز آتیران موجود است", null, true, false));
        else dbc.addView(ui.issue("جدول‌های پیدا نشده: " + String.join("، ", info.missing), true));
        if (!networkNote.isEmpty()) dbc.addView(ui.caption(networkNote));
        dbc.addView(ui.button("تنظیم اتصال پایگاه داده", R.drawable.mi_database, MeelanoTaxUi.BTN_GHOST, v -> push(this::renderDbSettings)));
        page.addView(dbc);

        // 2. Taxpayer
        LinearLayout tp = ui.card();
        boolean tpOk = setting("memory_id").matches("[A-Z0-9]{6}") && (cfg().tins.length() == 14 || cfg().tins.length() == 11);
        tp.addView(stepTitle(2, "مشخصات مودی (فروشنده)", tpOk));
        final boolean[] companyType = {!"individual".equals(setting("taxpayer_type"))};
        LinearLayout typeRow = ui.v();
        tp.addView(ui.text("نوع مودی", 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        tp.addView(typeRow);
        Runnable[] drawType = new Runnable[1];
        drawType[0] = () -> {
            typeRow.removeAllViews();
            typeRow.addView(ui.chipRow(ui.chip("حقوقی (شرکت)", companyType[0], v -> { companyType[0] = true; drawType[0].run(); }),
                    ui.chip("حقیقی (شخص)", !companyType[0], v -> { companyType[0] = false; drawType[0].run(); })));
        };
        drawType[0].run();
        EditText mem = ui.ltrInput("مثال: A1B2C3", setting("memory_id"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText eco = ui.ltrInput("۱۴ رقم", setting("economic_code"), InputType.TYPE_CLASS_NUMBER);
        EditText nid = ui.ltrInput("۱۱ رقم (حقوقی) یا ۱۰ رقم (حقیقی)", setting("national_id"), InputType.TYPE_CLASS_NUMBER);
        EditText branch = ui.ltrInput("اختیاری", setting("branch_code"), InputType.TYPE_CLASS_NUMBER);
        EditText en = ui.ltrInput("Derakhshan Nuts", setting("english_name"), InputType.TYPE_CLASS_TEXT);
        tp.addView(ui.field("شناسه یکتای حافظه مالیاتی", mem, "۶ کاراکتر؛ از کارپوشه ← عضویت ← شناسه یکتای حافظه"));
        tp.addView(ui.field("شماره اقتصادی (شماره مالیاتی) فروشنده", eco, null));
        tp.addView(ui.field("شناسه ملی / کد ملی مودی", nid, "باید با شناسه داخل گواهی امضا یکی باشد"));
        tp.addView(ui.field("کد شعبه فروشنده", branch, null));
        tp.addView(ui.field("نام لاتین (برای گواهی امضا)", en, null));
        if (company.name != null || company.economicCode != null) {
            tp.addView(ui.caption("در آتیران: " + nz(company.name) + (company.economicCode != null ? " • اقتصادی " + MeelanoTaxUi.fa(company.economicCode) : "")
                    + (company.memoryId != null ? " • حافظه " + company.memoryId : "")));
            tp.addView(ui.button("برداشتن از اطلاعات شرکت در آتیران", R.drawable.mi_download, MeelanoTaxUi.BTN_GHOST, v -> {
                if (company.memoryId != null) mem.setText(company.memoryId.trim().toUpperCase(Locale.US));
                if (company.economicCode != null) eco.setText(nz(MeelanoTaxInvoice.digits(company.economicCode)));
                if (company.nationalId != null) nid.setText(nz(MeelanoTaxInvoice.digits(company.nationalId)));
                if (company.branch != null) branch.setText(nz(MeelanoTaxInvoice.digits(company.branch)));
            }));
        }
        page.addView(tp);

        // 3. Keys
        LinearLayout kc = ui.card();
        boolean keyOk = privateKey != null && certificate != null && MeelanoTaxCrypto.keyMatchesCertificate(privateKey, certificate);
        kc.addView(stepTitle(3, "کلید خصوصی و گواهی امضا", keyOk));
        kc.addView(ui.checkLine("کلید خصوصی روی این گوشی", privateKey == null ? "ندارد" : "دارد (رمزگذاری‌شده با کلید امن گوشی)", privateKey != null, false));
        kc.addView(ui.checkLine("گواهی امضای الکترونیکی", certificate == null ? "ندارد" : "معتبر تا " + certUntil(), certificate != null, false));
        if (privateKey != null && certificate != null && !keyOk) kc.addView(ui.issue("کلید خصوصی با گواهی جفت نیست.", true));
        kc.addView(ui.button("مدیریت کلید و گواهی", R.drawable.mi_key, MeelanoTaxUi.BTN_GHOST, v -> push(this::renderKeys)));
        page.addView(kc);

        // 4. Rules
        LinearLayout rc = ui.card();
        rc.addView(stepTitle(4, "قواعد ارسال", true));
        final boolean[] prod = {"PRODUCTION".equals(setting("env"))};
        LinearLayout envRow = ui.v();
        rc.addView(ui.text("محیط سامانه", 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        rc.addView(envRow);
        Runnable[] drawEnv = new Runnable[1];
        drawEnv[0] = () -> {
            envRow.removeAllViews();
            envRow.addView(ui.chipRow(ui.chip("آزمایشی (sandbox)", !prod[0], v -> { prod[0] = false; drawEnv[0].run(); }),
                    ui.chip("اصلی (رسمی)", prod[0], v -> ui.confirm("محیط اصلی", "در محیط اصلی هر ارسال رسمی است و در کارپوشه خریدار و حساب مالیاتی شما ثبت می‌شود. مطمئن هستید؟", "بله، محیط اصلی",
                            () -> { prod[0] = true; drawEnv[0].run(); }))));
        };
        drawEnv[0].run();
        EditText deadline = ui.ltrInput("۱۲", setting("deadline_days").isEmpty() ? "12" : setting("deadline_days"), InputType.TYPE_CLASS_NUMBER);
        rc.addView(ui.field("مهلت ارسال (روز)", deadline, "بعد از این مهلت، صورتحساب با «قاعده ارسال = ۱» (ماده ۹) فرستاده می‌شود"));
        EditText seed = ui.ltrInput("۱", setting("serial_seed").isEmpty() ? "1" : setting("serial_seed"), InputType.TYPE_CLASS_NUMBER);
        rc.addView(ui.field("شروع سریال صورتحساب", seed, "اگر قبلاً با برنامه دیگری با همین حافظه ارسال کرده‌اید، عددی بزرگ‌تر از آخرین سریال بگذارید. آخرین سریال این برنامه: " + (info.lastSerial > 0 ? fa(info.lastSerial) : "—")));
        final int[] setm = {(int) MeelanoTaxEngine.parseLong(setting("default_setm"), 0)};
        LinearLayout setmRow = ui.v();
        rc.addView(ui.text("روش تسویه پیش‌فرض (فقط نوع اول)", 12.5f, MeelanoTaxUi.PRIMARY_DARK, true));
        rc.addView(setmRow);
        Runnable[] drawSetm = new Runnable[1];
        drawSetm[0] = () -> {
            setmRow.removeAllViews();
            String[] n = {"تعیین نشود", "نقدی", "نسیه", "نقدی/نسیه"};
            List<View> a = new ArrayList<>();
            for (int k = 0; k <= 3; k++) { final int kk = k; a.add(ui.chip(n[k], setm[0] == k, v -> { setm[0] = kk; drawSetm[0].run(); })); }
            setmRow.addView(ui.chipRow(a.toArray(new View[0])));
        };
        drawSetm[0].run();
        CheckBox type2 = ui.check("خریدار بدون کد ملی/اقتصادی با «نوع دوم» فرستاده شود", !"0".equals(setting("type2_when_no_ids")));
        CheckBox wb = ui.check("شماره مالیاتی و وضعیت در جدول‌های مالیاتی خود آتیران هم ثبت شود", !"0".equals(setting("write_back")));
        CheckBox wa = ui.check("شناسه کالا و مشخصات خریدار در کارت کالا/مشتری آتیران هم نوشته شود", "1".equals(setting("write_atiran_ids")));
        CheckBox vpn = ui.check("در صورت روشن بودن VPN، ارتباط از شبکه مستقیم گوشی برقرار شود", !"0".equals(prefs.getString("vpn_bypass", "1")));
        rc.addView(type2);
        rc.addView(wb);
        rc.addView(wa);
        rc.addView(vpn);
        page.addView(rc);

        // 5. Users
        LinearLayout uc = ui.card();
        uc.addView(stepTitle(5, "کاربران مجاز", true));
        uc.addView(ui.caption("اگر هیچ کاربری انتخاب نشود، همه کاربران آتیران اجازه ورود دارند. کاربر فعلی همیشه انتخاب می‌ماند."));
        Set<String> allowed = new LinkedHashSet<>();
        for (String a : setting("allowed_users").split(",")) if (!a.trim().isEmpty()) allowed.add(normUser(a));
        List<CheckBox> userBoxes = new ArrayList<>();
        List<String> userNames = new ArrayList<>();
        for (String[] u : info.users) {
            if (u[1] == null || u[1].trim().isEmpty()) continue;
            boolean me = normUser(u[1]).equals(normUser(userName()));
            CheckBox cb = ui.check(u[1] + (me ? " (شما)" : "") + ("0".equals(u[2]) || "false".equalsIgnoreCase(nz(u[2])) ? " — غیرفعال" : ""), allowed.contains(normUser(u[1])) || (me && !allowed.isEmpty()));
            userBoxes.add(cb);
            userNames.add(u[1].trim());
            uc.addView(cb);
        }
        if (info.users.isEmpty()) uc.addView(ui.caption("فهرست کاربران آتیران خوانده نشد."));
        page.addView(uc);

        // 6. Test
        LinearLayout fc = ui.card();
        fc.addView(stepTitle(6, "آزمون و پایان راه‌اندازی", "1".equals(setting("setup_done"))));
        fc.addView(ui.caption("اتصال به سامانه، کلید عمومی سازمان، شناسه حافظه، شماره اقتصادی و جفت بودن کلید و گواهی بررسی می‌شود."));
        page.addView(fc);

        View.OnClickListener save = v -> {
            String m = mem.getText().toString().trim().toUpperCase(Locale.US);
            String e = nz(MeelanoTaxInvoice.digits(eco.getText().toString()));
            String n = nz(MeelanoTaxInvoice.digits(nid.getText().toString()));
            if (!m.isEmpty() && !m.matches("[A-Z0-9]{6}")) { ui.message("شناسه حافظه", "شناسه یکتای حافظه ۶ کاراکتر (حرف انگلیسی و عدد) است."); return; }
            if (!e.isEmpty() && !(e.length() == 14 || e.length() == 11)) { ui.message("شماره اقتصادی", "شماره اقتصادی باید ۱۴ رقم (یا شناسه ملی ۱۱ رقمی) باشد."); return; }
            if (!n.isEmpty() && !(n.length() == 11 || n.length() == 10)) { ui.message("شناسه ملی", "شناسه ملی ۱۱ رقم و کد ملی ۱۰ رقم است."); return; }
            int dl = (int) MeelanoTaxEngine.parseLong(deadline.getText().toString(), -1);
            if (dl < 1 || dl > 60) { ui.message("مهلت ارسال", "مهلت ارسال را بین ۱ تا ۶۰ روز وارد کنید."); return; }
            long sd = MeelanoTaxEngine.parseLong(seed.getText().toString(), -1);
            if (sd < 1 || sd > 0xFFFFFFFFFFL) { ui.message("سریال", "شروع سریال باید عددی مثبت باشد."); return; }
            Map<String, String> vals = new LinkedHashMap<>();
            vals.put("memory_id", m);
            vals.put("economic_code", e);
            vals.put("national_id", n);
            vals.put("branch_code", nz(MeelanoTaxInvoice.digits(branch.getText().toString())));
            vals.put("english_name", en.getText().toString().trim());
            vals.put("taxpayer_type", companyType[0] ? "company" : "individual");
            vals.put("env", prod[0] ? "PRODUCTION" : "SANDBOX");
            vals.put("deadline_days", String.valueOf(dl));
            vals.put("serial_seed", String.valueOf(sd));
            vals.put("default_setm", setm[0] == 0 ? "" : String.valueOf(setm[0]));
            vals.put("type2_when_no_ids", type2.isChecked() ? "1" : "0");
            vals.put("write_back", wb.isChecked() ? "1" : "0");
            vals.put("write_atiran_ids", wa.isChecked() ? "1" : "0");
            List<String> picked = new ArrayList<>();
            for (int i = 0; i < userBoxes.size(); i++) if (userBoxes.get(i).isChecked()) picked.add(userNames.get(i));
            if (!picked.isEmpty()) {
                boolean hasMe = false;
                for (String p : picked) if (normUser(p).equals(normUser(userName()))) hasMe = true;
                if (!hasMe) picked.add(userName());
            }
            vals.put("allowed_users", String.join(",", picked));
            prefs.edit().putString("vpn_bypass", vpn.isChecked() ? "1" : "0").apply();
            boolean finish = v.getTag() != null;
            putSettings(vals, () -> {
                if (finish) runSystemTest(() -> {
                    Map<String, String> done = new HashMap<>();
                    done.put("setup_done", "1");
                    putSettings(done, () -> ui.message("راه‌اندازی", "راه‌اندازی اولیه کامل شد. اکنون می‌توانید فاکتورها را به سامانه مودیان بفرستید." + (sandbox() ? "\n\nیادآوری: محیط فعلی آزمایشی است؛ بعد از آزمون موفق، محیط را روی «اصلی» بگذارید." : "")));
                });
                else refresh();
            });
        };
        LinearLayout footer = ui.h();
        footer.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        View saveBtn = ui.button("ذخیره", R.drawable.mi_save, MeelanoTaxUi.BTN_SECONDARY, save);
        View finishBtn = ui.button("ذخیره و آزمون نهایی", R.drawable.mi_verified_user, MeelanoTaxUi.BTN_PRIMARY, save);
        finishBtn.setTag("finish");
        footer.addView(ui.pair(saveBtn, finishBtn), MeelanoTaxUi.weight(1));
        setScreen("راه‌اندازی اولیه", "مشخصات مودی و قواعد ارسال", ui.scroll(page), footer);
    }

    private View stepTitle(int n, String title, boolean ok) {
        LinearLayout r = ui.h();
        TextView num = ui.text(fa(n), 13, ok ? Color.WHITE : MeelanoTaxUi.PRIMARY_DARK, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(ui.round(ok ? MeelanoTaxUi.OK : MeelanoTaxUi.PRIMARY_SOFT, 13, 0, 0));
        r.addView(num, MeelanoTaxUi.lp(ui.dp(26), ui.dp(26)));
        TextView t = ui.heading(title);
        t.setPadding(ui.dp(10), 0, 0, 0);
        r.addView(t, MeelanoTaxUi.weight(1));
        if (ok) r.addView(ui.icon(R.drawable.mi_task_alt, MeelanoTaxUi.OK, 20));
        r.setPadding(0, 0, 0, ui.dp(8));
        return r;
    }

    // ------------------------------------------------------------------ system test

    /** Runs the end-to-end check; {@code onSuccess} runs only when every essential step passed. */
    private void runSystemTest(Runnable onSuccess) {
        MeelanoTaxEngine.Config cf = cfg();
        String nationalId = setting("national_id");
        PrivateKey key = privateKey;
        X509Certificate cert = certificate;
        db("در حال آزمون اتصال به سامانه مودیان…", c -> {
            List<String[]> steps = new ArrayList<>();
            steps.add(new String[]{"پایگاه داده آتیران", nz(MeelanoTaxDb.serverVersion(c)), "1"});
            boolean memOk = cf.memoryId.matches("[A-Z0-9]{6}");
            steps.add(new String[]{"شناسه یکتای حافظه", memOk ? cf.memoryId : "نامعتبر", memOk ? "1" : "0"});
            steps.add(new String[]{"شماره اقتصادی", cf.tins.isEmpty() ? "ثبت نشده" : MeelanoTaxUi.fa(cf.tins), (cf.tins.length() == 14 || cf.tins.length() == 11) ? "1" : "0"});
            steps.add(new String[]{"کلید خصوصی", key == null ? "روی این گوشی نیست" : "آماده", key != null ? "1" : "0"});
            if (cert != null) {
                boolean valid = true;
                try { cert.checkValidity(); } catch (Exception e) { valid = false; }
                steps.add(new String[]{"اعتبار گواهی", valid ? "معتبر تا " + jdate(cert.getNotAfter().getTime()).split(" ")[0] : "منقضی یا هنوز معتبر نشده", valid ? "1" : "0"});
                String sn = nz(MeelanoTaxCrypto.subject(cert).get("SERIALNUMBER")).trim();
                boolean snOk = !nationalId.isEmpty() && sn.equals(nationalId);
                steps.add(new String[]{"شناسه ملی داخل گواهی", sn.isEmpty() ? "ندارد" : MeelanoTaxUi.fa(sn) + (snOk ? "" : " (با شناسه ملی تنظیمات یکی نیست)"), snOk ? "1" : "0"});
                if (MeelanoTaxCrypto.selfSigned(cert)) steps.add(new String[]{"صادرکننده گواهی", "گواهی خودامضا است و سامانه آن را نمی‌پذیرد", "0"});
                if (key != null) {
                    boolean match = MeelanoTaxCrypto.keyMatchesCertificate(key, cert);
                    steps.add(new String[]{"جفت بودن کلید و گواهی", match ? "درست" : "کلید با گواهی جفت نیست", match ? "1" : "0"});
                }
            } else steps.add(new String[]{"گواهی امضا", "وارد نشده", "0"});
            MeelanoTaxApi api = new MeelanoTaxApi(cf.sandbox, cf.memoryId, key, cert == null ? null : cert.getEncoded());
            try {
                MeelanoTaxApi.ServerKey sk = api.serverKey();
                steps.add(new String[]{"ارتباط با سامانه (" + (cf.sandbox ? "آزمایشی" : "اصلی") + ")", "کلید عمومی سازمان دریافت شد (" + nz(sk.id).substring(0, Math.min(8, nz(sk.id).length())) + "…)", "1"});
            } catch (MeelanoTaxApi.ApiException e) {
                steps.add(new String[]{"ارتباط با سامانه", e.persian(), "0"});
                return steps;
            }
            if (key != null && cert != null && memOk) {
                try {
                    Map<String, Object> f = api.fiscalInformation();
                    String owner = nz(MeelanoTaxJson.str(f, "nameTrade"));
                    String fs = nz(MeelanoTaxJson.str(f, "fiscalStatus"));
                    String eco = nz(MeelanoTaxJson.str(f, "economicCode"));
                    boolean active = fs.isEmpty() || "ACTIVE".equalsIgnoreCase(fs) || fs.contains("فعال");
                    steps.add(new String[]{"اطلاعات حافظه مالیاتی", owner + (fs.isEmpty() ? "" : " • وضعیت: " + fs) + (eco.isEmpty() ? "" : " • اقتصادی " + MeelanoTaxUi.fa(eco)), active ? "1" : "0"});
                    if (!eco.isEmpty() && !cf.tins.isEmpty() && !eco.equals(cf.tins))
                        steps.add(new String[]{"تطبیق شماره اقتصادی", "شماره اقتصادی حافظه (" + MeelanoTaxUi.fa(eco) + ") با تنظیمات یکی نیست", "0"});
                } catch (MeelanoTaxApi.ApiException e) {
                    steps.add(new String[]{"احراز هویت و اطلاعات حافظه", e.persian(), "0"});
                }
                if (!cf.tins.isEmpty()) {
                    try {
                        Map<String, Object> t = api.taxpayer(cf.tins);
                        String st = nz(MeelanoTaxJson.str(t, "taxpayerStatus"));
                        steps.add(new String[]{"وضعیت مودی", nz(MeelanoTaxJson.str(t, "nameTrade")) + (st.isEmpty() ? "" : " • " + st), "1"});
                    } catch (MeelanoTaxApi.ApiException e) {
                        steps.add(new String[]{"وضعیت مودی", e.persian(), "-1"});
                    }
                }
            }
            return steps;
        }, steps -> {
            LinearLayout body = ui.v();
            boolean ok = true;
            for (String[] s : steps) {
                boolean pass = "1".equals(s[2]);
                if ("0".equals(s[2])) ok = false;
                body.addView(ui.checkLine(s[0], s[1], pass, "-1".equals(s[2])));
            }
            if (ok) body.addView(ui.issue("همه بررسی‌ها موفق بود.", false));
            else body.addView(ui.caption("موارد قرمز را اصلاح کنید و دوباره آزمون بگیرید. برای خطای احراز هویت: گواهی یا کلید عمومی باید در کارپوشه ← عضویت ← «کلید عمومی/گواهی» برای همین حافظه ثبت شده باشد."));
            final boolean passed = ok;
            ui.sheet("نتیجه آزمون", body, passed && onSuccess != null ? "پایان راه‌اندازی" : "باشه", passed && onSuccess != null ? onSuccess : null, null);
        });
    }

    // ================================================================== کلید و گواهی

    private void renderKeys() {
        LinearLayout page = ui.page();
        LinearLayout st = ui.card();
        st.addView(ui.heading("وضعیت فعلی"));
        st.addView(ui.checkLine("کلید خصوصی روی این گوشی", privateKey == null ? "ندارد" : "دارد • اثر انگشت: " + keyFingerprint(), privateKey != null, false));
        if (certificate != null) {
            boolean match = privateKey != null && MeelanoTaxCrypto.keyMatchesCertificate(privateKey, certificate);
            st.addView(ui.checkLine("گواهی امضا", privateKey == null ? "کلید متناظر روی گوشی نیست" : (match ? "با کلید جفت است" : "با کلید جفت نیست"), match, privateKey == null));
            for (String s : MeelanoTaxCrypto.describeCertificate(certificate)) st.addView(ui.caption("• " + s));
            if (MeelanoTaxCrypto.selfSigned(certificate)) st.addView(ui.issue("این گواهی خودامضا است؛ گواهی باید از مرکز صدور گواهی مجاز گرفته شود.", true));
        } else st.addView(ui.checkLine("گواهی امضا", "وارد نشده", false, false));
        page.addView(st);

        LinearLayout a = ui.card();
        a.addView(ui.heading("روش اول: ساخت کلید روی گوشی و درخواست گواهی"));
        a.addView(ui.caption("کلید خصوصی روی همین گوشی ساخته و با کلید امن گوشی رمز می‌شود. فایل درخواست (CSR) را برای مرکز صدور گواهی (مثلاً gica.ir) بفرستید و گواهی صادرشده را با گزینه «ورود فایل گواهی» وارد کنید."));
        a.addView(ui.button("ساخت کلید و درخواست گواهی (CSR)", R.drawable.mi_key, MeelanoTaxUi.BTN_PRIMARY, v -> askCsr()));
        a.addView(ui.button("ورود فایل گواهی (crt / cer / pem)", R.drawable.mi_upload_file, MeelanoTaxUi.BTN_SECONDARY, v -> pickFile((data, name) -> importCertificateBytes(data))));
        page.addView(a);

        LinearLayout b = ui.card();
        b.addView(ui.heading("روش دوم: ورود کلید و گواهی موجود"));
        b.addView(ui.button("ورود فایل PFX / P12", R.drawable.mi_upload_file, MeelanoTaxUi.BTN_SECONDARY, v -> pickFile((data, name) -> askPfxPassword(data))));
        b.addView(ui.button("چسباندن متن کلید یا گواهی (PEM)", R.drawable.mi_content_paste, MeelanoTaxUi.BTN_GHOST, v -> askPem()));
        if (company.hasPrivateKey) b.addView(ui.button("برداشتن کلید خصوصی ثبت‌شده در آتیران", R.drawable.mi_download, MeelanoTaxUi.BTN_GHOST, v -> {
            try {
                PrivateKey k = MeelanoTaxCrypto.privateKeyFromPem(company.privateKeyText);
                storeKey(k);
                toast("کلید آتیران روی گوشی ذخیره شد");
                refresh();
            } catch (Exception e) { showError(e); }
        }));
        page.addView(b);

        if (privateKey != null) {
            LinearLayout c = ui.card();
            c.addView(ui.heading("کلید عمومی (برای ثبت در کارپوشه)"));
            c.addView(ui.caption("اگر در کارپوشه روش «کلید عمومی» را انتخاب کرده‌اید، این متن را در بخش عضویت ← کلید عمومی حافظه ثبت کنید."));
            c.addView(ui.pair(
                    ui.button("کپی", R.drawable.mi_content_copy, MeelanoTaxUi.BTN_GHOST, v -> copy("public key", publicPem())),
                    ui.button("اشتراک", R.drawable.mi_share, MeelanoTaxUi.BTN_GHOST, v -> shareText("کلید عمومی", publicPem()))));
            c.addView(ui.button("حذف کلید خصوصی از این گوشی", R.drawable.mi_delete, MeelanoTaxUi.BTN_DANGER, v -> ui.confirm("حذف کلید",
                    "بعد از حذف، تا کلید را دوباره وارد نکنید (از پشتیبان یا PFX) ارسال ممکن نیست. ادامه می‌دهید؟", "حذف", () -> {
                        try { MeelanoTaxVault.put(prefs, "private_key_pem", null); } catch (Exception ignored) { }
                        privateKey = null;
                        refresh();
                    })));
            page.addView(c);
        }
        setScreen("کلید و گواهی امضا", null, ui.scroll(page), null);
    }

    private String publicPem() {
        try { return MeelanoTaxCrypto.pem("PUBLIC KEY", MeelanoTaxCrypto.publicFromPrivate(privateKey).getEncoded()); } catch (Exception e) { return ""; }
    }

    private String keyFingerprint() {
        try {
            String h = MeelanoTaxCrypto.sha256Hex(MeelanoTaxCrypto.publicFromPrivate(privateKey).getEncoded());
            return h.substring(0, 16).toUpperCase(Locale.US);
        } catch (Exception e) { return "—"; }
    }

    private void askCsr() {
        LinearLayout body = ui.v();
        final boolean[] co = {!"individual".equals(setting("taxpayer_type"))};
        LinearLayout typeRow = ui.v();
        body.addView(typeRow);
        EditText en = ui.ltrInput("Derakhshan Nuts", setting("english_name"), InputType.TYPE_CLASS_TEXT);
        EditText first = ui.ltrInput("Milad", "", InputType.TYPE_CLASS_TEXT);
        EditText last = ui.ltrInput("Yaghoobi", "", InputType.TYPE_CLASS_TEXT);
        EditText nid = ui.ltrInput("شناسه ملی / کد ملی", setting("national_id"), InputType.TYPE_CLASS_NUMBER);
        EditText ou = ui.ltrInput("اختیاری", "", InputType.TYPE_CLASS_TEXT);
        LinearLayout fields = ui.v();
        body.addView(fields);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            typeRow.removeAllViews();
            typeRow.addView(ui.chipRow(ui.chip("حقوقی (شرکت)", co[0], v -> { co[0] = true; draw[0].run(); }), ui.chip("حقیقی (شخص)", !co[0], v -> { co[0] = false; draw[0].run(); })));
            fields.removeAllViews();
            detach(en, first, last, nid, ou);
            if (co[0]) fields.addView(ui.field("نام لاتین شرکت", en, "در گواهی به شکل «نام [Stamp]» ثبت می‌شود"));
            else {
                fields.addView(ui.field("نام (لاتین)", first, null));
                fields.addView(ui.field("نام خانوادگی (لاتین)", last, null));
            }
            fields.addView(ui.field(co[0] ? "شناسه ملی شرکت (۱۱ رقم)" : "کد ملی (۱۰ رقم)", nid, null));
            if (co[0]) fields.addView(ui.field("واحد سازمانی", ou, null));
        };
        draw[0].run();
        ui.form("درخواست گواهی امضا", body, "ساخت", () -> {
            String n = nz(MeelanoTaxInvoice.digits(nid.getText().toString()));
            MeelanoTaxCrypto.CsrSubject s = new MeelanoTaxCrypto.CsrSubject();
            s.company = co[0];
            s.serialNumber = n;
            if (co[0]) {
                String name = en.getText().toString().trim();
                if (!name.matches("[A-Za-z0-9 .,&()'-]{2,}")) { ui.message("نام لاتین", "نام شرکت را با حروف انگلیسی وارد کنید."); return false; }
                if (n.length() != 11) { ui.message("شناسه ملی", "شناسه ملی شرکت ۱۱ رقم است."); return false; }
                s.commonName = MeelanoTaxCrypto.defaultCommonName(true, name);
                s.organizationalUnit = ou.getText().toString().trim();
            } else {
                String f = first.getText().toString().trim(), l = last.getText().toString().trim();
                if (!f.matches("[A-Za-z .'-]{2,}") || !l.matches("[A-Za-z .'-]{2,}")) { ui.message("نام لاتین", "نام و نام خانوادگی را با حروف انگلیسی وارد کنید."); return false; }
                if (n.length() != 10 || !MeelanoTaxInvoice.nationalCodeValid(n)) { ui.message("کد ملی", "کد ملی ۱۰ رقمی معتبر وارد کنید."); return false; }
                s.commonName = MeelanoTaxCrypto.defaultCommonName(false, f + " " + l);
                s.givenName = f;
                s.surname = l;
                s.organization = null;
            }
            Runnable go = () -> work("در حال ساخت کلید ۲۰۴۸ بیتی…", () -> {
                KeyPair kp = MeelanoTaxCrypto.generateKeyPair();
                byte[] csr = MeelanoTaxCrypto.buildCsr(kp, s);
                if (!MeelanoTaxCrypto.verifyCsr(csr, kp.getPublic())) throw new IllegalStateException("امضای درخواست گواهی بررسی نشد.");
                storeKey(kp.getPrivate());
                return MeelanoTaxCrypto.pem("CERTIFICATE REQUEST", csr);
            }, pem -> {
                Map<String, String> vals = new HashMap<>();
                vals.put("taxpayer_type", co[0] ? "company" : "individual");
                if (co[0]) vals.put("english_name", en.getText().toString().trim());
                vals.put("last_csr", pem);
                putSettings(vals, () -> showCsr(pem));
            });
            if (privateKey != null) ui.confirm("جایگزینی کلید", "کلید خصوصی فعلی با کلید جدید جایگزین می‌شود و گواهی فعلی دیگر کار نمی‌کند تا گواهی جدید را وارد کنید. پیشنهاد: قبل از ادامه از کلید فعلی پشتیبان بگیرید.", "ساخت کلید جدید", go);
            else go.run();
            return true;
        }, "انصراف");
    }

    private static void detach(View... views) {
        for (View v : views) if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private void showCsr(String pem) {
        LinearLayout body = ui.v();
        body.addView(ui.caption("کلید خصوصی ساخته و روی گوشی ذخیره شد. این درخواست را برای مرکز صدور گواهی بفرستید. بعد از دریافت گواهی، آن را از «ورود فایل گواهی» وارد کنید و در کارپوشه ← عضویت ثبت کنید."));
        TextView t = ui.ltr(pem, 10.5f, MeelanoTaxUi.TEXT, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        body.addView(t);
        body.addView(ui.pair(
                ui.button("ذخیره فایل", R.drawable.mi_save, MeelanoTaxUi.BTN_SECONDARY, v -> saveTextFile("moadian-request.csr", pem)),
                ui.button("اشتراک", R.drawable.mi_share, MeelanoTaxUi.BTN_GHOST, v -> shareText("درخواست گواهی", pem))));
        ui.sheet("درخواست گواهی (CSR)", body, "کپی", () -> copy("csr", pem), "بستن");
        refresh();
    }

    private void importCertificateBytes(byte[] data) {
        try {
            X509Certificate cert;
            String text = new String(data, StandardCharsets.UTF_8);
            cert = text.contains("-----BEGIN") ? MeelanoTaxCrypto.certificateFromText(text) : MeelanoTaxCrypto.certificateFromBytes(data);
            acceptCertificate(cert, null);
        } catch (Exception e) { showError(new IllegalArgumentException("فایل گواهی خوانده نشد: " + nz(e.getMessage()))); }
    }

    /** Checks and saves a certificate (and optionally a key imported with it). */
    private void acceptCertificate(X509Certificate cert, PrivateKey withKey) {
        PrivateKey k = withKey != null ? withKey : privateKey;
        List<String> warn = new ArrayList<>();
        if (k == null) warn.add("کلید خصوصی متناظر روی گوشی نیست.");
        else if (!MeelanoTaxCrypto.keyMatchesCertificate(k, cert)) warn.add("این گواهی با کلید خصوصی جفت نیست.");
        try { cert.checkValidity(); } catch (Exception e) { warn.add("گواهی منقضی شده یا هنوز معتبر نیست."); }
        String sn = nz(MeelanoTaxCrypto.subject(cert).get("SERIALNUMBER")).trim();
        if (!setting("national_id").isEmpty() && !sn.equals(setting("national_id"))) warn.add("شناسه ملی گواهی (" + MeelanoTaxUi.fa(sn) + ") با شناسه ملی تنظیمات یکی نیست.");
        if (MeelanoTaxCrypto.selfSigned(cert)) warn.add("گواهی خودامضا است.");
        Runnable save = () -> db("در حال ذخیره گواهی…", c -> {
            if (withKey != null) storeKey(withKey);
            storeCert(c, cert);
            if (setting("national_id").isEmpty() && !sn.isEmpty()) {
                MeelanoTaxDb.putSetting(c, "national_id", sn, userName());
                settings.put("national_id", sn);
            }
            return true;
        }, x -> { toast("گواهی ذخیره شد"); refresh(); });
        if (warn.isEmpty()) { save.run(); return; }
        ui.confirm("بررسی گواهی", String.join("\n", warn) + "\n\nبا این وجود ذخیره شود؟", "ذخیره", save);
    }

    private void askPfxPassword(byte[] data) {
        LinearLayout body = ui.v();
        EditText pw = ui.ltrInput("رمز فایل", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(ui.field("رمز فایل PFX", pw, null));
        ui.sheet("ورود PFX / P12", body, "ورود", () -> {
            String p = pw.getText().toString();
            work("در حال خواندن فایل…", () -> MeelanoTaxCrypto.importPfx(data, p), pfx -> acceptCertificate(pfx.cert, pfx.key));
        }, "انصراف");
    }

    private void askPem() {
        LinearLayout body = ui.v();
        body.addView(ui.caption("متن «PRIVATE KEY» یا «RSA PRIVATE KEY» و/یا «CERTIFICATE» را کامل (با خطوط BEGIN و END) بچسبانید. کلید رمزدار پذیرفته نمی‌شود."));
        EditText t = ui.ltrInput("-----BEGIN …-----", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        t.setMinLines(6);
        t.setGravity(Gravity.TOP | Gravity.START);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.addView(t);
        ui.sheet("چسباندن PEM", body, "ورود", () -> {
            String text = t.getText().toString();
            List<String> types = MeelanoTaxCrypto.pemTypes(text);
            try {
                PrivateKey k = null;
                X509Certificate cert = null;
                if (types.contains("PRIVATE KEY") || types.contains("RSA PRIVATE KEY")) k = MeelanoTaxCrypto.privateKeyFromPem(text);
                if (types.contains("CERTIFICATE")) cert = MeelanoTaxCrypto.certificateFromText(text);
                if (types.contains("ENCRYPTED PRIVATE KEY")) throw new IllegalArgumentException("کلید رمزدار است؛ نسخه بدون رمز را وارد کنید یا از فایل PFX استفاده کنید.");
                if (k == null && cert == null) throw new IllegalArgumentException("کلید یا گواهی در متن پیدا نشد.");
                if (cert != null) acceptCertificate(cert, k);
                else {
                    storeKey(k);
                    toast("کلید ذخیره شد");
                    refresh();
                }
            } catch (Exception e) { showError(e); }
        }, "انصراف");
    }

    // ================================================================== پشتیبان کلید

    private void renderBackup() {
        LinearLayout page = ui.page();
        LinearLayout info = ui.softCard(MeelanoTaxUi.GOLD_SOFT, 0xFFEBD9B5);
        info.addView(ui.body("کلید خصوصی فقط روی همین گوشی است. اگر گوشی عوض یا برنامه حذف شود، بدون پشتیبان باید کلید و گواهی جدید بگیرید. پشتیبان با رمز دلخواه شما (AES-256) رمز می‌شود؛ رمز را جای امنی نگه دارید."));
        page.addView(info);
        String at = setting("key_backup_at");
        LinearLayout c = ui.card();
        c.addView(ui.heading("گرفتن پشتیبان"));
        c.addView(ui.row("آخرین پشتیبان در پایگاه داده", at.isEmpty() ? "ندارد" : MeelanoTaxUi.fa(at)));
        c.addView(ui.button("پشتیبان رمزدار در پایگاه داده آتیران", R.drawable.mi_cloud_upload, MeelanoTaxUi.BTN_PRIMARY, v -> askBackup(true)));
        c.addView(ui.button("پشتیبان رمزدار در فایل", R.drawable.mi_save, MeelanoTaxUi.BTN_SECONDARY, v -> askBackup(false)));
        page.addView(c);
        LinearLayout r = ui.card();
        r.addView(ui.heading("بازگردانی"));
        if (!setting("key_backup").isEmpty()) r.addView(ui.button("بازگردانی از پایگاه داده", R.drawable.mi_cloud_download, MeelanoTaxUi.BTN_SECONDARY, v -> askRestore(setting("key_backup"))));
        r.addView(ui.button("بازگردانی از فایل", R.drawable.mi_upload_file, MeelanoTaxUi.BTN_GHOST, v -> pickFile((data, name) -> askRestore(new String(data, StandardCharsets.UTF_8).trim()))));
        page.addView(r);
        setScreen("پشتیبان کلید", null, ui.scroll(page), null);
    }

    private void askBackup(boolean toDb) {
        if (privateKey == null) { ui.message("پشتیبان", "کلید خصوصی روی این گوشی نیست."); return; }
        LinearLayout body = ui.v();
        EditText p1 = ui.ltrInput("حداقل ۸ کاراکتر", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText p2 = ui.ltrInput("تکرار رمز", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(ui.field("رمز پشتیبان", p1, null));
        body.addView(ui.field("تکرار رمز", p2, null));
        ui.form("پشتیبان کلید", body, "رمزگذاری", () -> {
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < 8) { ui.message("رمز", "رمز باید حداقل ۸ کاراکتر باشد."); return false; }
            if (!a.equals(b)) { ui.message("رمز", "رمز و تکرار آن یکی نیستند."); return false; }
            PrivateKey k = privateKey;
            work("در حال رمزگذاری…", () -> "MEELANO-TAX-KEY-1\n" + MeelanoTaxCrypto.sealWithPassphrase(MeelanoTaxCrypto.pem("PRIVATE KEY", k.getEncoded()).getBytes(StandardCharsets.UTF_8), a), sealed -> {
                if (toDb) {
                    Map<String, String> vals = new HashMap<>();
                    vals.put("key_backup", sealed);
                    vals.put("key_backup_at", jdate(System.currentTimeMillis()));
                    putSettings(vals, this::refresh);
                } else saveTextFile("moadian-key-backup.txt", sealed);
            });
            return true;
        }, "انصراف");
    }

    private void askRestore(String sealed) {
        String body0 = sealed.startsWith("MEELANO-TAX-KEY-1") ? sealed.substring(sealed.indexOf('\n') + 1).trim() : sealed.trim();
        LinearLayout body = ui.v();
        EditText pw = ui.ltrInput("رمز پشتیبان", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(ui.field("رمز پشتیبان", pw, null));
        ui.sheet("بازگردانی کلید", body, "بازگردانی", () -> {
            String p = pw.getText().toString();
            work("در حال بازگشایی…", () -> MeelanoTaxCrypto.privateKeyFromPem(new String(MeelanoTaxCrypto.openWithPassphrase(body0, p), StandardCharsets.UTF_8)), k -> {
                Runnable go = () -> {
                    try { storeKey(k); toast("کلید بازگردانی شد"); refresh(); } catch (Exception e) { showError(e); }
                };
                if (certificate != null && !MeelanoTaxCrypto.keyMatchesCertificate(k, certificate)) ui.confirm("بازگردانی", "این کلید با گواهی فعلی جفت نیست. باز هم جایگزین شود؟", "جایگزین کن", go);
                else go.run();
            });
        }, "انصراف");
    }

    // ================================================================== اتصال پایگاه داده

    private void renderDbSettings() {
        setScreen("اتصال پایگاه داده", "سرور SQL آتیران", ui.scroll(dbSettingsForm(() -> { toast("ذخیره شد"); goBack(); })), null);
    }

    /** DB settings before login (no shell). */
    private void renderDbSettingsStandalone() {
        isLoginScreen = false;
        LinearLayout col = ui.v();
        LinearLayout bar = ui.h();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(8), ui.dp(10), ui.dp(16), ui.dp(10));
        bar.setBackgroundColor(MeelanoTaxUi.SURFACE);
        bar.setElevation(ui.dp(2));
        ImageView back = ui.icon(R.drawable.mi_arrow_forward, MeelanoTaxUi.PRIMARY_DARK, 24);
        back.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        back.setBackground(ui.pressable(ui.round(Color.TRANSPARENT, 22, 0, 0), 0x220B6A70));
        back.setOnClickListener(v -> showLogin());
        bar.addView(back, MeelanoTaxUi.lp(ui.dp(44), ui.dp(44)));
        TextView t = ui.title("اتصال پایگاه داده آتیران");
        t.setPadding(ui.dp(6), 0, 0, 0);
        bar.addView(t, MeelanoTaxUi.weight(1));
        col.addView(bar, MeelanoTaxUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(ui.scroll(dbSettingsForm(this::showLogin)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setRoot(col);
    }

    private View dbSettingsForm(Runnable afterSave) {
        LinearLayout page = ui.page();
        String[] d = MainActivity.defaultDbConfig();
        LinearLayout c = ui.card();
        c.addView(ui.caption("خالی گذاشتن هر مورد = مقدار پیش‌فرض برنامه‌های پخش درخشان (" + d[0] + ":" + d[1] + " / " + d[2] + ")."));
        EditText host = ui.ltrInput(d[0], prefs.getString("db_host", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = ui.ltrInput(d[1], prefs.getString("db_port", ""), InputType.TYPE_CLASS_NUMBER);
        EditText name = ui.ltrInput(d[2], prefs.getString("db_name", ""), InputType.TYPE_CLASS_TEXT);
        EditText u = ui.ltrInput("پیش‌فرض", prefs.getString("db_user", ""), InputType.TYPE_CLASS_TEXT);
        String oldPass = MeelanoTaxVault.get(prefs, "db_pass");
        EditText p = ui.ltrInput("رمز کاربر پایگاه داده", oldPass == null ? "" : oldPass, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(ui.field("آدرس سرور", host, null));
        c.addView(ui.field("پورت", port, null));
        c.addView(ui.field("نام پایگاه داده", name, null));
        c.addView(ui.field("کاربر SQL", u, "اگر خالی باشد، کاربر و رمز پیش‌فرض برنامه استفاده می‌شود"));
        c.addView(ui.field("رمز SQL", p, "رمز با کلید امن گوشی رمزگذاری و ذخیره می‌شود"));
        CheckBox vpn = ui.check("در صورت روشن بودن VPN، اتصال از شبکه مستقیم گوشی برقرار شود", !"0".equals(prefs.getString("vpn_bypass", "1")));
        c.addView(vpn);
        page.addView(c);
        Runnable apply = () -> {
            prefs.edit().putString("db_host", host.getText().toString().trim()).putString("db_port", MeelanoTaxDb.foldDigits(port.getText().toString().trim()))
                    .putString("db_name", name.getText().toString().trim()).putString("db_user", u.getText().toString().trim())
                    .putString("vpn_bypass", vpn.isChecked() ? "1" : "0").apply();
            try { MeelanoTaxVault.put(prefs, "db_pass", p.getText().toString().isEmpty() ? null : p.getText().toString()); } catch (Exception e) { toast("رمز ذخیره نشد: " + e.getMessage()); }
            schemaReady = false;
            MeelanoTaxDb.clearCache();
        };
        page.addView(ui.button("آزمون اتصال", R.drawable.mi_sync, MeelanoTaxUi.BTN_SECONDARY, v -> {
            apply.run();
            db("در حال اتصال…", cn -> {
                List<String> miss = MeelanoTaxDb.missingTables(cn);
                return new String[]{nz(MeelanoTaxDb.serverVersion(cn)), String.join("، ", miss)};
            }, r -> ui.message("آزمون اتصال", "اتصال برقرار شد.\nنسخه سرور: " + r[0] + (r[1].isEmpty() ? "\nهمه جدول‌های مورد نیاز موجود است." : "\nجدول‌های پیدا نشده: " + r[1])
                    + (networkNote.isEmpty() ? "" : "\n\n" + networkNote)));
        }));
        page.addView(ui.button("ذخیره", R.drawable.mi_save, MeelanoTaxUi.BTN_PRIMARY, v -> { apply.run(); if (afterSave != null) afterSave.run(); }));
        return page;
    }

    // ================================================================== راهنما

    private void renderHelp() {
        LinearLayout page = ui.page();
        String[][] sections = {
                {"مراحل کار", "۱. در «راه‌اندازی اولیه» مشخصات مودی را کامل کنید.\n۲. در «کلید و گواهی» کلید بسازید، CSR را برای مرکز صدور گواهی بفرستید و گواهی را وارد کنید؛ سپس گواهی (یا کلید عمومی) را در کارپوشه ← عضویت ← حافظه مالیاتی ثبت کنید.\n۳. شناسه ۱۳ رقمی کالاها و مشخصات خریداران را کامل کنید.\n۴. آزمون نهایی را بگیرید، ابتدا در محیط آزمایشی ارسال کنید و سپس محیط را «اصلی» کنید.\n۵. از «فاکتورهای فروش» فاکتورها را انتخاب و ارسال کنید؛ نتیجه چند ثانیه بعد خودکار استعلام می‌شود."},
                {"انواع صورتحساب", "• اصلی: اولین ارسال هر فاکتور.\n• اصلاحی: وقتی فاکتور ثبت‌شده در آتیران ویرایش شود (مبلغ، مقدار، تخفیف). تغییر خریدار، شناسه کالا یا نرخ مالیات با اصلاحی ممکن نیست و باید ابطال و ارسال دوباره انجام شود.\n• ابطالی: فاکتور باطل‌شده در آتیران یا برگشت کامل.\n• برگشت از فروش: از «برگشتی و ابطال»، با مقدار باقی‌مانده و همان قیمت‌های صورتحساب اصلی."},
                {"نوع اول و نوع دوم", "صورتحساب نوع اول مشخصات خریدار را دارد و در کارپوشه خریدار نمایش داده می‌شود؛ برای خریدار حقیقی کد ملی و کد پستی (یا شماره اقتصادی) و برای حقوقی شماره اقتصادی/شناسه ملی لازم است. نوع دوم برای مصرف‌کننده نهایی و خریدار بدون مشخصات است."},
                {"مهلت ارسال", "صورتحساب باید ظرف مهلت قانونی فرستاده شود (پیش‌فرض ۱۲ روز، قابل تغییر). بعد از مهلت، برنامه خودکار «قاعده ارسال = ۱» و تاریخ ارسال را مطابق ماده ۹ اضافه می‌کند."},
                {"وضعیت‌ها", "• در انتظار نتیجه: فرستاده شده و سامانه هنوز پاسخ نهایی نداده؛ «استعلام» بزنید.\n• ثبت شد: سامانه پذیرفته است.\n• رد شده: خطا را بخوانید، اطلاعات را اصلاح و دوباره بفرستید (شماره مالیاتی جدید ساخته می‌شود).\n• نرسیده: درخواست به سامانه نرسیده؛ دوباره بفرستید.\n• وضعیت کارپوشه: تأیید، رد یا عدم نیاز به واکنش خریدار."},
                {"خطاهای رایج", "• خطای احراز هویت: گواهی/کلید عمومی در کارپوشه برای همین حافظه ثبت نشده، یا شناسه ملی گواهی با صاحب حافظه یکی نیست.\n• شناسه کالا نامعتبر: شناسه ۱۳ رقمی را از stuffid.tax.gov.ir بررسی کنید.\n• شماره اقتصادی خریدار نامعتبر: مشخصات خریدار را اصلاح یا نوع دوم بفرستید.\n• VPN: برای ارتباط با سامانه مودیان VPN را خاموش کنید یا گزینه «شبکه مستقیم» را روشن بگذارید."},
                {"امنیت", "کلید خصوصی فقط روی گوشی و با کلید امن اندروید رمز می‌شود و هیچ‌وقت به صورت باز در پایگاه داده ذخیره نمی‌شود. برای جابه‌جایی گوشی از «پشتیبان کلید» با رمز دلخواه استفاده کنید. همه ارسال‌ها با نام کاربر آتیران در سوابق ثبت می‌شود."}
        };
        for (String[] s : sections) {
            LinearLayout c = ui.card();
            c.addView(ui.heading(s[0]));
            c.addView(ui.body(s[1]));
            page.addView(c);
        }
        page.addView(ui.button("کارپوشه مودیان (my.tax.gov.ir)", R.drawable.mi_open_in_new, MeelanoTaxUi.BTN_GHOST, v -> openUrl("https://my.tax.gov.ir")));
        setScreen("راهنمای سامانه مودیان", null, ui.scroll(page), null);
    }
}
