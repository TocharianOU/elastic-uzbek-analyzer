/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.plugin.NamedComponent;
import org.elasticsearch.plugin.analysis.AnalyzerFactory;
import org.tocharian.uzbek.morph.UzbekMorphTokenFilter;

/** The ready-made {@code uzbek} analyzer: the full chain, root only. */
@NamedComponent("uzbek")
public class UzbekAnalyzerFactory implements AnalyzerFactory {

    @Override
    public Analyzer create() {
        return new UzbekAnalyzer(UzbekMorphTokenFilter.View.LEMMA);
    }
}
