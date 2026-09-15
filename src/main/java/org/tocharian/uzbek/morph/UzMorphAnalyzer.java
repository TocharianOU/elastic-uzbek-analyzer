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
 * <p>Analysis is table-first. A 118k-entry inflection table, compiled by a
 * linguist, is consulted before any rule runs; the rules exist to handle what is
 * missing from it rather than to re-derive what it already states. Verb lemmas in
 * that table are normalised to the bare stem rather than the {@code -moq}
 * infinitive, so a word reaching a lemma by lookup and a word reaching one by
 * rule end up at the same term.
 *
 * <p>The rules themselves are a validated suffix stripper, not a finite-state
 * morphotactic parser,
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

    private static final String FORMS      = "/uz_morph/forms.tsv";
    private static final String ROOTS      = "/uz_morph/roots.tsv";
    private static final String AFFIXES    = "/uz_morph/affixes.tsv";
    private static final String ALLOMORPHS = "/uz_morph/stem-allomorphs.tsv";

    /** Shortest string allowed to remain after stripping against the lexicon. */
    private static final int MIN_ROOT = 2;

    /** Shortest remainder accepted by the out-of-vocabulary fallback, which has no
     *  lexicon to check its work and so is held to a stricter minimum. */
    private static final int MIN_OOV_ROOT = 4;

    /** How many times analysis may re-enter itself on its own remainder. */
    private static final int MAX_PASSES = 4;

    /**
     * The core inflections: the affixes that are both highly productive and
     * unambiguous.
     *
     * <p>Used for two things. They are the only affixes the out-of-vocabulary
     * fallback may remove, and they win ties when several cuts all validate —
     * which is what settles {@code otalar}. Both {@code ota+lar} and
     * {@code otal+ar} leave a real root and agree on part of speech, but
     * {@code -lar} is the plural every Uzbek noun takes and {@code -ar} is not,
     * so the first is the reading a shopper meant.
     *
     * <p>Product titles are full of words no lexicon has: new brands, compounds,
     * derived forms such as {@code oʻqituvchi} that are built from derivational
     * suffixes the inflectional table does not carry. Leaving those completely
     * unanalyzed costs real recall, but stripping them with the full 2,464-surface
     * table would be guessing.
     *
     * <p>Ordered longest first. Short or vowel-only affixes such as {@code -i} and
     * {@code -si} are deliberately absent: far too many Uzbek roots end that way
     * for the fallback to strip them blind, and as tie-breakers they would beat
     * the longer cut that is usually right.
     */
    private static final List<String> CORE_AFFIXES = List.of(
        "larimizda", "laringizda", "larimizni", "laringizni", "larimizning",
        "lardagi", "larimiz", "laringiz", "larining", "larning", "larida",
        "laridan", "lariga", "larini", "larda", "lardan", "larga", "larni",
        "lari", "dagi", "ning", "lar", "dan", "ni", "da", "ga", "ka", "qa");

    private static final Set<String> CORE_AFFIX_SET = Set.copyOf(CORE_AFFIXES);

    /**
     * Plural affixes, the one family safe to strip off a word the lexicon already
     * lists as a root.
     *
     * <p>The source word list carries plenty of plain plurals as entries of their
     * own — {@code beglar}, {@code betlar}, {@code bentonitlar} — and stopping at
     * them splits a noun across two terms. Measured over the 57,807 roots, 813
     * end in a core affix and decompose to another root; the {@code lar} family is
     * almost entirely genuine plurals, while the case endings are almost entirely
     * false: {@code sirka} is vinegar, not {@code sir}+{@code ka}, and the same
     * goes for {@code tikka}, {@code anonimka} and {@code metodika}. So plurals
     * are decomposed and case endings are not.
     */
    private static final Set<String> PLURAL_AFFIXES = Set.of(
        "lar", "lari", "larni", "larning", "larda", "lardan", "larga", "lardagi",
        "larimiz", "laringiz", "larining", "larida", "laridan", "lariga", "larini",
        "larimizda", "laringizda", "larimizni", "laringizni", "larimizning");

    /** Root -> the parts of speech the lexicon gives it. Empty means unconstrained. */
    private final Map<String, Set<String>> roots;
    /** Affix surface -> the parts of speech it attaches to. Empty means unconstrained. */
    private final Map<String, Set<String>> affixes;
    private final Map<String, List<String>> allomorphs;
    /** Attested inflected form -> its lemma. Consulted before any rule runs. */
    private final Map<String, String> forms;
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
        this.forms = new HashMap<>();
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
        // Lemma strings are pooled: 118k forms share 31k lemmas, so holding one
        // instance of each rather than one per row is most of the footprint.
        Map<String, String> pool = new HashMap<>();
        for (String[] row : load(FORMS)) {
            if (row.length < 2) continue;
            forms.put(row[0], pool.computeIfAbsent(row[1], v -> v));
        }

        this.maxAffixLength = max;
    }

    /**
     * Analyze with the attested-form table bypassed, so the rules can be measured
     * against it as a held-out set. Not used at index or query time.
     */
    public Analysis analyzeWithRulesOnly(String searchKey) {
        return analyze(searchKey, ProtectionVerdict.OPEN, 0, false);
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
        return analyze(searchKey, guard, 0);
    }

    private Analysis analyze(String searchKey, ProtectionVerdict guard, int pass) {
        return analyze(searchKey, guard, pass, true);
    }

    private Analysis analyze(String searchKey, ProtectionVerdict guard, int pass, boolean useTable) {
        if (searchKey == null || searchKey.isEmpty()) return Analysis.identity(searchKey == null ? "" : searchKey);

        if (guard.level() == Protection.FULL) {
            return new Analysis(searchKey, searchKey, List.of(), Analysis.Method.PROTECTED);
        }

        // Before accepting the word as a root, check whether it is an inflected
        // shape the allomorph table knows about. The source word list carries such
        // shapes as entries of their own — "shahri" and "singlim" sit in it
        // alongside "shahar" and "singil" — so a plain root check stops there and
        // never reaches the table that says which one is the lemma.
        Analysis viaAllomorph = allomorphDecomposition(searchKey, floorFor(guard));
        if (viaAllomorph != null) return viaAllomorph;

        // An attested form needs no analysis. The rules exist for what is missing
        // from this table, not to re-derive what a linguist already wrote down.
        String attested = useTable ? forms.get(searchKey) : null;
        if (attested != null) {
            return new Analysis(searchKey, attested, List.of(), Analysis.Method.LOOKUP);
        }

        if (roots.containsKey(searchKey)) {
            Analysis plural = pluralOfKnownRoot(searchKey, floorFor(guard));
            if (plural != null) return plural;
            return new Analysis(searchKey, searchKey, List.of(), Analysis.Method.ROOT);
        }

        int floor = MIN_ROOT;
        if (guard.level() == Protection.STEM_LOCKED && guard.matched() != null) {
            floor = Math.max(floor, guard.matched().length());
        }

        // Collect every cut that validates, then choose — rather than taking the
        // first one found in either direction.
        //
        // Neither direction works alone, which is the whole reason for scoring.
        // Longest affix first answers "xari" for "xaritasi", because a long tail
        // happens to leave a valid root, when the word is "xarita"+"si". Shortest
        // affix first answers "otal"+"ar" for "otalar", because "ar" is an affix
        // and "otal" is a root, when the word is "ota"+"lar". So stem length is
        // not the deciding property — productivity of the affix is.
        int longest = Math.min(maxAffixLength, searchKey.length() - floor);
        Analysis best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int len = 1; len <= longest; len++) {
            String affix = searchKey.substring(searchKey.length() - len);
            Set<String> affixPos = affixes.get(affix);
            if (affixPos == null) continue;

            String stem = searchKey.substring(0, searchKey.length() - len);
            Resolved r = resolve(stem);
            if (r == null || !posAgrees(affixPos, roots.get(r.root))) continue;

            // A core inflection outranks everything; among equals, keep more of
            // the word. Small margins, so the two never trade places by accident.
            int score = (CORE_AFFIX_SET.contains(affix) ? 1000 : 0) + r.root.length();
            if (score > bestScore) {
                bestScore = score;
                best = new Analysis(searchKey, r.root, List.of(affix), r.method);
            }
        }
        if (best != null) return best;

        // Nothing in the lexicon matched. Fall back to a short list of safe
        // nominal inflections so that unknown words still lose their case endings.
        Analysis oov = oovStrip(searchKey, floor, pass, useTable);
        return oov != null ? oov : Analysis.identity(searchKey);
    }

    private static int floorFor(ProtectionVerdict guard) {
        int floor = MIN_ROOT;
        if (guard.level() == Protection.STEM_LOCKED && guard.matched() != null) {
            floor = Math.max(floor, guard.matched().length());
        }
        return floor;
    }

    /**
     * Is this lexicon entry simply the plural of another entry?
     *
     * <p>Restricted to {@link #PLURAL_AFFIXES} on purpose. Allowing case endings
     * here would break real words that merely end that way.
     */
    private Analysis pluralOfKnownRoot(String searchKey, int floor) {
        for (String affix : PLURAL_AFFIXES) {
            if (searchKey.length() - affix.length() < Math.max(2, floor)) continue;
            if (!searchKey.endsWith(affix)) continue;
            String stem = searchKey.substring(0, searchKey.length() - affix.length());
            Set<String> stemPos = roots.get(stem);
            if (stemPos == null) continue;
            if (!posAgrees(affixes.get(affix), stemPos)) continue;
            return new Analysis(searchKey, stem, List.of(affix), Analysis.Method.AFFIX_STRIP);
        }
        return null;
    }

    /**
     * Is this word an allomorph stem plus a valid affix?
     *
     * <p>Only the 89 hand-curated allomorph stems are eligible, and the part of
     * speech still has to agree, so this cannot run away with ordinary words. It
     * exists because the source lexicon lists inflected shapes as lemmas, which
     * makes the plain root check fire too early on exactly the words the allomorph
     * table was written for.
     */
    private Analysis allomorphDecomposition(String searchKey, int floor) {
        int longest = Math.min(maxAffixLength, searchKey.length() - floor);
        for (int len = 1; len <= longest; len++) {
            String affix = searchKey.substring(searchKey.length() - len);
            Set<String> affixPos = affixes.get(affix);
            if (affixPos == null) continue;

            String stem = searchKey.substring(0, searchKey.length() - len);
            if (roots.containsKey(stem)) continue;      // a real word; leave it alone
            List<String> alts = allomorphs.get(stem);
            if (alts == null) continue;
            for (String alt : alts) {
                if (roots.containsKey(alt) && posAgrees(affixPos, roots.get(alt))) {
                    return new Analysis(searchKey, alt, List.of(affix), Analysis.Method.ALLOMORPH);
                }
            }
        }
        return null;
    }

    /**
     * Strip one unambiguous nominal affix from a word the lexicon does not know,
     * then hand the remainder back to the validated path.
     *
     * <p>The affix table enumerates chains rather than slots, and it cannot
     * enumerate all of them: {@code -larimizdagi} is listed but
     * {@code -larimizdagilar} is not. Without the second look, the fallback
     * answers with the unvalidated remainder {@code kitoblarimizdagi}, which is
     * not a word at all. Re-entering turns that into {@code kitob}.
     *
     * <p>Unvalidated by definition, so the cut itself stays timid: one affix per
     * pass, from {@link #CORE_AFFIXES}, never below {@link #MIN_OOV_ROOT}
     * characters, and at most {@link #MAX_PASSES} passes in total.
     */
    private Analysis oovStrip(String searchKey, int floor, int pass, boolean useTable) {
        int minRoot = Math.max(MIN_OOV_ROOT, floor);
        for (String affix : CORE_AFFIXES) {
            if (searchKey.length() - affix.length() < minRoot) continue;
            if (!searchKey.endsWith(affix)) continue;

            String stem = searchKey.substring(0, searchKey.length() - affix.length());
            if (pass + 1 < MAX_PASSES) {
                Analysis deeper = analyze(stem, ProtectionVerdict.OPEN, pass + 1, useTable);
                if (deeper.isAnalyzed()) {
                    List<String> chain = new ArrayList<>(deeper.affixes());
                    chain.add(affix);
                    return new Analysis(searchKey, deeper.lemma(), chain, deeper.method());
                }
            }
            return new Analysis(searchKey, stem, List.of(affix), Analysis.Method.OOV_STRIP);
        }
        return null;
    }

    // --------------------------------------------------------- resolution

    private record Resolved(String root, Analysis.Method method) {}

    /**
     * Turn what is left after stripping into a real root, or return null.
     *
     * <p>A direct lexicon hit wins over the allomorph table. The table lists the
     * bound shapes of stems that lose a vowel under inflection, but some of those
     * shapes are ordinary words in their own right: {@code qiz} is the bound form
     * of {@code qizil} "red" AND the everyday word for "girl". Consulting the
     * table first turns {@code qizlar} "girls" into {@code qizil}, which is not
     * merely a worse lemma but a different word. The table only gets a say when
     * the remainder is not a word on its own.
     */
    private Resolved resolve(String stem) {
        if (roots.containsKey(stem)) {
            return new Resolved(stem, Analysis.Method.AFFIX_STRIP);
        }

        List<String> alts = allomorphs.get(stem);
        if (alts != null) {
            for (String alt : alts) {
                if (roots.containsKey(alt)) return new Resolved(alt, Analysis.Method.ALLOMORPH);
            }
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

    public int formCount()      { return forms.size(); }
    public int rootCount()      { return roots.size(); }
    public int affixCount()     { return affixes.size(); }
    public int allomorphCount() { return allomorphs.size(); }
}
