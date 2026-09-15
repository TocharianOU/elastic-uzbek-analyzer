/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.protect;

/**
 * How much of a token Layer 4 is allowed to touch.
 *
 * <p>Two levels, because the two failure modes are different. A brand or a
 * model code must survive whole. A Russian loanword must keep its stem intact
 * but should still lose Uzbek affixes: {@code telefonlar} has to reduce to
 * {@code telefon}, while {@code zaryadka} must not reduce to {@code zaryad} —
 * its {@code -ka} is Russian stem material, and is also the Uzbek dative
 * allomorph after a voiceless stop.
 */
public enum Protection {

    /** Layer 4 analyzes this token normally. */
    NONE,

    /** Affixes after the matched stem may be stripped; the stem itself is atomic. */
    STEM_LOCKED,

    /** Layer 4 must not touch the token at all (keyword_marker). */
    FULL
}
