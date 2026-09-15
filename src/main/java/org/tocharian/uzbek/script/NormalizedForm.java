/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

import java.util.List;

/**
 * Layer 1 output.
 *
 * <p>Carries two views plus the offset map that keeps highlighting honest:
 * <ul>
 *   <li>{@link #internal()} — one codepoint per phoneme ({@code ö ğ ş ç}),
 *       the form Layer 4 morphology runs on.</li>
 *   <li>{@link #searchKey()} — {@code ö→o}, {@code ğ→g}, tutuq dropped. Folds
 *       exactly the ambiguity that omitted apostrophes create, and nothing
 *       else.</li>
 *   <li>{@link #srcIndex()} — for every character of {@code internal}, the
 *       index in the ORIGINAL input it came from. Normalization changes string
 *       length ({@code sh}→{@code ş} shrinks, Cyrillic {@code ю}→{@code yu}
 *       grows), so without this map every highlight fragment drifts.</li>
 * </ul>
 */
public record NormalizedForm(
        String original,
        String internal,
        String searchKey,
        ScriptId source,
        int[] srcIndex,
        List<Ambiguity> ambiguities) {

    /**
     * A place where the input genuinely underdetermines the reading. Recorded
     * rather than silently resolved, so Layer 2 can emit position-synonyms and
     * the caller can see why recall was widened.
     */
    public record Ambiguity(int outPos, String chosen, List<String> alternatives, String rule) {
        @Override
        public String toString() {
            return "@" + outPos + " " + chosen + "~" + alternatives + " (" + rule + ")";
        }
    }

    /** Map an offset in {@link #internal()} back to the original string. */
    public int toOriginalOffset(int internalOffset) {
        if (srcIndex.length == 0) return 0;
        int i = Math.max(0, Math.min(internalOffset, srcIndex.length - 1));
        return srcIndex[i];
    }

    public boolean hasAmbiguity() {
        return !ambiguities.isEmpty();
    }
}
