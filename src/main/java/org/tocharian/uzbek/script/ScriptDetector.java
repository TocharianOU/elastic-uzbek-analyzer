/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

/**
 * Layer 0 — orthography and language identification.
 *
 * <p>Pure function, no state, no I/O. Decides which of the four Uzbek
 * orthographies a string is written in, and — critically for e-commerce
 * catalogues — separates Uzbek Cyrillic from Russian Cyrillic so that
 * Layer 4 never applies Uzbek morphology to a Russian word.
 *
 * <p>Discriminating evidence:
 * <ul>
 *   <li>Uzbek Cyrillic has {@code ў қ ғ ҳ}; Russian does not.</li>
 *   <li>Russian has {@code ы щ}; Uzbek Cyrillic does not.</li>
 *   <li>The 2026 Latin reform has {@code ö ğ ş ç}; the 1995 one has
 *       {@code oʻ gʻ} plus the digraphs {@code sh ch}.</li>
 * </ul>
 *
 * <p>Works at both field and token granularity. Callers should run it per
 * token on mixed catalogue text, because a single product title routinely
 * contains Uzbek, Russian and a Latin brand name.
 */
public final class ScriptDetector {

    // -- Uzbek-only Cyrillic letters ------------------------------------
    private static final String UZ_CYRL_MARKERS = "ўқғҳ";   // ў қ ғ ҳ
    // -- Russian-only Cyrillic letters ----------------------------------
    private static final String RU_CYRL_MARKERS = "ыщ";               // ы щ
    // -- 2026 Latin reform letters (also the internal form) -------------
    private static final String LATN_2026_MARKERS = "öğşçș"; // ö ğ ş ç ș
    // -- Latin letters no Uzbek orthography has --------------------------
    // ı and ŝ never come from Uzbek text. The normalizer writes Russian ы and щ
    // as these two, so the evidence that a token is Russian survives folding to
    // Latin and can still be seen when the token filter runs.
    private static final String NON_UZBEK_LATIN = "ıŝ";      // ı ŝ

    private ScriptDetector() {}

    /** Immutable detection result. Counts are exposed so callers can set their own thresholds. */
    public record Detection(
            ScriptId script,
            double confidence,
            int latin, int cyrillic, int arabic, int digit, int other,
            int uzCyrlMarkers, int ruCyrlMarkers, int latn2026Markers, int latn1995Markers) {

        public int letters() {
            return latin + cyrillic + arabic;
        }

        @Override
        public String toString() {
            return String.format(
                "%-15s conf=%.2f  [la=%d cy=%d ar=%d di=%d]  uz=%d ru=%d n26=%d n95=%d",
                script, confidence, latin, cyrillic, arabic, digit,
                uzCyrlMarkers, ruCyrlMarkers, latn2026Markers, latn1995Markers);
        }
    }

