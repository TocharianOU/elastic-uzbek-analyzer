/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.protect;

/**
 * Layer 3 output: how much protection a token gets, and why.
 *
 * <p>The reason is carried so that a wrong decision is debuggable. Over-protection
 * is the expensive error — a real Uzbek word wrongly marked loses every one of its
 * inflected forms from search — so it must be possible to see which rule fired.
 */
public record ProtectionVerdict(Protection level, Reason reason, String matched) {

    public enum Reason {
        /** Nothing fired. */
        NONE,
        /** Contains a digit: model codes, capacities, sizes. */
        DIGIT,
        /** Layer 0 found a letter the Uzbek alphabet does not have ({@code w}, standalone {@code c}). */
        NON_UZBEK_LETTER,
        /** Layer 0 found Russian-only Cyrillic ({@code ы}, {@code щ}). */
        RUSSIAN_SCRIPT,
        /** Listed in {@code brands.txt}. */
        BRAND,
        /** Starts with a stem listed in {@code loanwords.txt}. */
        LOANWORD_STEM,
        /** Listed in {@code function_words.txt}: never takes an affix. */
        FUNCTION_WORD
    }

    public static final ProtectionVerdict OPEN =
            new ProtectionVerdict(Protection.NONE, Reason.NONE, null);

    public boolean isProtected() {
        return level != Protection.NONE;
    }

    @Override
    public String toString() {
        return level + (matched == null ? "" : "(" + reason + ":" + matched + ")");
    }
}
