/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.plugin.NamedComponent;
import org.elasticsearch.plugin.analysis.AnalyzerFactory;
import org.tocharian.uzbek.morph.UzbekMorphTokenFilter;

/** The {@code uzbek_split} analyzer: the full chain, root plus affixes. */
@NamedComponent("uzbek_split")
public class UzbekSplitAnalyzerFactory implements AnalyzerFactory {

    @Override
    public Analyzer create() {
        return new UzbekAnalyzer(UzbekMorphTokenFilter.View.SPLIT);
    }
}
