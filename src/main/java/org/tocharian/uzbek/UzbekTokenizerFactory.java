/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.plugin.NamedComponent;
import org.elasticsearch.plugin.analysis.TokenizerFactory;
import org.tocharian.uzbek.tokenize.UzbekTokenizer;

/** Registers Layer 2 as the {@code uzbek_tokenizer} tokenizer. */
@NamedComponent("uzbek_tokenizer")
public class UzbekTokenizerFactory implements TokenizerFactory {

    @Override
    public Tokenizer create() {
        return new UzbekTokenizer();
    }
}
