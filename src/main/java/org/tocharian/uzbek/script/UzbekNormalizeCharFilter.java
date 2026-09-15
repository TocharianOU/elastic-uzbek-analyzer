/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.script;

import org.apache.lucene.analysis.charfilter.BaseCharFilter;

import java.io.IOException;
import java.io.Reader;

/**
 * Layer 1 as a Lucene char filter.
 *
 * <p>Folds the whole input to the internal form before tokenization, so every
 * later stage sees one codepoint per phoneme regardless of which of the four
 * Uzbek orthographies the text arrived in.
 *
 * <p>The offset map is the fiddly part and the reason this extends
 * {@link BaseCharFilter}. Normalization changes length in both directions —
 * {@code sh} becomes {@code ş} and shrinks, Cyrillic {@code ю} becomes {@code yu}
 * and grows — so without correction every highlighted fragment drifts further
 * from its word the deeper into the field it sits. {@code NormalizedForm} carries
 * the source index of each output character; this turns that into the cumulative
 * diffs Lucene expects.
 */
public final class UzbekNormalizeCharFilter extends BaseCharFilter {

    private final char[] output;
    private int position;

    public UzbekNormalizeCharFilter(Reader in) {
        super(in);
        String raw = read(in);
        NormalizedForm form = UzNormalizer.normalize(raw);
        this.output = form.internal().toCharArray();

        int[] src = form.srcIndex();
        int lastDiff = 0;
        for (int i = 0; i < output.length && i < src.length; i++) {
            int diff = src[i] - i;
            if (diff != lastDiff) {
                addOffCorrectMap(i, diff);
                lastDiff = diff;
            }
        }
        // Close the map at the end of the stream so a trailing highlight lands
        // on the end of the source text, not the end of the shorter output.
        int tailDiff = raw.length() - output.length;
        if (tailDiff != lastDiff) {
            addOffCorrectMap(output.length, tailDiff);
        }
    }

    @Override
    public int read(char[] cbuf, int off, int len) {
        if (position >= output.length) return -1;
        int n = Math.min(len, output.length - position);
        System.arraycopy(output, position, cbuf, off, n);
        position += n;
        return n;
    }

    private static String read(Reader in) {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[2048];
        try {
            int n;
            while ((n = in.read(buf)) != -1) sb.append(buf, 0, n);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return sb.toString();
    }
}
