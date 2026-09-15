/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.protect;

import org.tocharian.uzbek.script.ScriptDetector;
import org.tocharian.uzbek.script.ScriptId;
import org.tocharian.uzbek.script.UzNormalizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Layer 3 — decide what Layer 4 may not touch.
 *
 * <p>Layer 0 measured on a real 46k-stem lexicon reports {@code LATN_UNDETERMINED}
 * for 87.2% of Uzbek words: a Latin string made of Uzbek-legal letters is simply
 * not separable from a brand name by orthography. That gap is this layer's job.
 *
 * <p>Rules fire in descending order of confidence and short-circuit. The list
 * lookups are keyed on the Layer 1 search key, so an entry spelled in either
 * alphabet covers that spelling, and an incoming token matches regardless of how
 * its apostrophes were typed.
 *
 * <p>Error asymmetry drives the thresholds. Failing to protect a brand costs one
 * bad match; wrongly protecting a real Uzbek word removes every inflected form of
 * it from search. Over-protection is the expensive direction, so every rule here
 * is either exact or anchored at the start of the token.
 *
 * <p><b>Invariant.</b> {@code brand-exemptions.txt} and Layer 4's root lexicon must
 * be generated from the SAME word list. A brand is exempted from protection on the
 * promise that Layer 4 will recognise it as a known root and leave it whole; if the
 * two drift apart, an exempted brand falls through to affix-stripping and is
 * mangled. Regenerate both together.
 *
 * <p>Thread-safe and immutable after construction. Load once, share.
 */
public final class ProtectionMarker {

    private static final String BRANDS   = "/uz_lex/brands.txt";
    private static final String LOANS    = "/uz_lex/loanwords.txt";
    private static final String FUNCTION = "/uz_lex/function_words.txt";
    private static final String EXEMPT   = "/uz_lex/brand-exemptions.txt";

    /** Shortest loanword stem that may be matched as a prefix. */
    private static final int MIN_PREFIX_STEM = 5;

    private final Set<String> brands;
    private final Set<String> brandExemptions;
    private final Set<String> functionWords;
    private final Map<String, String> loanStems;   // search key -> original spelling

    private static final class Holder {
        static final ProtectionMarker INSTANCE = new ProtectionMarker();
    }

    public static ProtectionMarker get() {
        return Holder.INSTANCE;
    }

    ProtectionMarker() {
        this.brands = loadKeys(BRANDS);
        this.brandExemptions = loadKeys(EXEMPT);
        this.functionWords = loadKeys(FUNCTION);
        this.loanStems = new HashMap<>();
        for (String raw : loadRaw(LOANS)) {
            loanStems.put(UzNormalizer.normalize(raw).searchKey(), raw);
        }
    }

    /** Convenience: detect the orthography, then decide. */
    public ProtectionVerdict check(String token) {
        if (token == null || token.isEmpty()) return ProtectionVerdict.OPEN;
        return check(token, ScriptDetector.detect(token).script());
    }

    /**
     * Decide how much of {@code token} Layer 4 may analyze.
     *
     * @param token  a single token, before or after normalization — either works,
     *               since matching happens on the search key
     * @param script the Layer 0 verdict for this token
     */
    public ProtectionVerdict check(String token, ScriptId script) {
        if (token == null || token.isEmpty()) return ProtectionVerdict.OPEN;

        // 1. Digits. Model codes, capacities and sizes are never words.
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                return new ProtectionVerdict(Protection.FULL,
                        ProtectionVerdict.Reason.DIGIT, token);
            }
        }

        // 2. A letter the Uzbek alphabet does not have. Sound, no list needed.
        if (script == ScriptId.LATN_OTHER) {
            return new ProtectionVerdict(Protection.FULL,
                    ProtectionVerdict.Reason.NON_UZBEK_LETTER, token);
        }

        // 3. Russian-only Cyrillic. Uzbek morphology must not be applied.
        if (script == ScriptId.RU_CYRL) {
            return new ProtectionVerdict(Protection.FULL,
                    ProtectionVerdict.Reason.RUSSIAN_SCRIPT, token);
        }

        String key = UzNormalizer.normalize(token, script).searchKey();
        if (key.isEmpty()) return ProtectionVerdict.OPEN;

        // 4. Known brand, exact match on the whole token — unless the lexicon
        //    already knows that string as an Uzbek stem, in which case
        //    protecting it would cost more than it buys. See brand-exemptions.txt.
        if (brands.contains(key) && !brandExemptions.contains(key)) {
            return new ProtectionVerdict(Protection.FULL,
                    ProtectionVerdict.Reason.BRAND, key);
        }

        // 5. Never-inflected function word, exact match.
        if (functionWords.contains(key)) {
            return new ProtectionVerdict(Protection.FULL,
                    ProtectionVerdict.Reason.FUNCTION_WORD, key);
        }

        // 6. Russian loanword stem. Anchored at the start and longest-match, so
        //    "telefonlar" locks "telefon" and keeps its affixes strippable.
        String stem = longestLoanPrefix(key);
        if (stem != null) {
            return new ProtectionVerdict(Protection.STEM_LOCKED,
                    ProtectionVerdict.Reason.LOANWORD_STEM, stem);
        }

        return ProtectionVerdict.OPEN;
    }

    /**
     * Longest loanword stem that {@code key} starts with, or null.
     *
     * <p>Prefix matching is what makes the rule useful — inflected loanwords are
     * the whole point — but it is also how this layer could over-fire, so short
     * stems are excluded: a 3-letter Russian stem would swallow unrelated Uzbek
     * words. {@link #MIN_PREFIX_STEM} is set from the measured false-protection
     * rate against a 46k-stem Uzbek lexicon, not by intuition.
     */
    private String longestLoanPrefix(String key) {
        String best = null;
        for (int len = key.length(); len >= MIN_PREFIX_STEM; len--) {
            String candidate = key.substring(0, len);
            if (loanStems.containsKey(candidate)) {
                best = candidate;
                break;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ loading

    private static Set<String> loadKeys(String resource) {
        Set<String> out = new HashSet<>();
        for (String raw : loadRaw(resource)) {
            out.add(UzNormalizer.normalize(raw).searchKey());
        }
        return out;
    }

    /** Read a resource: one entry per line, {@code #} comments and blanks skipped,
     *  everything after a tab treated as an annotation. */
    private static java.util.List<String> loadRaw(String resource) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (InputStream in = ProtectionMarker.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing analyzer resource: " + resource);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab >= 0) line = line.substring(0, tab).trim();
                    if (!line.isEmpty()) out.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return out;
    }

    public int brandCount()        { return brands.size(); }
    public int loanwordCount()     { return loanStems.size(); }
    public int functionWordCount() { return functionWords.size(); }
}
