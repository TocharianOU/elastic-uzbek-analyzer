/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.plugin.NamedComponent;
import org.elasticsearch.plugin.analysis.AnalysisMode;
import org.elasticsearch.plugin.analysis.TokenFilterFactory;
import org.tocharian.uzbek.morph.UzbekMorphTokenFilter;

/**
 * Registers Layers 3 and 4 as the {@code uzbek_morph} token filter: root only.
 *
 * <p>{@link AnalysisMode#ALL} because the filter must run at query time too. Index
 * and query have to agree on the term, or none of the cross-script matching works.
 */
@NamedComponent("uzbek_morph")
public class UzbekMorphTokenFilterFactory implements TokenFilterFactory {

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return new UzbekMorphTokenFilter(tokenStream, UzbekMorphTokenFilter.View.LEMMA);
    }

    @Override
    public TokenStream normalize(TokenStream tokenStream) {
        return tokenStream;
    }

    @Override
    public AnalysisMode getAnalysisMode() {
        return AnalysisMode.ALL;
    }
}
