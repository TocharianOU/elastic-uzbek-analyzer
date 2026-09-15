/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.tocharian.uzbek.script.Apostrophes;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Layer 2 — split Uzbek catalogue text into tokens.
 *
 * <p>Two things the {@code standard} tokenizer gets wrong here.
 *
 * <p><b>The apostrophe is a letter.</b> In {@code qopqogʻi} the modifier letter is
 * part of {@code gʻ}, and in {@code Isʼhoq} the tutuq belgisi is what keeps
 * {@code s+h} from being read as the digraph {@code sh}. Treating either as
 * punctuation splits a word in half.
 *
 * <p><b>Model codes must survive whole.</b> {@code SM-G998B} and {@code 128GB} are
 * high-intent queries in a shop; breaking them into {@code SM} and {@code G998B}
 * loses the sale. So a run containing a digit is emitted intact, separators and
 * all, while a run without one is split on those separators as usual — which keeps
 * ordinary hyphenated words such as {@code koʻk-yashil} behaving normally.
 */
public final class UzbekTokenizer extends Tokenizer {

    private static final int MAX_TOKEN_LENGTH = 255;

    /** Joiners kept inside a token only when the run turns out to carry a digit. */
    private static final String CONDITIONAL_JOINERS = "-./_";

    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAttr = addAttribute(OffsetAttribute.class);

    /** Tokens already carved out of the current run, waiting to be emitted. */
    private final Deque<Token> pending = new ArrayDeque<>();

    private final StringBuilder run = new StringBuilder();
    private int offset = 0;
    private int finalOffset = 0;
    private int pushedBack = -1;

    private record Token(String text, int start, int end) {}

    @Override
    public boolean incrementToken() throws IOException {
        clearAttributes();

        while (pending.isEmpty()) {
            if (!readRun()) return false;
        }

        Token t = pending.poll();
        termAttr.setEmpty().append(t.text());
        offsetAttr.setOffset(correctOffset(t.start()), correctOffset(t.end()));
        return true;
    }

    /** Read one maximal run of token characters and queue what it yields. */
    private boolean readRun() throws IOException {
        run.setLength(0);
        int runStart = -1;

        while (true) {
            int c = next();
            if (c == -1) {
                finalOffset = offset;
                return runStart >= 0 && emitRun(runStart);
            }
            offset++;

            if (isTokenChar((char) c) || (runStart >= 0 && isConditionalJoiner((char) c))) {
                if (runStart < 0) {
                    if (isConditionalJoiner((char) c)) continue;   // never start on a joiner
                    runStart = offset - 1;
                }
                if (run.length() < MAX_TOKEN_LENGTH) run.append((char) c);
            } else if (runStart >= 0) {
                return emitRun(runStart);
            }
        }
    }

    /**
     * Decide what a finished run yields.
     *
     * <p>Trailing joiners are pushed back so a sentence-final {@code kitob.} does
     * not keep the period.
     */
    private boolean emitRun(int runStart) {
        while (run.length() > 0 && isConditionalJoiner(run.charAt(run.length() - 1))) {
            run.setLength(run.length() - 1);
        }
        if (run.length() == 0) return false;

        String text = run.toString();
        if (containsDigit(text)) {
            pending.add(new Token(text, runStart, runStart + text.length()));
            return true;
        }

        int pieceStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean atEnd = i == text.length();
            if (atEnd || isConditionalJoiner(text.charAt(i))) {
                if (i > pieceStart) {
                    pending.add(new Token(text.substring(pieceStart, i),
                            runStart + pieceStart, runStart + i));
                }
                pieceStart = i + 1;
            }
        }
        return !pending.isEmpty();
    }

    private int next() throws IOException {
        if (pushedBack >= 0) {
            int c = pushedBack;
            pushedBack = -1;
            return c;
        }
        return input.read();
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || Apostrophes.isApostrophe(c);
    }

    private static boolean isConditionalJoiner(char c) {
        return CONDITIONAL_JOINERS.indexOf(c) >= 0;
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    @Override
    public void end() throws IOException {
        super.end();
        offsetAttr.setOffset(correctOffset(finalOffset), correctOffset(finalOffset));
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        pending.clear();
        run.setLength(0);
        offset = 0;
        finalOffset = 0;
        pushedBack = -1;
    }
}
