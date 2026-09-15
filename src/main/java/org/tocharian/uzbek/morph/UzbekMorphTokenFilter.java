/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.morph;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.tocharian.uzbek.protect.ProtectionMarker;
import org.tocharian.uzbek.protect.ProtectionVerdict;
import org.tocharian.uzbek.script.UzNormalizer;

import java.io.IOException;
import java.util.List;

/**
 * Layers 3 and 4 as a Lucene token filter.
 *
 * <p>Runs protection and morphology together because they are one decision: what
 * the analyzer is allowed to do to this token. A protected token is also marked
 * with {@link KeywordAttribute} so that any downstream stemmer leaves it alone.
 *
 * <p>Two views, chosen at construction:
 * <ul>
 *   <li>{@link View#LEMMA} emits the root only. Highest recall, and the field a
 *       {@code match} query should target.</li>
 *   <li>{@link View#SPLIT} emits the root and then each affix at the same
 *       position. Keeps affix material searchable for phrase and prefix queries.</li>
 * </ul>
 */
public final class UzbekMorphTokenFilter extends TokenFilter {

    public enum View { LEMMA, SPLIT }

    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keywordAttr = addAttribute(KeywordAttribute.class);
    private final PositionIncrementAttribute posAttr = addAttribute(PositionIncrementAttribute.class);

    private final UzMorphAnalyzer morphology;
    private final ProtectionMarker protection;
    private final View view;

    private List<String> pending;
    private int pendingIndex;

    public UzbekMorphTokenFilter(TokenStream input, View view) {
        this(input, view, UzMorphAnalyzer.get(), ProtectionMarker.get());
    }

    UzbekMorphTokenFilter(TokenStream input, View view,
                          UzMorphAnalyzer morphology, ProtectionMarker protection) {
        super(input);
        this.view = view;
        this.morphology = morphology;
        this.protection = protection;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (pending != null && pendingIndex < pending.size()) {
            String piece = pending.get(pendingIndex++);
            termAttr.setEmpty().append(piece);
            posAttr.setPositionIncrement(0);      // affixes share the root's position
            return true;
        }
        pending = null;

        if (!input.incrementToken()) return false;

        String token = termAttr.toString();
        if (keywordAttr.isKeyword()) return true;

        // The token arrives in the internal form; the lists and lexicon are keyed
        // on the search key, so fold the last step here.
        String key = UzNormalizer.toSearchKey(token);
        ProtectionVerdict guard = protection.check(key);
        Analysis analysis = morphology.analyze(key, guard);

        if (guard.level() != org.tocharian.uzbek.protect.Protection.NONE) {
            keywordAttr.setKeyword(true);
        }

        termAttr.setEmpty().append(analysis.lemma());

        if (view == View.SPLIT && !analysis.affixes().isEmpty()) {
            pending = analysis.affixes();
            pendingIndex = 0;
        }
        return true;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        pending = null;
        pendingIndex = 0;
    }
}
