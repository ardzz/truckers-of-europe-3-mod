package com.toemod;

/**
 * Screen and input rules of the in-game menu that do not need Android: which page is shown,
 * when live diagnostics may be read, and exact value entry within a control's native range.
 */
final class MenuModel {
    enum Page { HOME, CATEGORY, WORLD, UPDATES, DEBUG }

    /** Category whose controls live on the World tab instead of a Home row. */
    static final String WORLD = "World";

    private MenuModel() {}

    /** The bottom tab a page belongs to: a category is reached from Home. */
    static Page tab(Page p) {
        return p == Page.CATEGORY ? Page.HOME : p;
    }

    /** Native diagnostics are read only while someone can see them. */
    static boolean needsSnapshot(Page p, boolean expanded, boolean resumed) {
        return expanded && resumed && (p == Page.WORLD || p == Page.DEBUG);
    }

    /**
     * A typed value for a control with range [min, max], or NaN when the text is not a plain
     * decimal (no exponent, hex, suffix, NaN or Infinity) or lies outside the range. Never clamps:
     * the user sees why an entry was refused instead of a different value being applied.
     */
    static float parseValue(String text, float min, float max, boolean integer) {
        if (text == null) return Float.NaN;
        String s = text.trim();
        if (!integer && s.indexOf('.') < 0 && s.indexOf(',') == s.lastIndexOf(',')) s = s.replace(',', '.');
        String pattern = integer ? "-?\\d{1,12}" : "-?(\\d{1,12}(\\.\\d{0,6})?|\\.\\d{1,6})";
        if (!s.matches(pattern)) return Float.NaN;
        double v = Double.parseDouble(s);
        if (v < min || v > max) return Float.NaN;
        return (float) v;
    }
}
