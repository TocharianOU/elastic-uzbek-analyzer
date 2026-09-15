/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Perso-Arabic to Latin, for the {@code uzs} variety.
 *
 * <p>Output is the 1995 Latin orthography — {@code oʻ}, {@code gʻ}, {@code sh},
 * {@code ch} — which {@link UzNormalizer} already knows how to fold, so this
 * stage only has to reach a script the rest of the pipeline speaks.
 *
 * <p>Matching is longest-key-first, because several sequences are multi-character:
 * {@code اۉ} is one vowel, and the joining forms carry a tatweel that has to be
 * consumed with the letter it belongs to.
 *
 * <p><b>This is lossy, and unavoidably so.</b> The script is an abjad: short
 * vowels are frequently unwritten, so the same consonant skeleton can stand for
 * several words and no table can recover which. Position decides whether
 * {@code و} and {@code ی} are consonants or vowels, which is worth doing — the
 * source table calls them consonants everywhere, turning {@code قوپقوغی} into
 * {@code qvpqvgʻi} — but it cannot decide WHICH vowel, and the common value is
 * taken. It is a large improvement on
 * passing the text through untouched — a Perso-Arabic listing becomes findable at
 * all — but it is not the equal of the Latin and Cyrillic paths, and should not
 * be presented as one.
 *
 * <p>Mapping derived from the transliteration table of the Lutfiy project
 * (Tahrirchi, MIT). Independent implementation.
 */
public final class ArabicScript {

    private ArabicScript() {}

    /** The intermediate Latin text, and where each character came from. */
    public record Transliteration(String latin, int[] srcIndex) {}

    /** Longest key first, so multi-character sequences win. */
    private static final Map<String, String> MAP = buildMap();

    private static final int MAX_KEY_LENGTH;
    static {
        int max = 0;
        for (String k : MAP.keySet()) max = Math.max(max, k.length());
        MAX_KEY_LENGTH = max;
    }

    /** True if the string carries any Perso-Arabic letter. */
    public static boolean isArabicScript(char c) {
        return (c >= '؀' && c <= 'ۿ')
            || (c >= 'ݐ' && c <= 'ݿ')
            || (c >= 'ﭐ' && c <= '﷿')
            || (c >= 'ﹰ' && c <= '﻿');
    }

    /**
     * Transliterate to the 1995 Latin orthography, tracking the source index of
     * every character produced. Anything unmapped is passed through so that the
     * leak guard downstream can report it rather than have it vanish.
     */
    public static Transliteration toLatin(String input) {
        StringBuilder out = new StringBuilder(input.length() * 2);
        List<Integer> src = new ArrayList<>(input.length() * 2);

        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            // Zero-width joiners carry no sound; they only shape the glyphs.
            if (c == '‌' || c == '‍' || c == '​' || c == '﻿') {
                i++;
                continue;
            }
            // Harakat: the short-vowel marks are part of the keys above when they
            // matter, and noise on their own.
            if (c >= 'ً' && c <= 'ْ') {
                i++;
                continue;
            }

            // و and ی are matres lectionis: each serves as a consonant and as a
            // vowel, and the source table treats both as consonants throughout,
            // which turns بویوک into "bvivk" and قوپقوغی into "qvpqvgʻi". Position
            // settles the consonant/vowel question — after a consonant they are
            // vowels — even though it cannot settle WHICH vowel.
            if (c == '\u0648' || c == '\u06CC') {
                // Decide against what was actually produced, not against the
                // source letter. The character before may itself have been one of
                // these two, and only its resolution says whether a vowel is
                // already in place: in بویوک the و becomes a vowel, so the ی that
                // follows is the consonant y.
                char prev = lastLetter(input, i);
                boolean consonantal = prev == 0 || isVowelLetter(prev)
                        || (out.length() > 0 && isLatinVowel(out.charAt(out.length() - 1)));
                String mapped = (c == '\u0648')
                        ? (consonantal ? "v" : "o")
                        : (consonantal ? "y" : "i");
                out.append(mapped);
                src.add(i);
                i++;
                continue;
            }

            String hit = null;
            int hitLen = 0;
            int maxLen = Math.min(MAX_KEY_LENGTH, input.length() - i);
            for (int len = maxLen; len >= 1; len--) {
                String candidate = input.substring(i, i + len);
                String mapped = MAP.get(candidate);
                if (mapped != null) {
                    hit = mapped;
                    hitLen = len;
                    break;
                }
            }

            if (hit == null) {
                out.append(c);
                src.add(i);
                i++;
            } else {
                for (int k = 0; k < hit.length(); k++) {
                    out.append(hit.charAt(k));
                    src.add(i);
                }
                i += hitLen;
            }
        }

