/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.morph;

import java.util.List;

/**
 * Layer 4 output: what a token was made of.
 *
 * <p>Two views, mirroring how the index uses them. {@link #lemma()} is the root
 * alone and drives recall; {@link #split()} is root plus affixes and is what a
 * phrase or prefix query needs.
 */
public record Analysis(String surface, String lemma, List<String> affixes, Method method) {

    public enum Method {
        /** The token is itself a root. */
        ROOT,
        /** An affix was stripped and the remainder is a known root. */
        AFFIX_STRIP,
        /** The remainder matched a listed stem allomorph (dropped vowel, final voicing). */
        ALLOMORPH,
        /** The remainder ended in a voiced stop that restores to a voiceless root. */
        DEVOICED,
        /** The lexicon did not know the stem; a safe nominal affix was removed anyway. */
        OOV_STRIP,
        /** Layer 3 said hands off. */
        PROTECTED,
        /** Nothing applied; the token stands as written. */
        IDENTITY
    }

    public static Analysis identity(String s) {
        return new Analysis(s, s, List.of(), Method.IDENTITY);
    }

    /** Root first, then each affix, in surface order. */
    public List<String> split() {
        if (affixes.isEmpty()) return List.of(lemma);
        List<String> out = new java.util.ArrayList<>(affixes.size() + 1);
        out.add(lemma);
        out.addAll(affixes);
        return out;
    }

    public boolean isAnalyzed() {
        return method != Method.IDENTITY && method != Method.PROTECTED;
    }

    @Override
    public String toString() {
        return lemma + (affixes.isEmpty() ? "" : "+" + String.join("+", affixes))
             + " [" + method + "]";
    }
}
