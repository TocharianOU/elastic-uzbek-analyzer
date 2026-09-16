/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.Test;
import org.tocharian.uzbek.morph.UzbekMorphTokenFilter.View;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The whole chain, through the Lucene API the plugin actually exposes. */
public class UzbekAnalyzerTests {

    private record Tok(String term, int start, int end, int posInc) {}

    private static List<Tok> analyze(View view, String text) throws Exception {
        List<Tok> out = new ArrayList<>();
        try (Analyzer a = new UzbekAnalyzer(view);
             TokenStream ts = a.tokenStream("f", new StringReader(text))) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            OffsetAttribute off = ts.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute pos = ts.addAttribute(PositionIncrementAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(new Tok(term.toString(), off.startOffset(), off.endOffset(),
                        pos.getPositionIncrement()));
            }
            ts.end();
        }
        return out;
    }

    private static List<String> terms(View view, String text) throws Exception {
        return analyze(view, text).stream().map(Tok::term).toList();
    }

    /** The point of the whole plugin: two alphabets, one set of terms. */
    @Test
    public void latinAndCyrillicProduceIdenticalTerms() throws Exception {
        assertEquals(terms(View.LEMMA, "Telefon uchun qopqogʻi"),
                     terms(View.LEMMA, "Телефон "
                                     + "учун "
                                     + "қопқоғи"));
    }

    @Test
    public void modelCodesAreNotSplit() throws Exception {
        assertEquals(List.of("kitob", "sm-g998b", "128gb"),
                terms(View.LEMMA, "kitoblarimizda SM-G998B 128GB"));
    }

    /** A hyphen without a digit is an ordinary boundary. */
    @Test
    public void hyphenatedWordsWithoutDigitsStillSplit() throws Exception {
        assertTrue(terms(View.LEMMA, "koʻk-yashil").size() > 1);
    }

    @Test
    public void apostrophesStayInsideTheirWord() throws Exception {
        assertEquals(List.of("qopqoq"), terms(View.LEMMA, "qopqog'i"));
        assertEquals(List.of("qopqoq"), terms(View.LEMMA, "qopqogʻi"));
    }

    @Test
    public void splitViewKeepsAffixesAtTheRootsPosition() throws Exception {
        List<Tok> toks = analyze(View.SPLIT, "kitoblarimizda");
        assertEquals(2, toks.size());
        assertEquals("kitob", toks.get(0).term());
        assertEquals(1, toks.get(0).posInc());
        assertEquals(0, toks.get(1).posInc());
    }

    /** Offsets must point back into the original text, not the folded form. */
    @Test
    public void offsetsSurviveNormalisationLengthChanges() throws Exception {
        String text = "Samsung Galaxy A54 uchun silikon qopqogʻi qora";
        for (Tok t : analyze(View.LEMMA, text)) {
            assertTrue(t.term() + " start out of range", t.start() >= 0 && t.start() <= text.length());
            assertTrue(t.term() + " end out of range", t.end() >= t.start() && t.end() <= text.length());
        }
        // "qopqogʻi" occupies 8 characters starting at index 33 of the source, and
        // must be reported as such even though it folds to the 6-character term
        // "qopqoq". Getting this wrong is what makes highlights drift.
        int start = text.indexOf("qopqog");
        Tok lid = analyze(View.LEMMA, text).stream()
                .filter(t -> t.term().equals("qopqoq")).findFirst().orElseThrow();
        assertEquals(start, lid.start());
        assertEquals(start + 8, lid.end());
        assertEquals("qopqog\u02BBi", text.substring(lid.start(), lid.end()));
    }

    /**
     * Russian is recognised by ы and щ, and those have to survive until the token
     * filter runs. This used to pass on "чехол" for the wrong reason: every word
     * spelled with ch was being locked, Uzbek or not.
     */
    @Test
    public void russianTextIsNotStemmedAsUzbek() throws Exception {
        // -ka is also the Uzbek dative after a voiceless stop
        assertEquals(List.of("şcetka"), terms(View.LEMMA, "щетка"));
        assertEquals(List.of("noviy", "detskiy"), terms(View.LEMMA, "новый детский"));
    }

    /**
     * The search key writes ç as c, and a standalone c is proof that a word is not
     * Uzbek. Reading the script off the key therefore locked every word spelled
     * with ch against morphology: 7,345 roots of the lexicon, among them the words
     * for lamp, teapot, case and fridge.
     */
    @Test
    public void wordsSpelledWithChAreStemmedLikeAnyOther() throws Exception {
        assertEquals(terms(View.LEMMA, "chiroq"),    terms(View.LEMMA, "chiroqlar"));
        assertEquals(terms(View.LEMMA, "chiroq"),    terms(View.LEMMA, "чироқлар"));
        assertEquals(terms(View.LEMMA, "choynak"),   terms(View.LEMMA, "choynaklarni"));
        assertEquals(terms(View.LEMMA, "chexol"),    terms(View.LEMMA, "chexollar"));
        assertEquals(terms(View.LEMMA, "muzlatgich"), terms(View.LEMMA, "muzlatgichlar"));
        // and a genuinely foreign c is still protected
        assertEquals(List.of("casio"), terms(View.LEMMA, "Casio"));
    }

    /**
     * Script is decided per run, not per field. A field-level verdict folded the
     * minority script with the majority's table and left it unreachable.
     */
    @Test
    public void aMinorityScriptInsideAFieldIsStillFolded() throws Exception {
        assertTrue(terms(View.LEMMA, "Samsung Galaxy A54 uchun silikon qopqogʻi qora rangli телефон")
                .contains("telefon"));

        List<String> cyrillicField = terms(View.LEMMA,
                "Самсунг Галакси А54 учун силикон қопқоғи қора рангли чехол qopqog'i");
        assertEquals(cyrillicField.toString(), 2, java.util.Collections.frequency(cyrillicField, "qopqoq"));

        assertTrue(terms(View.LEMMA, "Samsung Galaxy A54 \u0642\u0648\u067E\u0642\u0648\u063A\u06CC")
                .contains("qopqoq"));
    }

    /** A Perso-Arabic run inside Latin text keeps an offset map into the original. */
    @Test
    public void offsetsOfAPersoArabicRunPointIntoTheOriginal() throws Exception {
        String arabic = "\u0642\u0648\u067E\u0642\u0648\u063A\u06CC";
        String text = "Samsung " + arabic;
        Tok lid = analyze(View.LEMMA, text).stream()
                .filter(t -> t.term().equals("qopqoq")).findFirst().orElseThrow();
        assertEquals(arabic, text.substring(lid.start(), lid.end()));
    }

    /**
     * A model-code run stays whole, and its letter-only pieces of three or more
     * characters are also emitted at the same position, so the word next to the
     * code can still be found.
     */
    @Test
    public void modelCodeRunsStillCarryTheirWords() throws Exception {
        String text = "iPhone 15 Pro Max/256GB";
        List<Tok> toks = analyze(View.LEMMA, text);
        List<String> t = toks.stream().map(Tok::term).toList();
        assertEquals(List.of("iphone", "15", "pro", "max/256gb", "max"), t);
        Tok max = toks.get(4);
        assertEquals(0, max.posInc());
        assertEquals("Max", text.substring(max.start(), max.end()));

        assertEquals(List.of("qopqogi-128gb", "qopqoq"), terms(View.LEMMA, "qopqogʻi-128GB"));
        // two-letter pieces are series and unit noise, not words
        assertEquals(List.of("sm-g998b"), terms(View.LEMMA, "SM-G998B"));
    }
}
