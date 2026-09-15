/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.morph;

import org.tocharian.uzbek.protect.Protection;
import org.tocharian.uzbek.protect.ProtectionVerdict;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 4 — reduce an inflected token to its root.
 *
 * <p>Uzbek is agglutinative, so {@code kitoblarimizda} and {@code kitob} are the
 * same product to a shopper. Recall depends on them reaching the same term.
 *
 * <p>This is a validated suffix stripper, not a finite-state morphotactic parser,
 * and the choice is driven by the data: the affix inventory it is built from
 * enumerates whole affix <i>chains</i> rather than slots — {@code -larimizdangina}
 * is one entry — so there is no morphotactic grammar to reconstruct. Stripping the
 * longest known affix and then requiring the remainder to be a real root gives the
 * same answer with far less that can go wrong. An affix is only ever removed if
 * what is left is a word.
 *
 * <p>Two morphophonemic processes defeat plain stripping, and both are handled
 * from tables rather than guessed:
 * <ul>
 *   <li>a dropped vowel — {@code ayir + il} surfaces as {@code ayril}, so the
 *       remainder {@code ayr} has to resolve back to {@code ayir};</li>
 *   <li>a voiced final stop — {@code tilak + im} surfaces as {@code tilagim}, so
 *       a remainder ending in {@code g} may restore to {@code k} or {@code q}.</li>
 * </ul>
 *
 * <p>Everything here operates on the Layer 1 search key, never on raw text.
 *
 * <p>Thread-safe and immutable after construction. Load once, share.
 */
public final class UzMorphAnalyzer {

    private static final String ROOTS      = "/uz_morph/roots.tsv";
    private static final String AFFIXES    = "/uz_morph/affixes.tsv";
    private static final String ALLOMORPHS = "/uz_morph/stem-allomorphs.tsv";

    /** Shortest string allowed to remain after stripping against the lexicon. */
    private static final int MIN_ROOT = 3;

    /** Shortest remainder accepted by the out-of-vocabulary fallback, which has no
     *  lexicon to check its work and so is held to a stricter minimum. */
    private static final int MIN_OOV_ROOT = 4;

    /**
     * The only affixes the out-of-vocabulary fallback may remove.
     *
     * <p>Product titles are full of words no lexicon has: new brands, compounds,
     * derived forms such as {@code oʻqituvchi} that are built from derivational
     * suffixes the inflectional table does not carry. Leaving those completely
     * unanalyzed costs real recall, but stripping them with the full 2,464-surface
     * table would be guessing.
     *
     * <p>So the fallback is restricted to nominal inflections that are long enough
     * and distinctive enough to be worth the bet, ordered longest first. Short or
     * vowel-only affixes such as {@code -i} and {@code -si} are excluded: far too
     * many Uzbek roots end that way.
     */
    private static final List<String> OOV_AFFIXES = List.of(
        "larimizda", "laringizda", "larimizni", "laringizni", "larimizning",
        "lardagi", "larimiz", "laringiz", "larining", "larning", "larida",
        "laridan", "lariga", "larini", "larda", "lardan", "larga", "larni",
        "lari", "dagi", "ning", "lar", "dan", "ni", "da", "ga", "ka", "qa");

    /** Root -> the parts of speech the lexicon gives it. Empty means unconstrained. */
    private final Map<String, Set<String>> roots;
    /** Affix surface -> the parts of speech it attaches to. Empty means unconstrained. */
    private final Map<String, Set<String>> affixes;
    private final Map<String, List<String>> allomorphs;
    private final int maxAffixLength;

    private static final class Holder {
        static final UzMorphAnalyzer INSTANCE = new UzMorphAnalyzer();
    }

    public static UzMorphAnalyzer get() {
        return Holder.INSTANCE;
    }

    UzMorphAnalyzer() {
        this.roots = new HashMap<>();
        this.affixes = new HashMap<>();
        this.allomorphs = new HashMap<>();
        int max = 0;

        for (String[] row : load(ROOTS)) roots.put(row[0], tags(row));
        for (String[] row : load(AFFIXES)) {
            affixes.put(row[0], tags(row));
            max = Math.max(max, row[0].length());
        }
        for (String[] row : load(ALLOMORPHS)) {
            if (row.length < 2) continue;
            allomorphs.put(row[0], List.of(row[1].split(",")));
        }
        this.maxAffixLength = max;
    }

    /** Analyze without protection information. */
    public Analysis analyze(String searchKey) {
        return analyze(searchKey, ProtectionVerdict.OPEN);
    }

