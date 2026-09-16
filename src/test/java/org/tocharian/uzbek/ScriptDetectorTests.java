/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.junit.Test;
import org.tocharian.uzbek.script.ScriptDetector;
import org.tocharian.uzbek.script.ScriptId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScriptDetectorTests {

    private static ScriptId of(String s) {
        return ScriptDetector.detect(s).script();
    }

    @Test
    public void separatesTheTwoLatinOrthographies() {
        assertEquals(ScriptId.UZ_LATN_1995, of("Oʻzbekiston shaharlari"));
        assertEquals(ScriptId.UZ_LATN_2026, of("Özbekiston şaharlari"));
    }

    @Test
    public void anyApostropheSpellingStillReadsAs1995() {
        for (String apos : new String[]{"ʻ", "'", "’", "`", "´"}) {
            assertEquals("apostrophe " + Integer.toHexString(apos.charAt(0)),
                    ScriptId.UZ_LATN_1995, of("qopqog" + apos + "i"));
        }
    }

    @Test
    public void separatesUzbekCyrillicFromRussian() {
        assertEquals(ScriptId.UZ_CYRL, of("Ўзбекистон"));
        assertEquals(ScriptId.RU_CYRL, of("Новый телефон"));
    }

    @Test
    public void reportsUndeterminedRatherThanGuessing() {
        // Every letter is legal in Uzbek, so nothing here proves the language.
        // Guessing was tried and got "Galaxy" (x) and "Samsung" (ng) wrong.
        assertEquals(ScriptId.LATN_UNDETERMINED, of("Samsung Galaxy S24 Ultra"));
        assertEquals(ScriptId.LATN_UNDETERMINED, of("Lenovo IdeaPad Slim"));
        assertTrue(ScriptId.LATN_UNDETERMINED.isUzbek());
    }

    @Test
    public void lettersAbsentFromTheAlphabetAreSoundEvidenceAgainstUzbek() {
        assertEquals(ScriptId.LATN_OTHER, of("Windows"));       // w
        assertEquals(ScriptId.LATN_OTHER, of("Barcelona"));     // standalone c
        assertEquals(ScriptId.LATN_UNDETERMINED, of("chexol")); // c only inside ch
    }

    /**
     * On the internal form ç is its own letter, so a word spelled with ch reads as
     * Uzbek and a standalone c still reads as foreign, even next to a 2026 letter.
     * On the search key the two are indistinguishable, which is why detection must
     * never run there.
     */
    @Test
    public void theInternalFormKeepsChApartFromAStandaloneC() {
        assertEquals(ScriptId.UZ_LATN_2026, of("çexollar"));
        assertEquals(ScriptId.LATN_OTHER, of("çicago"));
        assertEquals(ScriptId.LATN_OTHER, of("cexollar"));   // the key: do not detect here
    }

    /** Russian ы and щ are carried into the internal form as ı and ŝ. */
    @Test
    public void russianCarriersReadAsNonUzbek() {
        assertEquals(ScriptId.LATN_OTHER, of("novıy"));
        assertEquals(ScriptId.LATN_OTHER, of("ŝetka"));
    }

    @Test
    public void handlesMixedAndNonLinguisticInput() {
        assertEquals(ScriptId.MIXED, of("Ноутбук Lenovo"));
        assertEquals(ScriptId.NON_LINGUISTIC, of("15 999 000"));
        assertEquals(ScriptId.UNKNOWN, of(""));
    }
}
