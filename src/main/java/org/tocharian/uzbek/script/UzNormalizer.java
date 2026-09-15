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

    /** Fold using a known orthography (use when Layer 0 ran at a coarser granularity). */
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

        switch (script) {
            case UZ_CYRL, RU_CYRL, CYRL_AMBIGUOUS -> foldCyrillic(nfc, out, src, amb);
            case UZ_ARAB                           -> foldArabic(nfc, out, src, amb);
            case MIXED                             -> foldMixed(nfc, out, src, amb);
            default                                -> foldLatin(nfc, out, src, amb);
        }

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
     */
    public static String toSearchKey(String internal) {
        StringBuilder sb = new StringBuilder(internal.length());
        for (int i = 0; i < internal.length(); i++) {
            char c = internal.charAt(i);
            switch (c) {
                case 'ö' -> sb.append('o');            // ö -> o
                case 'ğ' -> sb.append('g');            // ğ -> g
                case 'ç' -> sb.append('c');            // ç -> c   (collision-free)
                case Apostrophes.TUTUQ, Apostrophes.TURNED_COMMA -> { /* drop */ }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- Latin

    private static void foldLatin(String s, StringBuilder out, List<Integer> src,
                                 List<NormalizedForm.Ambiguity> amb) {
        int n = s.length();
        int i = 0;
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

    private static void foldCyrillic(String s, StringBuilder out, List<Integer> src,
                                     List<NormalizedForm.Ambiguity> amb) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
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
                case 'ы' -> rep = "i";   // ы (Russian only) — unmapped would leak into the key
                case 'ь' -> rep = "";    // ь — dropped, see the class note
                case 'щ' -> rep = "şç"; // щ (Russian only) -> şç
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
    private static void foldArabic(String s, StringBuilder out, List<Integer> src,
                                  List<NormalizedForm.Ambiguity> amb) {
        ArabicScript.Transliteration t = ArabicScript.toLatin(s);

        StringBuilder latinOut = new StringBuilder(t.latin().length());
        List<Integer> latinSrc = new ArrayList<>(t.latin().length());
        foldLatin(t.latin(), latinOut, latinSrc, amb);

        for (int i = 0; i < latinOut.length(); i++) {
            int viaLatin = latinSrc.get(i);
            int original = viaLatin < t.srcIndex().length ? t.srcIndex()[viaLatin] : 0;
            emit(out, src, latinOut.charAt(i), original);
        }

        amb.add(new NormalizedForm.Ambiguity(0, out.toString(), List.of("unwritten-vowels"),
                "arabic.transliterated"));
    }

    /** Per-character dispatch for strings that genuinely mix scripts. */
    private static void foldMixed(String s, StringBuilder out, List<Integer> src,
                                 List<NormalizedForm.Ambiguity> amb) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            int j = i;
            boolean cyr = isCyrillic(s.charAt(i));
            while (j < n && isCyrillic(s.charAt(j)) == cyr) j++;
            String run = s.substring(i, j);

            StringBuilder sub = new StringBuilder();
            List<Integer> subSrc = new ArrayList<>();
            if (cyr) foldCyrillic(run, sub, subSrc, amb);
            else     foldLatin(run, sub, subSrc, amb);

            for (int k = 0; k < sub.length(); k++) {
                emit(out, src, sub.charAt(k), i + subSrc.get(k));
            }
            i = j;
        }
    }

    private static boolean isCyrillic(char c) {
        return c >= 'Ѐ' && c <= 'ӿ';
    }

    private static void emit(StringBuilder out, List<Integer> src, char c, int srcPos) {
        out.append(c);
        src.add(srcPos);
    }
}