    /**
     * Analyze a Layer 1 search key.
     *
     * @param searchKey the normalized token
     * @param guard     the Layer 3 verdict. {@link Protection#FULL} is returned
     *                  untouched; {@link Protection#STEM_LOCKED} allows affixes to
     *                  come off but forbids cutting into the matched stem, which is
     *                  what keeps {@code zaryadka} from becoming {@code zaryad}
     *                  while {@code telefonlar} still becomes {@code telefon}.
     */
    public Analysis analyze(String searchKey, ProtectionVerdict guard) {
        if (searchKey == null || searchKey.isEmpty()) return Analysis.identity(searchKey == null ? "" : searchKey);

        if (guard.level() == Protection.FULL) {
            return new Analysis(searchKey, searchKey, List.of(), Analysis.Method.PROTECTED);
        }

        if (roots.containsKey(searchKey)) {
            return new Analysis(searchKey, searchKey, List.of(), Analysis.Method.ROOT);
        }

        int floor = MIN_ROOT;
        if (guard.level() == Protection.STEM_LOCKED && guard.matched() != null) {
            floor = Math.max(floor, guard.matched().length());
        }

        // Shortest affix first, i.e. longest surviving stem.
        //
        // Longest-match-first is the usual rule for a bare stemmer, but it is the
        // wrong one once the remainder has to be a real word: it throws away
        // material a shorter cut would have kept. "xaritasi" has a long tail that
        // happens to leave the valid root "xari", so longest-first answers "xari"
        // when the word is "xarita" + "si". Taking the smallest cut that still
        // validates keeps as much of the word as the lexicon will vouch for, and
        // long affixes are still found — nothing shorter validates for
        // "kitoblarimizda", so it walks up to "larimizda" and returns "kitob".
        int longest = Math.min(maxAffixLength, searchKey.length() - floor);
        for (int len = 1; len <= longest; len++) {
            String affix = searchKey.substring(searchKey.length() - len);
            Set<String> affixPos = affixes.get(affix);
            if (affixPos == null) continue;

            String stem = searchKey.substring(0, searchKey.length() - len);
            Resolved r = resolve(stem);
            if (r != null && posAgrees(affixPos, roots.get(r.root))) {
                return new Analysis(searchKey, r.root, List.of(affix), r.method);
            }
        }

        // Nothing in the lexicon matched. Fall back to a short list of safe
        // nominal inflections so that unknown words still lose their case endings.
        Analysis oov = oovStrip(searchKey, floor);
        return oov != null ? oov : Analysis.identity(searchKey);
    }

    /**
     * Strip one unambiguous nominal affix from a word the lexicon does not know.
     *
     * <p>Unvalidated by definition, so it is deliberately timid: one affix only,
     * from {@link #OOV_AFFIXES}, and never below {@link #MIN_OOV_ROOT} characters.
     */
    private Analysis oovStrip(String searchKey, int floor) {
        int minRoot = Math.max(MIN_OOV_ROOT, floor);
        for (String affix : OOV_AFFIXES) {
            if (searchKey.length() - affix.length() < minRoot) continue;
            if (!searchKey.endsWith(affix)) continue;
            String stem = searchKey.substring(0, searchKey.length() - affix.length());
            return new Analysis(searchKey, stem, List.of(affix), Analysis.Method.OOV_STRIP);
        }
        return null;
    }

    // --------------------------------------------------------- resolution

    private record Resolved(String root, Analysis.Method method) {}

    /**
     * Turn what is left after stripping into a real root, or return null.
     *
     * <p>The allomorph table is consulted BEFORE a direct lexicon hit. The source
     * word list contains inflectional stems as entries of their own — {@code ayr}
     * sits beside {@code ayir} — so a direct match would stop at the inflected
     * shape and send {@code ayrildi} and {@code ayirdi} to two different terms.
     * The table exists precisely to say which shape is the lemma.
     */
    private Resolved resolve(String stem) {
        List<String> alts = allomorphs.get(stem);
        if (alts != null) {
            for (String alt : alts) {
                if (roots.containsKey(alt)) return new Resolved(alt, Analysis.Method.ALLOMORPH);
            }
        }

        if (roots.containsKey(stem)) {
            return new Resolved(stem, Analysis.Method.AFFIX_STRIP);
        }

        // tilak + im -> tilagim ; oʻrtoq + im -> oʻrtogʻim. Both surface as a
        // final g in the search key, because ğ folds to g there.
        if (stem.endsWith("g")) {
            String base = stem.substring(0, stem.length() - 1);
            for (String restored : List.of(base + "k", base + "q")) {
                if (roots.containsKey(restored)) return new Resolved(restored, Analysis.Method.DEVOICED);
            }
        }
        return null;
    }

    /**
     * Does this affix belong on this stem?
     *
     * <p>The check that makes shortest-affix-first safe. {@code ayrildi} can be cut
     * as {@code ayri + ldi}, and {@code ayri} really is a word — but {@code -ldi} is
     * a verb ending and {@code ayri} is an adjective, so the cut is wrong. Rejecting
     * it lets the search continue to {@code ayr + ildi}, which the allomorph table
     * resolves to the verb {@code ayir}.
     *
     * <p>An empty set on either side means the source gives no part of speech, and
     * an unknown constraint must not be treated as a failed one.
     */
    private static boolean posAgrees(Set<String> affixPos, Set<String> rootPos) {
        if (affixPos == null || rootPos == null) return true;
        if (affixPos.isEmpty() || rootPos.isEmpty()) return true;
        if (affixPos.contains("X") || rootPos.contains("X")) return true;
        for (String p : affixPos) if (rootPos.contains(p)) return true;
        return false;
    }

    private static Set<String> tags(String[] row) {
        if (row.length < 2 || row[1].isBlank()) return Set.of();
        return Set.of(row[1].split(","));
    }

    // ------------------------------------------------------------ loading

    private static List<String[]> load(String resource) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = UzMorphAnalyzer.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing analyzer resource: " + resource);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    String[] parts = line.split("\t");
                    if (parts.length > 0 && !parts[0].isEmpty()) out.add(parts);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return out;
    }

    public int rootCount()      { return roots.size(); }
    public int affixCount()     { return affixes.size(); }
    public int allomorphCount() { return allomorphs.size(); }
}
