/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.tocharian.uzbek.morph.UzbekMorphTokenFilter;
import org.tocharian.uzbek.script.UzbekNormalizeCharFilter;
import org.tocharian.uzbek.tokenize.UzbekTokenizer;

import java.io.Reader;

/**
 * The whole chain, wired up: normalize, tokenize, protect and analyze.
 *
 * <p>The same instance is correct at index and query time, and it has to be used
 * on both — the cross-script matching works because index and query are folded by
 * the identical function, not because either side is special.
 */
public final class UzbekAnalyzer extends Analyzer {

    private final UzbekMorphTokenFilter.View view;

    public UzbekAnalyzer(UzbekMorphTokenFilter.View view) {
        this.view = view;
    }

    @Override
    protected Reader initReader(String fieldName, Reader reader) {
        return new UzbekNormalizeCharFilter(reader);
    }

    @Override
    protected Reader initReaderForNormalization(String fieldName, Reader reader) {
        return new UzbekNormalizeCharFilter(reader);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        UzbekTokenizer source = new UzbekTokenizer();
        TokenStream result = new UzbekMorphTokenFilter(source, view);
        return new TokenStreamComponents(source, result);
    }
}
