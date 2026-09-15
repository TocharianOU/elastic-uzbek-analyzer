/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

/**
 * The apostrophe problem, isolated.
 *
 * <p>Uzbek Latin uses two distinct modifier letters that users almost never
 * type correctly:
 * <ul>
 *   <li>{@code U+02BB ʻ} MODIFIER LETTER TURNED COMMA — the one in
 *       {@code oʻ} and {@code gʻ} (part of the letter).</li>
 *   <li>{@code U+02BC ʼ} MODIFIER LETTER APOSTROPHE — the
 *       <i>tutuq belgisi</i>, which marks a glottal stop / vowel length and
 *       also separates {@code s+h} from the digraph {@code sh}
 *       (as in {@code Isʼhoq}).</li>
 * </ul>
 *
 * <p>In real catalogue data both arrive as any of a dozen lookalikes, or are
 * omitted entirely. Every one of these must fold to the same token or search
 * silently loses documents.
 */
public final class Apostrophes {

    /** Canonical form inside {@code oʻ} / {@code gʻ}. */
    public static final char TURNED_COMMA = 'ʻ';   // ʻ
    /** Canonical <i>tutuq belgisi</i>. */
    public static final char TUTUQ        = 'ʼ';   // ʼ

    /**
     * Every codepoint observed standing in for an Uzbek apostrophe. Order is
     * irrelevant; membership is what matters.
     */
    private static final String VARIANTS =
              "'"   // '  APOSTROPHE            (ASCII, by far the most common)
            + "`"   // `  GRAVE ACCENT          (keyboard slip)
            + "´"   // ´  ACUTE ACCENT
            + "ʹ"   // ʹ  MODIFIER LETTER PRIME
            + "ʻ"   // ʻ  MODIFIER LETTER TURNED COMMA   <- canonical (oʻ/gʻ)
            + "ʼ"   // ʼ  MODIFIER LETTER APOSTROPHE     <- canonical (tutuq)
            + "ʽ"   // ʽ  MODIFIER LETTER REVERSED COMMA
            + "ˈ"   // ˈ  MODIFIER LETTER VERTICAL LINE
            + "‘"   // '  LEFT SINGLE QUOTATION MARK     (autocorrect)
            + "’"   // '  RIGHT SINGLE QUOTATION MARK    (autocorrect)
            + "‛"   // ‛  SINGLE HIGH-REVERSED-9
            + "′"   // ′  PRIME
            + "ꞌ"   // ꞌ  LATIN SMALL LETTER SALTILLO
            + "＇";  // ＇ FULLWIDTH APOSTROPHE

    private Apostrophes() {}

    /** True if {@code c} is any spelling of an Uzbek apostrophe. */
    public static boolean isApostrophe(char c) {
        return VARIANTS.indexOf(c) >= 0;
    }
}
