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

    @Test
    public void russianTextIsNotStemmedAsUzbek() throws Exception {
        List<String> t = terms(View.LEMMA,
            "Чехол для "
          + "телефона");
        assertTrue(t.contains("cexol"));
    }
}