        int[] idx = new int[src.size()];
        for (int k = 0; k < idx.length; k++) idx[k] = src.get(k);
        return new Transliteration(out.toString(), idx);
    }

    private static boolean isLatinVowel(char c) {
        return "aeiou\u00F6".indexOf(c) >= 0;
    }

    /** Unambiguous vowel letters: after one of these, و and ی are consonants. */
    private static boolean isVowelLetter(char c) {
        return c == '\u0627'    // ا
            || c == '\u0622'    // آ
            || c == '\u06D0'    // ې
            || c == '\u06C9'    // ۉ
            || c == '\u0647';   // ه  (word-final a/e)
    }

    /** The previous letter, skipping joiners and harakat; 0 at a word boundary. */
    private static char lastLetter(String s, int from) {
        for (int i = from - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\u200C' || c == '\u200D' || c == '\u200B' || c == '\u0640') continue;
            if (c >= '\u064B' && c <= '\u0652') continue;
            return isArabicScript(c) ? c : 0;
        }
        return 0;
    }

    private static Map<String, String> buildMap() {
        String[] pairs = {
            "\u0627\u0650\u06CC\u0640", "i",
            "\u0640\u0646\u06AF\u0640", "ng",
            "\u0627\u0650\u06CC", "i",
            "\u0627\u06D0\u0640", "e",
            "\u0627\u064F\u0648", "u",
            "\u0640\u064E\u0647", "a",
            "\u0640\u0650\u06CC", "i",
            "\u0640\u0650\u0647", "e",
            "\u0640\u064F\u0648", "u",
            "\u0640\u0628\u0640", "b",
            "\u0640\u067E\u0640", "p",
            "\u0640\u062A\u0640", "t",
            "\u0640\u062B\u0640", "s",
            "\u0640\u062C\u0640", "j",
            "\u0640\u0686\u0640", "ch",
            "\u0640\u062D\u0640", "h",
            "\u0640\u062E\u0640", "x",
            "\u0640\u0633\u0640", "s",
            "\u0640\u0634\u0640", "sh",
            "\u0640\u0635\u0640", "s",
            "\u0640\u0636\u0640", "z",
            "\u0640\u0637\u0640", "t",
            "\u0640\u0638\u0640", "z",
            "\u0640\u0639\u0640", "\u02BB",
            "\u0640\u063A\u0640", "g\u02BB",
            "\u0640\u0641\u0640", "f",
            "\u0640\u0642\u0640", "q",
            "\u0640\u06A9\u0640", "k",
            "\u0640\u06AF\u0640", "g",
            "\u0640\u0644\u0640", "l",
            "\u0640\u0645\u0640", "m",
            "\u0640\u0646\u0640", "n",
            "\u0646\u06AF\u0640", "ng",
            "\u0640\u0646\u06AF", "ng",
            "\u0640\u0647\u0640", "h",
            "\u0640\u06CC\u0640", "i",
            "\u0640\u0626\u0640", "\u02BB",
            "\u0627\u064E", "a",
            "\u0627\u06D0", "e",
            "\u0627\u06C9", "o\u02BB",
            "\u0627\u0648", "u",
            "\u0627\u0650", "i",
            "\u0627\u064F", "u",
            "\u0640\u0647", "h",
            "\u0640\u064E", "a",
            "\u0640\u0627", "o",
            "\u0640\u06D0", "e",
            "\u0640\u0650", "i",
            "\u0640\u06C9", "o\u02BB",
            "\u0640\u064F", "u",
            "\u0628\u0640", "b",
            "\u0640\u0628", "b",
            "\u067E\u0640", "p",
            "\u0640\u067E", "p",
            "\u062A\u0640", "t",
            "\u0640\u062A", "t",
            "\u062B\u0640", "s",
            "\u0640\u062B", "s",
            "\u062C\u0640", "j",
            "\u0640\u062C", "j",
            "\u0686\u0640", "ch",
            "\u0640\u0686", "ch",
            "\u062D\u0640", "h",
            "\u0640\u062D", "h",
            "\u062E\u0640", "x",
            "\u0640\u062E", "x",
            "\u0640\u062F", "d",
            "\u0640\u0630", "z",
            "\u0640\u0631", "r",
            "\u0640\u0632", "z",
            "\u0640\u0698", "j",
            "\u0633\u0640", "s",
            "\u0640\u0633", "s",
            "\u0634\u0640", "sh",
            "\u0640\u0634", "sh",
            "\u0635\u0640", "s",
            "\u0640\u0635", "s",
            "\u0636\u0640", "z",
            "\u0640\u0636", "z",
            "\u0637\u0640", "t",
            "\u0640\u0637", "t",
            "\u0638\u0640", "z",
            "\u0640\u0638", "z",
            "\u0639\u0640", "\u02BB",
            "\u0640\u0639", "\u02BB",
            "\u063A\u0640", "g\u02BB",
            "\u0640\u063A", "g\u02BB",
            "\u0641\u0640", "f",
            "\u0640\u0641", "f",
            "\u0642\u0640", "q",
            "\u0640\u0642", "q",
            "\u06A9\u0640", "k",
            "\u0640\u06A9", "k",
            "\u06AF\u0640", "g",
            "\u0640\u06AF", "g",
            "\u0644\u0640", "l",
            "\u0640\u0644", "l",
            "\u0645\u0640", "m",
            "\u0640\u0645", "m",
            "\u0646\u0640", "n",
            "\u0640\u0646", "n",
            "\u0646\u06AF", "ng",
            "\u0640\u0648", "v",
            "\u0647\u0640", "h",
            "\u06CC\u0640", "i",
            "\u0640\u06CC", "i",
            "\u0626\u0640", "\u02BB",
            "\u0640\u0623", "\u02BB",
            "\u0640\u0624", "\u02BB",
            "\u0622", "o",
            "\u0640", "a",
            "\u064A", "i",
            "\u0627", "a",
            "\u06D0", "e",
            "\u06C9", "o\u02BB",
            "\u06CC", "i",
            "\u0648", "v",
            "\u0628", "b",
            "\u067E", "p",
            "\u062A", "t",
            "\u062B", "s",
            "\u062C", "j",
            "\u0686", "ch",
            "\u062D", "h",
            "\u062E", "x",
            "\u062F", "d",
            "\u0630", "z",
            "\u0631", "r",
            "\u0632", "z",
            "\u0698", "j",
            "\u0633", "s",
            "\u0634", "sh",
            "\u0635", "s",
            "\u0636", "z",
            "\u0637", "t",
            "\u0638", "z",
            "\u0639", "\u02BB",
            "\u063A", "g\u02BB",
            "\u0641", "f",
            "\u0642", "q",
            "\u06A9", "k",
            "\u06AF", "g",
            "\u0644", "l",
            "\u0645", "m",
            "\u0646", "n",
            "\u0647", "h",
            "\u0621", "\u02BB",
            "\u0623", "\u02BB",
            "\u0624", "\u02BB",        };
        Map<String, String> m = new LinkedHashMap<>(pairs.length);
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return Map.copyOf(m);
    }
}
