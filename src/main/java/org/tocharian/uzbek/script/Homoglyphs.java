/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

/**
 * Layer 1, step 0 — repair Cyrillic/Latin homoglyphs before anything else runs.
 *
 * <p>Uzbek is the worst case for this class of bug: the same person types both
 * alphabets daily and keyboard layouts sit one shortcut apart, so a single
 * Cyrillic {@code е} (U+0435) lands inside an otherwise-Latin word and is
 * invisible. Found in the wild in a curated 46k-stem academic lexicon, on core
 * verb stems: {@code kеl} "come", {@code sеv} "love", {@code tеp} "kick",
 * {@code kеs} "cut".
 *
 * <p>Left alone, such a token normalizes fine but is classified {@code MIXED},
 * so morphology is skipped and it never matches a clean query. In a product
 * catalogue that is a listing nobody can find.
 *
 * <p>The repair direction is decided by the unambiguous letters around it: in a
 * run that is otherwise Latin, homoglyphs become Latin, and vice versa. Only
 * characters that are visually identical across the two scripts are touched.
 */
public final class Homoglyphs {

    /**
     * Cyrillic letters that are visually identical to a Latin letter AT LOWERCASE,
     * and that Latin letter. Repair runs after case folding, so only lowercase
     * identity counts.
     *
     * <p>Deliberately excludes {@code к м т н в}: those look like {@code K M T H B}
     * in capitals but nothing like {@code k m t h b} in lowercase. Including them
     * was actively harmful — it made words such as {@code chek} consist entirely of
     * "homoglyphs", leaving no unambiguous letter to decide the repair direction.
     */
    private static final char[][] CYR_TO_LAT = {
        {'\u0430', 'a'},  // а
        {'\u0435', 'e'},  // е
        {'\u043E', 'o'},  // о
        {'\u0440', 'p'},  // р
        {'\u0441', 'c'},  // с
        {'\u0443', 'y'},  // у
        {'\u0445', 'x'},  // х
        {'\u0456', 'i'},  // і  (Ukrainian i, common in pasted text)
        {'\u0458', 'j'},  // ј
        {'\u0455', 's'},  // ѕ
        {'\u04CF', 'l'},  // ӏ
    };

    private Homoglyphs() {}

    public record Repair(String text, int replaced) {}

    /**
     * Repair homoglyphs per word, in two passes.
     *
     * <p>Pass 1 establishes the dominant script of the whole input from letters
     * that are unambiguous. Pass 2 repairs each word using that word's own
     * unambiguous letters, falling back to the input-level dominant script for
     * words that have none — a short word can consist entirely of shared-shape
     * letters ({@code sep} is s+e+p, all three identical in both alphabets), and
     * then only the surrounding text can decide.
     */
    public static Repair repair(String s) {
        if (s == null || s.isEmpty()) return new Repair(s == null ? "" : s, 0);

        // Pass 1: which script does the input as a whole belong to?
        int docLatin = 0, docCyrillic = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = Character.toLowerCase(s.charAt(k));
            if (isHomoglyph(c)) continue;
            if (c >= 'a' && c <= 'z') docLatin++;
            else if (c >= '\u0400' && c <= '\u04FF') docCyrillic++;
        }
        final int docBias = Integer.compare(docLatin, docCyrillic);   // >0 Latin, <0 Cyrillic

        StringBuilder out = new StringBuilder(s.length());
        int replaced = 0;
        int i = 0;
        int n = s.length();

        while (i < n) {
            if (!Character.isLetter(s.charAt(i))) {
                out.append(s.charAt(i));
                i++;
                continue;
            }
            // An apostrophe between two letters is part of the word: oʻ and gʻ are
            // letters. Splitting there left the i of qopqog'i as a word of its
            // own, with no evidence, so a Cyrillic context turned it into і.
            int j = i;
            while (j < n && (Character.isLetter(s.charAt(j))
                    || (Apostrophes.isApostrophe(s.charAt(j))
                        && j + 1 < n && Character.isLetter(s.charAt(j + 1))))) j++;
            String word = s.substring(i, j);

            int latinOnly = 0, cyrillicOnly = 0;
            for (int k = 0; k < word.length(); k++) {
                char c = Character.toLowerCase(word.charAt(k));
                if (isHomoglyph(c)) continue;                 // carries no evidence
                if (c >= 'a' && c <= 'z') latinOnly++;
                else if (c >= 'Ѐ' && c <= 'ӿ') cyrillicOnly++;
            }

            // No evidence inside the word: defer to the input-level dominant script.
            if (latinOnly == 0 && cyrillicOnly == 0) {
                if (docBias > 0) latinOnly = 1;
                else if (docBias < 0) cyrillicOnly = 1;
            }

            if (latinOnly > 0 && cyrillicOnly == 0) {
                for (int k = 0; k < word.length(); k++) {
                    char c = word.charAt(k);
                    char lat = toLatin(Character.toLowerCase(c));
                    if (lat != 0) { out.append(lat); replaced++; }
                    else out.append(c);
                }
            } else if (cyrillicOnly > 0 && latinOnly == 0) {
                for (int k = 0; k < word.length(); k++) {
                    char c = word.charAt(k);
                    char cyr = toCyrillic(Character.toLowerCase(c));
                    if (cyr != 0) { out.append(cyr); replaced++; }
                    else out.append(c);
                }
            } else {
                out.append(word);   // no evidence, or genuinely both: leave alone
            }
            i = j;
        }
        return new Repair(out.toString(), replaced);
    }

    private static boolean isHomoglyph(char lower) {
        for (char[] pair : CYR_TO_LAT) {
            if (lower == pair[0] || lower == pair[1]) return true;
        }
        return false;
    }

    /** Cyrillic homoglyph -> its Latin twin, or 0 if not one. */
    private static char toLatin(char lower) {
        for (char[] pair : CYR_TO_LAT) if (lower == pair[0]) return pair[1];
        return 0;
    }

    /** Latin homoglyph -> its Cyrillic twin, or 0 if not one. */
    private static char toCyrillic(char lower) {
        for (char[] pair : CYR_TO_LAT) if (lower == pair[1]) return pair[0];
        return 0;
    }
}
