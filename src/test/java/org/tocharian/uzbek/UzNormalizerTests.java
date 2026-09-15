/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.junit.Test;
import org.tocharian.uzbek.script.NormalizedForm;
import org.tocharian.uzbek.script.ScriptId;
import org.tocharian.uzbek.script.UzNormalizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UzNormalizerTests {

    private static String key(String s) {
        return UzNormalizer.normalize(s).searchKey();
    }

    /** The behaviour the whole plugin exists for. */
    @Test
    public void everySpellingOfAWordFoldsToOneKey() {
        assertAllEqual("qopqogi",
            "qopqogʻi", "qopqog'i", "qopqog’i", "qopqog`i",
            "qopqogi", "qopqoği", "қопқоғи");

        assertAllEqual("ozbekiston",
            "Oʻzbekiston", "O'zbekiston", "Ozbekiston", "Özbekiston",
            "Ўзбекистон");

        assertAllEqual("şahar", "shahar", "şahar",
            "шаҳар");

        assertAllEqual("cexol", "chexol", "çexol",
            "чехол");
    }

    @Test
    public void internalFormUsesTheOfficial2026Letters() {
        assertEquals("özbekiston", UzNormalizer.normalize("Oʻzbekiston").internal());
        assertEquals("şahar", UzNormalizer.normalize("shahar").internal());
        assertEquals("çexol", UzNormalizer.normalize("chexol").internal());
    }

    /**
     * ng is a digraph, not one letter: non+ga is /n/+/g/. Collapsing it would
     * corrupt every dative on an n-final stem.
     */
    @Test
    public void ngIsNotCollapsed() {
        assertEquals("nonga", UzNormalizer.normalize("nonga").internal());
        assertEquals("singil", UzNormalizer.normalize("singil").internal());
    }

    /** The tutuq belgisi is what separates s+h from the digraph sh. */
    @Test
    public void tutuqBelgisiBlocksTheShDigraph() {
        assertEquals("ishoq", UzNormalizer.normalize("Isʼhoq").internal());
        assertEquals("işoq", UzNormalizer.normalize("ishoq").internal());
    }

    /**
     * A Cyrillic letter surviving into the key is silent data loss: no Latin
     * query can ever produce it. Found in the wild as an unmapped 'ы'.
     */
    @Test
    public void noCyrillicSurvivesIntoTheKey() {
        String[] russian = {
            "новый", "мыло",
            "щётка", "пальто",
            "объект", "зарядка",
        };
        for (String r : russian) {
            String out = UzNormalizer.normalize(r, ScriptId.UZ_CYRL).internal();
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                assertTrue(r + " leaked U+" + Integer.toHexString(c),
                        c < 'Ѐ' || c > 'ӿ');
            }
        }
    }

    /**
     * Core verb stems in a curated 46k lexicon carry a Cyrillic 'е' among Latin
     * letters. They normalize cleanly but classify as mixed script, so morphology
     * is skipped and no ordinary query can reach them.
     */
    @Test
    public void repairsStrayCyrillicInsideLatinWords() {
        assertEquals("kel", UzNormalizer.normalize("kеl").internal());
        assertEquals("sev", UzNormalizer.normalize("sеv").internal());
        assertEquals("çek", UzNormalizer.normalize("chеk").internal());
        // every letter shared between the alphabets: only context can decide
        assertEquals("sep mayda uruğ",
                UzNormalizer.normalize("sеp mayda urugʻ").internal());
    }

    /** Highlighting drifts without this, because normalization changes length. */
    @Test
    public void offsetMapCoversEveryOutputCharacterAndNeverGoesBackwards() {
        String[] samples = {
            "Oʻzbekiston shaharlari",
            "Ўзбекистон юрти",
            "Lenovo IdeaPad ноутбук 15\"",
        };
        for (String s : samples) {
            NormalizedForm f = UzNormalizer.normalize(s);
            int[] ix = f.srcIndex();
            assertEquals(s, f.internal().length(), ix.length);
            for (int i = 0; i < ix.length; i++) {
                assertTrue(s + " offset out of range", ix[i] >= 0 && ix[i] < s.length());
                if (i > 0) assertTrue(s + " offset went backwards", ix[i] >= ix[i - 1]);
            }
        }
    }

    /**
     * Folding is graded by collision risk, not by "is it a diacritic". c does not
     * exist in the Uzbek alphabet so ç folds for free; ş is kept because sh is a
     * digraph nobody mistypes, and folding it would only add collisions.
     */
    @Test
    public void searchKeyFoldsOnlyWhatInputAmbiguityRequires() {
        assertEquals("qopqogi", key("qopqoği"));   // ğ -> g
        assertEquals("ozbekiston", key("özbekiston")); // ö -> o
        assertEquals("cexol", key("çexol"));       // ç -> c, collision-free
        assertEquals("şahar", key("şahar"));  // ş kept
    }

    /**
     * Uzbek writes Russian ц as ts only after a vowel, and as s otherwise — both
     * word-initially and after a consonant. A blanket ts is wrong far more often
     * than it is right, and gets every one of these except the last.
     */
    @Test
    public void cyrillicTseFollowsThePositionalRule() {
        assertEquals("sirk",        key("\u0446\u0438\u0440\u043A"));                 // цирк
        assertEquals("sement",      key("\u0446\u0435\u043C\u0435\u043D\u0442"));   // цемент
        assertEquals("sex",         key("\u0446\u0435\u0445"));                        // цех
        assertEquals("stansiya",    key("\u0441\u0442\u0430\u043D\u0446\u0438\u044F")); // станция
        assertEquals("revolyutsiya",
                key("\u0440\u0435\u0432\u043E\u043B\u044E\u0446\u0438\u044F"));  // революция

        // which is the point: the Cyrillic and Latin spellings now meet
        assertEquals(key("sirk"), key("\u0446\u0438\u0440\u043A"));
        assertEquals(key("stansiya"), key("\u0441\u0442\u0430\u043D\u0446\u0438\u044F"));
    }

    private static void assertAllEqual(String expected, String... spellings) {
        for (String s : spellings) assertEquals(s, expected, key(s));
    }
}
