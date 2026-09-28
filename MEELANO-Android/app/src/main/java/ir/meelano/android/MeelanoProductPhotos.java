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
            case "rice_bag": return "rice";
            case "oil_bottle": return "cooking_oil";
            case "tea_box": return "black_tea";
            case "pasta_pack": return "pasta";
            case "cheese": return "white_cheese";
            case "water_bottle": return "mineral_water";
            case "milk_carton": return "milk";
            case "dairy_cup": return "yogurt";
            default: return null;
        }
    }

    static String keyFor(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = norm(text);
        if (has(t, "بیسکویت", "بیسکوییت", "بیسکوئیت", "کلوچه", "ویفر")) return "biscuit";
        if (has(t, "تن ماهی", "تنماهی", "کنسرو ماهی", "ماهی تن")) return "canned_tuna";
        if (has(t, "پودر لباسشویی", "پودر ماشین لباسشویی", "پودر دستی", "مایع لباسشویی", "شوینده لباس")) return "laundry_powder";
        if (has(t, "پسته", "بادام", "بادوم", "فندق", "گردو", "آجیل", "اجیل", "تخمه")) {
            return has(t, "کره", "روغن", "شیر", "بستنی", "شکلات") ? null : "nuts";
        }
        if (has(t, "عدس", "لوبیا", "نخود", "لپه", "حبوبات") || hasWord(t, "ماش")) {
            return has(t, "کنسرو", "سبز", "فرنگی", "ماشین", "ظرفشویی", "لباسشویی") ? null : "legumes";
        }
        // Whole words only: «نبات» must not catch «روغن نباتی», «قند» not «قندان».
        if (hasWord(t, "شکر", "قند", "نبات")) {
            return has(t, "شکلات", "رژیمی") || hasWord(t, "بی قند", "بدون قند") ? null : "sugar";
        }
        if (has(t, "شیر کاکائو", "شیرکاکائو", "شیر موز", "شیرموز")) return "milk";
        if (has(t, "شیرینی", "شیرین", "شیره", "شیر خشک", "شیرخشک", "شیر برنج", "بستنی", "کیک", "شکلات")) return null;
        if (has(t, "ماست")) return "yogurt";
        if (has(t, "پنیر")) return "white_cheese";
        if (has(t, "شیر")) return "milk";
        if (has(t, "آب معدنی", "اب معدنی", "آب آشامیدنی", "اب اشامیدنی")) return "mineral_water";
        if (has(t, "مایع ظرف", "ظرفشویی", "ظرف شویی")) return has(t, "پودر", "قرص", "ماشین") ? null : "dish_soap";
        if (has(t, "چای")) return has(t, "چای سبز", "چای ساز", "چایساز", "دمنوش", "لیوان", "قوری") ? null : "black_tea";
        if (has(t, "برنج")) return "rice";
        if (has(t, "ماکارونی", "ماکارانی", "پاستا")) return "pasta";
        if (has(t, "رب")) return has(t, "انار", "آلو", "الو", "لیمو") ? null : "tomato_paste";
        if (has(t, "روغن")) return has(t, "موتور", "ترمز", "بدن", "بچه", "ماساژ", "مو", "آرایشی", "ارایشی") ? null : "cooking_oil";
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
