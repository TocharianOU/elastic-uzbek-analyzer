/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

/**
 * Orthographies this analyzer accepts as input (Layer 0 output).
 *
 * <p>Uzbek is written in four living orthographies. They are NOT
 * interchangeable at the character level, so the normalizer (Layer 1) needs
 * to know which one it is looking at before it can fold to the internal form.
 */
public enum ScriptId {

    /** Latin, 1995 orthography: digraphs {@code sh ch ng} and {@code oʻ gʻ}. */
    UZ_LATN_1995(true),

    /** Latin, 2026 reform: {@code ş ç ö ğ}. This is also the internal form. */
    UZ_LATN_2026(true),

    /** Uzbek Cyrillic: marked by {@code ў қ ғ ҳ}. */
    UZ_CYRL(true),

    /** Perso-Arabic script, as used for the {@code uzs} variety. */
    UZ_ARAB(true),

    /** Cyrillic that carries Russian-only letters {@code ы щ} — must NOT be stemmed as Uzbek. */
    RU_CYRL(false),

    /** Cyrillic with no discriminating letters either way (common in short strings). */
    CYRL_AMBIGUOUS(true),

    /**
     * Latin containing a letter that the Uzbek alphabet does not have
     * ({@code w}, or a standalone {@code c} outside the {@code ch} digraph).
     * A sound negative: this is definitely not Uzbek.
     */
    LATN_OTHER(false),

    /**
     * Latin with no orthographic marker either way — every letter is legal in
     * Uzbek, but nothing proves it is Uzbek ({@code Samsung}, {@code shahar},
     * {@code Lenovo} are all indistinguishable at this layer).
     *
     * <p>Reported honestly instead of guessed. Layer 1 folds it with the Latin
     * rules, which is harmless in either language, and Layer 4 decides via
     * lexicon lookup whether morphology applies.
     */
    LATN_UNDETERMINED(true),

    /** Several scripts in one string — normalize per token, not per field. */
    MIXED(false),

    /** Digits, punctuation, emoji only. */
    NON_LINGUISTIC(false),

    UNKNOWN(false);

    private final boolean uzbek;

    ScriptId(boolean uzbek) {
        this.uzbek = uzbek;
    }

    /** True if Layer 4 (morphology) may safely analyze text in this orthography. */
    public boolean isUzbek() {
        return uzbek;
    }
}
