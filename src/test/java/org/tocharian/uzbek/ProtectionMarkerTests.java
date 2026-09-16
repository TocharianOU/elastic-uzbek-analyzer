/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.junit.Test;
import org.tocharian.uzbek.script.ScriptDetector;
import org.tocharian.uzbek.protect.Protection;
import org.tocharian.uzbek.protect.ProtectionMarker;
import org.tocharian.uzbek.protect.ProtectionVerdict;

import static org.junit.Assert.assertEquals;

public class ProtectionMarkerTests {

    private static final ProtectionMarker PM = ProtectionMarker.get();

    private static Protection level(String token) {
        return PM.check(token).level();
    }

    @Test
    public void modelCodesAndCapacitiesSurviveWhole() {
        assertEquals(Protection.FULL, level("SM-G998B"));
        assertEquals(Protection.FULL, level("128GB"));
        assertEquals(Protection.FULL, level("A2650"));
    }

    @Test
    public void lettersAbsentFromTheAlphabetNeedNoList() {
        assertEquals(ProtectionVerdict.Reason.NON_UZBEK_LETTER, PM.check("Windows").reason());
        assertEquals(Protection.FULL, level("Xiaomi Redmi Watch".split(" ")[2]));
    }

    @Test
    public void russianScriptIsNeverStemmedAsUzbek() {
        assertEquals(ProtectionVerdict.Reason.RUSSIAN_SCRIPT,
                PM.check("новый").reason());
    }

    @Test
    public void brandsAbsentFromTheLexiconAreProtected() {
        assertEquals(Protection.FULL, level("Galaxy"));
        assertEquals(Protection.FULL, level("Lenovo"));
        assertEquals(Protection.FULL, level("Redmi"));
    }

    /**
     * The rule that is the opposite of the obvious one. A brand the lexicon knows
     * needs no protection — Layer 4 finds it as a root and leaves it whole — and
     * protecting it would delete a common noun from search, because in a Turkic
     * market brand names are frequently ordinary words.
     */
    @Test
    public void brandsPresentInTheLexiconAreLeftOpen() {
        assertEquals(Protection.NONE, level("Samsung"));
        assertEquals(Protection.NONE, level("Самсунг"));

        assertEquals("uzum is a marketplace and also the word for grape",
                Protection.NONE, level("uzum"));
        assertEquals("Бош folds to bosh, the word for head",
                Protection.NONE, level("bosh"));
        assertEquals("Olcha is a shop and also the word for cherry",
                Protection.NONE, level("olcha"));
        assertEquals("Ravon is a car and also the word for fluent",
                Protection.NONE, level("ravon"));
    }

    /**
     * Loanwords keep their stem but still shed Uzbek affixes: -ka is Russian stem
     * material in zaryadka and the dative allomorph elsewhere.
     */
    @Test
    public void loanwordStemsAreLockedButNotFrozen() {
        assertEquals(Protection.STEM_LOCKED, level("zaryadka"));
        assertEquals(Protection.STEM_LOCKED, level("zaryadkani"));
        assertEquals(Protection.STEM_LOCKED, level("telefonlar"));
        assertEquals("telefon", PM.check("telefonlar").matched());
    }

    /**
     * The token filter path: script read on the internal form, lists on the key.
     * Words spelled with ch must stay open, or loanword-locked where listed.
     */
    @Test
    public void checkKeyTakesTheScriptFromTheInternalForm() {
        assertEquals(Protection.NONE,
                PM.checkKey("ciroqlar", ScriptDetector.detect("çiroqlar").script()).level());
        assertEquals(Protection.STEM_LOCKED,
                PM.checkKey("cexollar", ScriptDetector.detect("çexollar").script()).level());
        assertEquals(ProtectionVerdict.Reason.NON_UZBEK_LETTER,
                PM.checkKey("şcetka", ScriptDetector.detect("ŝetka").script()).reason());
    }

    @Test
    public void ordinaryUzbekWordsAreLeftAlone() {
        for (String w : new String[]{"kitob", "kitoblarimiz", "shahar",
                                     "oʻqituvchi", "olma", "non", "yaxshi"}) {
            assertEquals(w, Protection.NONE, level(w));
        }
    }
}
