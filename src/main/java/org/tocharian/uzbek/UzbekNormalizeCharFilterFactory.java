/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek;

import org.elasticsearch.plugin.NamedComponent;
import org.elasticsearch.plugin.analysis.CharFilterFactory;
import org.tocharian.uzbek.script.UzbekNormalizeCharFilter;

import java.io.Reader;

/** Registers Layer 1 as the {@code uzbek_normalize} char filter. */
@NamedComponent("uzbek_normalize")
public class UzbekNormalizeCharFilterFactory implements CharFilterFactory {

    @Override
    public Reader create(Reader reader) {
        return new UzbekNormalizeCharFilter(reader);
    }

    @Override
    public Reader normalize(Reader reader) {
        return new UzbekNormalizeCharFilter(reader);
    }
}
