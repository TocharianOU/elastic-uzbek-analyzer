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

/** Registers the {@code uzbek_morph_split} token filter: root plus affixes. */
@NamedComponent("uzbek_morph_split")
public class UzbekMorphSplitTokenFilterFactory implements TokenFilterFactory {

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return new UzbekMorphTokenFilter(tokenStream, UzbekMorphTokenFilter.View.SPLIT);
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
