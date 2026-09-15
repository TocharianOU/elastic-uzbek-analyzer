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

    /**
     * The affix table enumerates chains, and it cannot enumerate all of them:
     * -larimizdagi is listed, -larimizdagilar is not. One pass answers with an
     * unvalidated remainder that is not a word at all.
     */
    @Test
    public void reEntersOnItsOwnRemainderForChainsTheTableMisses() {
        assertEquals("kitob", lemma("kitoblarimizdagilar"));
        assertEquals("uy", lemma("uydagilarga"));
        assertEquals("telefon", lemma("telefonlarimizdan"));
    }

    /**
     * Both cuts leave a real root and agree on part of speech, so stem length
     * cannot decide: "xaritasi" wants the longer stem and "otalar" the longer
     * affix. Productivity of the affix is what settles it.
     */
    @Test
    public void prefersACoreInflectionOverAnIncidentalOne() {
        assertEquals("ota", lemma("otalar"));       // ota+lar, not otal+ar
        assertEquals("xarita", lemma("xaritasi"));  // xarita+si, not xari+tasi
    }

    /**
     * "qiz" is the bound form of "qizil" (red) and also the everyday word for
     * girl. Consulting the allomorph table before the lexicon turns "qizlar"
     * (girls) into "qizil", which is not a worse lemma but a different word.
     */
    @Test
    public void aRealWordOutranksItsAllomorphReading() {
        assertEquals("qiz", lemma("qizlar"));
    }

    /**
     * The source lists plenty of plain plurals as lemmas of their own, which
     * would otherwise split a noun across two terms.
     */
    @Test
    public void decomposesPluralsThatTheLexiconListsAsRoots() {
        assertEquals("beg", lemma("beglar"));
        assertEquals("bet", lemma("betlar"));
        assertEquals("antibiotik", lemma("antibiotiklar"));
    }

    /**
     * ...but only plurals. Case endings on known roots are almost always part of
     * the word: sirka is vinegar, not sir+ka.
     */
    @Test
    public void doesNotStripCaseEndingsOffWordsThatMerelyEndThatWay() {
        for (String w : new String[]{"sirka", "tilka", "tikka", "anonimka",
                                     "metodika", "qadimdan", "avjida", "sirga"}) {
            assertEquals(w, w, lemma(w));
        }
    }

    @Test
    public void bothScriptsReachTheSameLemma() {
        assertEquals(lemma("telefonlar"),
                     lemma("телефонлар"));
        assertEquals(lemma("qopqogʻi"),
                     lemma("қопқоғи"));
    }
}
