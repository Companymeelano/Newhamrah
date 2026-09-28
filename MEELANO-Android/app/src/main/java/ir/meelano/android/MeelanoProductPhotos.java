package ir.meelano.android;

import java.util.Locale;

/**
 * Chooses which bundled photo (assets/products/&lt;key&gt;.jpg) fits a product, from its name or group.
 * Pure text logic, split out of MainActivity so it can be read and extended on its own.
 *
 * To add a photo: put &lt;key&gt;.jpg in assets/products, add its words below and list it in
 * assets/products/README.md.
 */
final class MeelanoProductPhotos {
    private MeelanoProductPhotos() { }

    /** Photo for a visual type the visitor picked by hand ("اصلاح هوشمندی تصویر"); null keeps the drawn picture. */
    static String keyForType(String type) {
        if (type == null) return null;
        switch (type) {
            case "nuts_bag": return "nuts";
            default: return null;
        }
    }

    /**
     * Nuts and dried fruit (آجیل و خشکبار). Specific words come first; «آجیل/مخلوط/چهارمغز» shows the
     * mixed bowl. Products made from nuts (کره بادام زمینی، روغن گردو، شکلات فندقی…) keep the drawn picture.
     */
    static String keyFor(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = norm(text);
        // Sweets and snacks that only taste of nuts or fruit (in the Atiran list: آبنبات فندقی، ویفر فندقی،
        // تافی بادام زمینی، آدامس توت فرنگی…) keep the drawn picture.
        if (has(t, "کره", "روغن", "بستنی", "شکلات", "کیک", "بیسکو", "آبنبات", "ابنبات", "ابنیات", "آدامس", "ادامس", "تافی",
                "ویفر", "پاستیل", "دراژه", "اسمارتیز", "شوکو", "توت فرنگی", "توتفرنگی", "طعم", "چیپس", "بایکیت", "تابلت", "اچاچی", "آچاچی")
                // whole words only: «کرم» is inside «کرمان», «پفک» inside «انجیر پفکی» (a kind of dried fig)
                || hasWord(t, "شیر", "کرم", "نانی", "پفک", "آرد", "ارد")) return null;
        if (has(t, "میوه خشک", "خشک میوه")) return "dried_fruit";
        if (hasWord(t, "آجیل", "اجیل", "مخلوط", "چهارمغز", "چهار مغز", "شب یلدا", "نخودچی")) return "nuts";
        if (has(t, "بادام زمینی", "بادوم زمینی", "بادامزمینی")) return "peanut";
        if (has(t, "بادام هندی", "بادوم هندی", "بادامهندی", "کاجو", "کازو")) return "cashew";
        if (has(t, "پسته")) return "pistachio";
        if (has(t, "فندق")) return "hazelnut";
        if (has(t, "گردو", "گردوی")) return "walnut";
        if (has(t, "بادام", "بادوم")) return "almond";
        if (has(t, "تخمه", "تخم کدو", "تخم هندوانه", "تخم آفتابگردان", "آفتابگردان", "شاهدانه")) return "seeds";
        if (has(t, "کشمش", "مویز", "سبزه")) return "raisin";
        if (has(t, "خرما", "رطب", "مضافتی", "پیارم", "زاهدی", "کبکاب")) return "dates";
        if (has(t, "برگه", "انجیر", "قیسی", "قیصی", "زردآلو", "زرد آلو", "آلو بخارا", "آلوبخارا", "آلوچه", "الوچه", "برگ زرد")
                || hasWord(t, "آلو", "الو", "توت", "توت خشک", "هلو خشک", "سیب خشک", "کیوی خشک")) return "dried_fruit";
        if (has(t, "خشکبار")) return "nuts";
        return null;
    }

    /** Lower-case, Arabic→Persian letters, punctuation and half-spaces → spaces, padded with one space. */
    static String norm(String value) {
        String t = value == null ? "" : value.toLowerCase(Locale.US);
        t = t.replace('ي', 'ی').replace('ك', 'ک').replace('أ', 'ا').replace('إ', 'ا').replace('ؤ', 'و').replace('ۀ', 'ه').replace('ة', 'ه');
        t = t.replace('\u200c', ' ').replace('\u200f', ' ').replace('\u200e', ' ');
        t = t.replaceAll("[\\p{Punct}\\[\\]{}()\\-_/\\\\|،؛]+", " ");
        return " " + t.replaceAll("\\s+", " ").trim() + " ";
    }

    /** Whole word, or (for keys longer than two letters) anywhere inside a word. */
    private static boolean has(String hay, String... keys) {
        for (String k : keys) {
            String key = norm(k).trim();
            if (key.isEmpty()) continue;
            if (hay.contains(" " + key + " ")) return true;
            if (key.length() > 2 && hay.contains(key)) return true;
        }
        return false;
    }

    private static boolean hasWord(String hay, String... keys) {
        for (String k : keys) {
            String key = norm(k).trim();
            if (!key.isEmpty() && hay.contains(" " + key + " ")) return true;
        }
        return false;
    }
}
