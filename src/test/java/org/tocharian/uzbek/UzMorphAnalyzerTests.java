/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.junit.Test;
import org.tocharian.uzbek.morph.Analysis;
import org.tocharian.uzbek.morph.UzMorphAnalyzer;
import org.tocharian.uzbek.protect.ProtectionMarker;
import org.tocharian.uzbek.script.UzNormalizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UzMorphAnalyzerTests {

    private static final UzMorphAnalyzer M = UzMorphAnalyzer.get();
    private static final ProtectionMarker P = ProtectionMarker.get();

    private static String lemma(String word) {
        String key = UzNormalizer.normalize(word).searchKey();
        return M.analyze(key, P.check(word)).lemma();
    }

    @Test
    public void stripsNominalInflection() {
        assertEquals("kitob", lemma("kitob"));
        assertEquals("kitob", lemma("kitoblar"));
        assertEquals("kitob", lemma("kitobni"));
        assertEquals("kitob", lemma("kitobimiz"));
        assertEquals("kitob", lemma("kitoblarimizda"));
        assertEquals("olma", lemma("olmalar"));
        assertEquals("non", lemma("nonlar"));
    }

    /**
     * Taking the smallest cut that still leaves a real word. Longest-match-first
     * answers "xari" here, because a long tail happens to leave a valid root.
     */
    @Test
    public void prefersTheLongestSurvivingStem() {
        assertEquals("xarita", lemma("xaritasi"));
    }

    /** tilak + im surfaces as tilagim; the final stop has to be restored. */
    @Test
    public void restoresAVoicedFinalStop() {
        assertEquals("tilak", lemma("tilagim"));
        assertEquals("ortoq", lemma("oʻrtogʻim"));
        assertEquals("qopqoq", lemma("qopqogʻi"));
    }

    /**
     * -ldi is a verb ending and "ayri" is an adjective, so that cut is wrong even
     * though "ayri" is a real word. Without the check, shortest-affix-first takes it.
     */
    @Test
    public void rejectsAnAffixThatDisagreesWithTheStemsPartOfSpeech() {
        assertTrue("ayri".equals(lemma("ayrildi")) == false);
    }

    /** Both directions of the loanword rule, in one place. */
    @Test
    public void respectsLayer3() {
        assertEquals("telefon", lemma("telefonlar"));      // affix comes off
        assertEquals("zaryadka", lemma("zaryadkani"));     // affix comes off
        assertEquals("zaryadka", lemma("zaryadka"));       // stem does NOT
        assertEquals("128gb", lemma("128GB"));             // untouched
        assertEquals("lenovo", lemma("Lenovo"));           // untouched
    }

    @Test
    public void unknownWordsStillLoseAPlainCaseEnding() {
        // The lexicon has no entry for many derived or new words; a timid
        // fallback still beats leaving the case ending attached.
        Analysis a = M.analyze(UzNormalizer.normalize("blogerlarni").searchKey());
        assertTrue(a.method() == Analysis.Method.OOV_STRIP
                || a.method() == Analysis.Method.AFFIX_STRIP);
    }

    @Test
    public void bothScriptsReachTheSameLemma() {
        assertEquals(lemma("telefonlar"),
                     lemma("телефонлар"));
        assertEquals(lemma("qopqogʻi"),
                     lemma("қопқоғи"));
    }
}