    public static Detection detect(String s) {
        if (s == null || s.isEmpty()) {
            return new Detection(ScriptId.UNKNOWN, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        int latin = 0, cyrillic = 0, arabic = 0, digit = 0, other = 0;
        int uzMark = 0, ruMark = 0, n26 = 0, n95 = 0;

        final String lower = s.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);

            if (UZ_CYRL_MARKERS.indexOf(c) >= 0) uzMark++;
            if (RU_CYRL_MARKERS.indexOf(c) >= 0) ruMark++;
            if (LATN_2026_MARKERS.indexOf(c) >= 0) n26++;

            // oʻ / gʻ in any apostrophe spelling is the 1995 signature
            if ((c == 'o' || c == 'g') && i + 1 < lower.length()
                    && Apostrophes.isApostrophe(lower.charAt(i + 1))) {
                n95++;
            }

            if (c >= 'a' && c <= 'z') latin++;
            else if (LATN_2026_MARKERS.indexOf(c) >= 0) latin++;
            else if (NON_UZBEK_LATIN.indexOf(c) >= 0) latin++;
            else if (c >= 'Ѐ' && c <= 'ӿ') cyrillic++;
            else if (isArabicLetter(c)) arabic++;
            else if (Character.isDigit(c)) digit++;
            else other++;
        }

        int letters = latin + cyrillic + arabic;
        if (letters == 0) {
            return new Detection(ScriptId.NON_LINGUISTIC, 1.0,
                    latin, cyrillic, arabic, digit, other, uzMark, ruMark, n26, n95);
        }

        // Two or more scripts each holding a real share => per-token work needed.
        int scripts = (latin > 0 ? 1 : 0) + (cyrillic > 0 ? 1 : 0) + (arabic > 0 ? 1 : 0);
        if (scripts > 1) {
            int max = Math.max(latin, Math.max(cyrillic, arabic));
            if ((double) max / letters < 0.85) {
                return new Detection(ScriptId.MIXED, (double) max / letters,
                        latin, cyrillic, arabic, digit, other, uzMark, ruMark, n26, n95);
            }
        }

        ScriptId id;
        double conf;

        if (arabic >= latin && arabic >= cyrillic) {
            id = ScriptId.UZ_ARAB;
            conf = (double) arabic / letters;
        } else if (cyrillic > latin) {
            conf = (double) cyrillic / letters;
            if (uzMark > 0 && ruMark == 0)      id = ScriptId.UZ_CYRL;
            else if (ruMark > 0 && uzMark == 0) id = ScriptId.RU_CYRL;
            else if (uzMark > 0)                { id = ScriptId.UZ_CYRL; conf *= 0.6; }
            else                                { id = ScriptId.CYRL_AMBIGUOUS; conf *= 0.5; }
        } else {
            conf = (double) latin / letters;
            // The negative test runs first. A letter no Uzbek orthography has is
            // proof, while ö ğ ş ç are only a hint: çicago carries a 2026 letter
            // and is still not Uzbek.
            if (hasNonUzbekLatin(lower))      id = ScriptId.LATN_OTHER;
            else if (n26 > 0 && n95 == 0)     id = ScriptId.UZ_LATN_2026;
            else if (n95 > 0)                 id = ScriptId.UZ_LATN_1995;
            else                              { id = ScriptId.LATN_UNDETERMINED; conf *= 0.5; }
        }

        return new Detection(id, conf, latin, cyrillic, arabic, digit, other,
                uzMark, ruMark, n26, n95);
    }

    /**
     * A SOUND negative test: does the string contain a Latin letter the Uzbek
     * alphabet does not have?
     *
     * <p>Uzbek Latin is {@code a b d e f g h i j k l m n o p q r s t u v x y z}
     * plus {@code oʻ gʻ sh ch ng}. There is no {@code w}, and no standalone
     * {@code c} — {@code c} occurs only inside the {@code ch} digraph. Either
     * one proves the string is not Uzbek. So do {@code ı} and {@code ŝ}, which
     * the normalizer uses to carry Russian {@code ы} and {@code щ}.
     *
     * <p>This only holds on raw text or on the internal form, where {@code ç} is
     * still a letter of its own. On the search key {@code ç} has become {@code c},
     * and every word spelled with {@code ch} would read as foreign.
     *
     * <p>Deliberately NOT a positive test. {@code q}, {@code x}, {@code sh},
     * {@code ng} were tried as positive Uzbek evidence and are worthless:
     * "Galaxy" has {@code x}, "Samsung" has {@code ng}, "shirt" has {@code sh}.
     * A string of otherwise-legal letters is genuinely undecidable here, so the
     * detector says so rather than guessing.
     */
    private static boolean hasNonUzbekLatin(String lower) {
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == 'w') return true;
            if (NON_UZBEK_LATIN.indexOf(c) >= 0) return true;
            if (c == 'c') {
                boolean partOfCh = i + 1 < lower.length() && lower.charAt(i + 1) == 'h';
                if (!partOfCh) return true;
            }
        }
        return false;
    }

    private static boolean isArabicLetter(char c) {
        return (c >= '؀' && c <= 'ۿ')
            || (c >= 'ݐ' && c <= 'ݿ')
            || (c >= 'ﭐ' && c <= '﷿')
            || (c >= 'ﹰ' && c <= '﻿');
    }
}
