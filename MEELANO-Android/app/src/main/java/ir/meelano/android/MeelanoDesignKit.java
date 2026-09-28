package ir.meelano.android;

/**
 * Central visual language tokens for the native Meelano Android app.
 * The main Activity still owns Android Views, but semantic glyphs, labels and
 * beauty hints live here so every section can share one design vocabulary.
 */
final class MeelanoDesignKit {
    private MeelanoDesignKit() {}

    static String glyph(String key) {
        if ("dashboard".equals(key)) return "⌂";
        if ("customers".equals(key)) return "♙";
        if ("products".equals(key)) return "◍";
        if ("reports".equals(key) || "visitor_reports".equals(key)) return "↗";
        if ("command".equals(key)) return "⌘";
        if ("assistant".equals(key)) return "✦";
        if ("chat".equals(key)) return "✉";
        if ("personnel".equals(key)) return "🪪";
        if ("attendance".equals(key)) return "⏱";
        if ("taxpayers".equals(key)) return "٪";
        if ("cameras".equals(key)) return "▣";
        if ("alarm".equals(key)) return "◬";
        if ("visitor_dashboard".equals(key)) return "◎";
        if ("showcase".equals(key)) return "◈";
        if ("cart".equals(key)) return "⊕";
        if ("settings".equals(key)) return "⚙";
        if ("management".equals(key)) return "♛";
        if ("health".equals(key)) return "◌";
        return "◆";
    }

    static String label(String key) {
        if ("dashboard".equals(key)) return "داشبورد";
        if ("customers".equals(key)) return "مشتریان";
        if ("products".equals(key)) return "کالاها";
        if ("reports".equals(key) || "visitor_reports".equals(key)) return "گزارشات";
        if ("command".equals(key)) return "فرماندهی";
        if ("assistant".equals(key)) return "دستیار";
        if ("chat".equals(key)) return "گفتگو";
        if ("personnel".equals(key)) return "پرسنل";
        if ("attendance".equals(key)) return "حضور";
        if ("taxpayers".equals(key)) return "مودیان";
        if ("cameras".equals(key)) return "دوربین";
        if ("alarm".equals(key)) return "دزدگیر";
        if ("visitor_dashboard".equals(key)) return "ماموریت";
        if ("showcase".equals(key)) return "کالا";
        if ("cart".equals(key)) return "سبد";
        if ("settings".equals(key)) return "تنظیمات";
        if ("management".equals(key)) return "مدیریت";
        if ("health".equals(key)) return "اتصال";
        return key == null || key.trim().isEmpty() ? "بخش" : key;
    }

    static String beautyHint(String key) {
        if ("dashboard".equals(key)) return "نمای مدیریتی glass با KPIهای سریع";
        if ("customers".equals(key)) return "کارت مشتری با ریسک، تماس و اولویت وصول";
        if ("products".equals(key)) return "کارت کالا با تصویر، قیمت و نمودار ریزگردش";
        if ("showcase".equals(key)) return "کالای مشتری‌محور، سریع و آماده ارائه";
        if ("cart".equals(key)) return "سبد ساده پیش‌فاکتور برای ویزیتور";
        if ("visitor_dashboard".equals(key)) return "ماموریت، مسیر، کالا و پیش‌فاکتور بدون بخش اضافه";
        if ("personnel".equals(key)) return "پرونده پرسنلی با خلاصه مالی و حضور";
        if ("reports".equals(key) || "visitor_reports".equals(key)) return "گزارشات طلایی ویزیتور، فاکتورهای من و صف آفلاین";
        if ("taxpayers".equals(key)) return "ارسال سازمانی با وضعیت روشن";
        if ("cameras".equals(key) || "alarm".equals(key)) return "کنترل سخت‌افزار با کارت وضعیت";
        if ("settings".equals(key)) return "آزمایشگاه تم، حرکت و امنیت محلی";
        return "زبان بصری یکپارچه Meelano";
    }
}
