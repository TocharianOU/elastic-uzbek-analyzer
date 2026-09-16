/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Layer 1 — fold any Uzbek orthography to one internal form.
 *
 * <p>The internal form is the Latin alphabet approved in September 2026:
 * {@code ö ğ ş ç} replace {@code oʻ gʻ sh ch}. That gives one codepoint per
 * phoneme, which is what lets every later layer use plain character logic
 * instead of fighting digraph boundaries.
 *
 * <p>{@code ng} is deliberately NOT collapsed to a single letter. It is a
 * digraph in the official alphabet, and collapsing it would be wrong across
 * morpheme boundaries: {@code non+ga → nonga} is /n/+/g/, not /ŋ/. Since
 * standard Uzbek has no productive vowel harmony, no later layer needs /ŋ/ as
 * an atomic segment — treating the final {@code g} as a voiced consonant
 * already selects the right {@code -ga}/{@code -da} allomorphs.
 *
 * <p>This is a search normalizer, not a transliterator, and the two want
 * different things. A transliterator must produce text a human reads, so it
 * resolves the Cyrillic soft sign into a written glide ({@code batalьon} ->
 * {@code batalyon}). A search normalizer only needs index and query to agree,
 * so {@code ь} is simply dropped on both sides. Where this diverges from
 * reference transliterators the reason is consistency, not oversight — with one
 * exception that was a real bug: {@code ы} must map to {@code i}, because
 * leaving it unmapped lets a Cyrillic character survive into the key, and no
 * Latin query can ever produce it.
 *
 * <p>Script is decided per run of characters, not per field. A catalogue title
 * such as {@code Samsung Galaxy qopqogʻi телефон} is mostly Latin, and a
 * field-level verdict would fold the Cyrillic word with the Latin rules, which
 * leaves it Cyrillic and unreachable by any Latin query. Each run of Cyrillic,
 * Perso-Arabic or other text therefore goes through its own table, and the
 * field-level {@link ScriptId} is kept only as a description of the input.
 *
 * <p>Russian {@code ы} and {@code щ} are written into the internal form as
 * {@code ı} and {@code ŝ}, letters no Uzbek orthography uses, and fold to
 * {@code i} and {@code şc} only in the search key. That is what lets the token
 * filter still see that a token is Russian after the char filter has made it
 * Latin. The terms in the index are the same as before.
 *
 * <p>Locale note: normalization uses {@link Locale#ROOT}, never Turkish.
 * Uzbek Latin has no dotless {@code ı}, so Turkish casing rules would corrupt
 * every {@code i}.
 */
public final class UzNormalizer {

    private UzNormalizer() {}

    // ---------------------------------------------------------------- API

    /** Detect the orthography, then fold. */
    public static NormalizedForm normalize(String input) {
        if (input == null || input.isEmpty()) {
            return new NormalizedForm("", "", "", ScriptId.UNKNOWN, new int[0], List.of());
        }
        // Detect on the homoglyph-repaired text: a stray Cyrillic ‘е’ inside a
        // Latin word would otherwise be classified MIXED and skip morphology.
        String probe = Homoglyphs.repair(
                Normalizer.normalize(input, Normalizer.Form.NFC)).text();
        return normalize(input, ScriptDetector.detect(probe).script());
    }

    /**
     * Fold, recording {@code script} as the source orthography. The fold itself
     * does not depend on it: every run of characters is folded by its own script.
     */
    public static NormalizedForm normalize(String input, ScriptId script) {
        if (input == null || input.isEmpty()) {
            return new NormalizedForm("", "", "", script, new int[0], List.of());
        }

        // NOTE offsets: srcIndex is measured against the NFC form. NFC is
        // length-preserving for pre-composed Latin and Cyrillic input, which is
        // what the char_filter chain receives in practice. Feed decomposed
        // (NFD) text through an upstream icu_normalizer if exact offsets on
        // such input matter. Homoglyph repair below is strictly 1:1, so it
        // never shifts an offset.
        String nfc = Normalizer.normalize(input, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        nfc = Homoglyphs.repair(nfc).text();

        StringBuilder out = new StringBuilder(nfc.length());
        List<Integer> src = new ArrayList<>(nfc.length());
        List<NormalizedForm.Ambiguity> amb = new ArrayList<>();

        // The script argument only labels the result. Folding is decided run by
        // run, so a minority script inside a field is folded by its own table.
        foldRuns(nfc, out, src, amb);

        String internal = out.toString();

        // Leak guard. Any Cyrillic codepoint still standing is a gap in the
        // mapping table, and a gap is silent data loss: a token such as
        // "nov\u044By" can never match a Latin query. Surfaced, not swallowed.
        for (int i = 0; i < internal.length(); i++) {
            char c = internal.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') {
                amb.add(new NormalizedForm.Ambiguity(i, String.valueOf(c),
                        List.of("UNMAPPED"), "cyrillic.unmapped"));
            }
        }
        int[] srcIndex = new int[src.size()];
        for (int i = 0; i < srcIndex.length; i++) srcIndex[i] = src.get(i);

        return new NormalizedForm(input, internal, toSearchKey(internal), script, srcIndex, amb);
    }

    /**
     * Collapse the one ambiguity that real input actually creates.
     *
     * <p>{@code ö→o} and {@code ğ→g} because the apostrophe in {@code oʻ}/{@code gʻ}
     * is omitted constantly; the collisions this causes ({@code oʻt} "grass" vs
     * {@code ot} "horse") are rarer than the recall it buys. {@code ç→c} is free —
     * {@code c} is not a letter of the Uzbek alphabet, so nothing can collide with it.
     *
     * <p>{@code ş} is deliberately kept. {@code sh} is a digraph, never written with
     * an apostrophe, so users do not mistype it — folding it to {@code s} would add
     * collisions ({@code bosh} "head" vs {@code bos} "press") for no recall gain.
     *
     * <p>Because {@code ç} becomes {@code c} here, the key must never be used to
     * decide which script or language a token is in. Run {@link ScriptDetector}
     * on the internal form instead.
     */
    public static String toSearchKey(String internal) {
        StringBuilder sb = new StringBuilder(internal.length());
        for (int i = 0; i < internal.length(); i++) {
            char c = internal.charAt(i);
            switch (c) {
                case 'ö' -> sb.append('o');            // ö -> o
                case 'ğ' -> sb.append('g');            // ğ -> g
                case 'ç' -> sb.append('c');            // ç -> c   (collision-free)
                case 'ı' -> sb.append('i');            // Russian ы, see the class note
                case 'ŝ' -> sb.append("şc");           // Russian щ
                case Apostrophes.TUTUQ, Apostrophes.TURNED_COMMA -> { /* drop */ }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- Latin

    private static void foldLatin(String s, StringBuilder out, List<Integer> src,
                                 List<NormalizedForm.Ambiguity> amb) {
        foldLatin(s, 0, s.length(), out, src, amb);
    }

    /** Fold {@code s[from, to)}. Look-ahead stops at {@code to}. */
    private static void foldLatin(String s, int from, int to, StringBuilder out, List<Integer> src,
                                 List<NormalizedForm.Ambiguity> amb) {
        int n = to;
        int i = from;
        while (i < n) {
            char c = s.charAt(i);

            // oʻ / gʻ in any apostrophe spelling -> ö / ğ
            if ((c == 'o' || c == 'g') && i + 1 < n && Apostrophes.isApostrophe(s.charAt(i + 1))) {
                emit(out, src, c == 'o' ? 'ö' : 'ğ', i);
                i += 2;
                continue;
            }

            // sʼh : tutuq belgisi separating s+h from the digraph sh (Isʼhoq)
            if (c == 's' && i + 2 < n && Apostrophes.isApostrophe(s.charAt(i + 1))
                    && s.charAt(i + 2) == 'h') {
                emit(out, src, 's', i);
                emit(out, src, 'h', i + 2);
                i += 3;
                continue;
            }

            // digraphs
            if (c == 's' && i + 1 < n && s.charAt(i + 1) == 'h') {
                emit(out, src, 'ş', i);                 // ş
                i += 2;
                continue;
            }
            if (c == 'c' && i + 1 < n && s.charAt(i + 1) == 'h') {
                emit(out, src, 'ç', i);                 // ç
                i += 2;
                continue;
            }

            // a lone apostrophe is the tutuq belgisi
            if (Apostrophes.isApostrophe(c)) {
                emit(out, src, Apostrophes.TUTUQ, i);
                i++;
                continue;
            }

            // s-with-comma-below is a font lookalike for ş, not a distinct letter
            if (c == 'ș') {
                emit(out, src, 'ş', i);
                i++;
                continue;
            }
            if (c == 'ț') {                            // ț -> t
                emit(out, src, 't', i);
                i++;
                continue;
            }

            emit(out, src, c, i);
            i++;
        }
    }

    // ---------------------------------------------------------- Cyrillic

    private static final String CYR_VOWELS = "аеёиоуўэюя";
    // а е ё и о у ў э ю я

    /**
     * Fold {@code s[from, to)}. The positional rules for {@code е} and {@code ц}
     * look at the character before, and read it from the whole string, so a
     * word is not treated as starting where the run happens to start.
     */
    private static void foldCyrillic(String s, int from, int to, StringBuilder out, List<Integer> src,
                                     List<NormalizedForm.Ambiguity> amb) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            String rep;

            switch (c) {
                case 'а' -> rep = "a";   // а
                case 'б' -> rep = "b";   // б
                case 'в' -> rep = "v";   // в
                case 'г' -> rep = "g";   // г
                case 'ғ' -> rep = "ğ"; // ғ -> ğ
                case 'д' -> rep = "d";   // д
                case 'ж' -> rep = "j";   // ж
                case 'з' -> rep = "z";   // з
                case 'и' -> rep = "i";   // и
                case 'й' -> rep = "y";   // й
                case 'к' -> rep = "k";   // к
                case 'қ' -> rep = "q";   // қ
                case 'л' -> rep = "l";   // л
                case 'м' -> rep = "m";   // м
                case 'н' -> rep = "n";   // н
                case 'о' -> rep = "o";   // о
                case 'п' -> rep = "p";   // п
                case 'р' -> rep = "r";   // р
                case 'с' -> rep = "s";   // с
                case 'т' -> rep = "t";   // т
                case 'у' -> rep = "u";   // у
                case 'ў' -> rep = "ö"; // ў -> ö
                case 'ф' -> rep = "f";   // ф
                case 'х' -> rep = "x";   // х
                case 'ҳ' -> rep = "h";   // ҳ
                case 'ч' -> rep = "ç"; // ч -> ç
                case 'ш' -> rep = "ş"; // ш -> ş
                case 'ё' -> rep = "yo";  // ё
                case 'ю' -> rep = "yu";  // ю
                case 'я' -> rep = "ya";  // я
                case 'э' -> rep = "e";   // э
                case 'ъ' -> rep = String.valueOf(Apostrophes.TUTUQ); // ъ
                case 'ы' -> rep = "ı";   // ы (Russian only) -> ı, folds to i in the key
                case 'ь' -> rep = "";    // ь — dropped, see the class note
                case 'щ' -> rep = "ŝ";   // щ (Russian only) -> ŝ, folds to şc in the key
                case 'ц' -> {
                    // Uzbek writes Russian ц as ts only after a vowel
                    // (революция -> revolyutsiya) and as s otherwise, both
                    // word-initially (цирк -> sirk, цемент -> sement) and after
                    // a consonant (станция -> stansiya). A blanket ts is wrong
                    // far more often than it is right.
                    boolean afterVowel = i > 0 && CYR_VOWELS.indexOf(s.charAt(i - 1)) >= 0;
                    rep = afterVowel ? "ts" : "s";
                    amb.add(new NormalizedForm.Ambiguity(out.length(), rep,
                            List.of(afterVowel ? "s" : "ts"), "cyrillic.tse.positional"));
                }
                case 'е' -> {            // е : ye word-initially or after a vowel, else e
                    boolean initial = (i == 0) || !Character.isLetter(s.charAt(i - 1));
                    boolean afterVowel = i > 0 && CYR_VOWELS.indexOf(s.charAt(i - 1)) >= 0;
                    if (initial || afterVowel) {
                        rep = "ye";
                        amb.add(new NormalizedForm.Ambiguity(out.length(), "ye", List.of("e"),
                                "cyrillic.ye.positional"));
                    } else {
                        rep = "e";
                    }
                }
                default -> rep = String.valueOf(c);
            }

            for (int k = 0; k < rep.length(); k++) {
                emit(out, src, rep.charAt(k), i);
            }
        }
    }

    // ------------------------------------------------------------ Arabic

    /**
     * Perso-Arabic, ISO 639-3 {@code uzs}.
     *
     * <p>Transliterated to the 1995 Latin orthography first, then folded by the
     * Latin path, so there is one set of rules for {@code oʻ}, {@code sh} and the
     * rest rather than two that could drift apart. The two offset maps are
     * composed, so a highlight still lands on the original Perso-Arabic.
     *
     * <p>Lossy by nature: the script is an abjad and short vowels are often
     * unwritten, so a consonant skeleton can stand for several words. The result
     * is flagged as such — worth having, because it makes such a listing findable
     * at all, but not the equal of the Latin and Cyrillic paths.
     */
    private static void foldArabic(String s, int from, int to, StringBuilder out, List<Integer> src,
                                  List<NormalizedForm.Ambiguity> amb) {
        ArabicScript.Transliteration t = ArabicScript.toLatin(s.substring(from, to));

        StringBuilder latinOut = new StringBuilder(t.latin().length());
        List<Integer> latinSrc = new ArrayList<>(t.latin().length());
        foldLatin(t.latin(), latinOut, latinSrc, amb);

        int runStart = out.length();
        for (int i = 0; i < latinOut.length(); i++) {
            int viaLatin = latinSrc.get(i);
            int original = from + (viaLatin < t.srcIndex().length ? t.srcIndex()[viaLatin] : 0);
            emit(out, src, latinOut.charAt(i), original);
        }

        amb.add(new NormalizedForm.Ambiguity(runStart, out.substring(runStart),
                List.of("unwritten-vowels"), "arabic.transliterated"));
    }

    // -------------------------------------------------------------- runs

    private enum Run { CYRILLIC, ARABIC, OTHER }

    /**
     * Split the input into maximal runs of one script and fold each with its own
     * table. Spaces, digits and punctuation belong to the OTHER run and pass
     * through the Latin path unchanged.
     */
    private static void foldRuns(String s, StringBuilder out, List<Integer> src,
                                 List<NormalizedForm.Ambiguity> amb) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            Run kind = runOf(s.charAt(i));
            int j = i + 1;
            while (j < n && continuesRun(kind, s.charAt(j))) j++;
            switch (kind) {
                case CYRILLIC -> foldCyrillic(s, i, j, out, src, amb);
                case ARABIC   -> foldArabic(s, i, j, out, src, amb);
                default       -> foldLatin(s, i, j, out, src, amb);
            }
            i = j;
        }
    }

    private static Run runOf(char c) {
        if (isCyrillic(c)) return Run.CYRILLIC;
        if (ArabicScript.isArabicScript(c)) return Run.ARABIC;
        return Run.OTHER;
    }

    /** Zero-width joiners shape Perso-Arabic glyphs, so they stay inside that run. */
    private static boolean continuesRun(Run kind, char c) {
        if (kind == Run.ARABIC && (c == '\u200B' || c == '\u200C' || c == '\u200D')) return true;
        return runOf(c) == kind;
    }

    private static boolean isCyrillic(char c) {
        return c >= 'Ѐ' && c <= 'ӿ';
    }

    private static void emit(StringBuilder out, List<Integer> src, char c, int srcPos) {
        out.append(c);
        src.add(srcPos);
    }
}
