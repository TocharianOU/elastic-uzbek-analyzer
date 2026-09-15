/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.tocharian.uzbek.dev;

import org.tocharian.uzbek.script.NormalizedForm;
import org.tocharian.uzbek.script.ScriptDetector;
import org.tocharian.uzbek.script.ScriptId;
import org.tocharian.uzbek.protect.Protection;
import org.tocharian.uzbek.protect.ProtectionMarker;
import org.tocharian.uzbek.protect.ProtectionVerdict;
import org.tocharian.uzbek.script.UzNormalizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

/**
 * Layer 1 measured on a real lexicon rather than hand-picked examples.
 *
 * <p>Quantifies the cost of each folding policy: how many genuinely distinct
 * stems end up sharing one search key. That number is the precision price paid
 * for tolerating omitted apostrophes, and it is the only honest way to choose
 * between folding policies.
 */
public final class ScaleTest {

    public static void main(String[] args) throws Exception {
        Path p = Path.of(args.length > 0 ? args[0] : "/tmp/uzcsv/out/stems.txt");
        List<String> stems = Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        System.out.println("stems (deduped): " + stems.size());

        // ---- 1. what Layer 0 makes of a real lexicon -------------------
        Map<ScriptId, Integer> byScript = new EnumMap<>(ScriptId.class);
        long t0 = System.nanoTime();
        List<NormalizedForm> forms = new ArrayList<>(stems.size());
        for (String s : stems) {
            NormalizedForm f = UzNormalizer.normalize(s);
            forms.add(f);
            byScript.merge(f.source(), 1, Integer::sum);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("normalized %d stems in %d ms  (%.0f stems/ms)%n%n",
                stems.size(), ms, ms == 0 ? Double.NaN : stems.size() / (double) ms);

        System.out.println("--- L0 verdict distribution over the real lexicon ---");
        byScript.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %-20s %6d  %5.1f%%%n",
                        e.getKey(), e.getValue(), 100.0 * e.getValue() / stems.size()));

        // ---- 2. how many stems even have the ambiguous letters ---------
        int withOG = 0, withSC = 0, withTutuq = 0;
        for (NormalizedForm f : forms) {
            String in = f.internal();
            if (in.indexOf('ö') >= 0 || in.indexOf('ğ') >= 0) withOG++;
            if (in.indexOf('ş') >= 0 || in.indexOf('ç') >= 0) withSC++;
            if (in.indexOf('ʼ') >= 0) withTutuq++;
        }
        System.out.printf("%n--- letters at stake ---%n");
        System.out.printf("  stems containing ö or ğ : %6d  (%.1f%%)%n", withOG, 100.0 * withOG / stems.size());
        System.out.printf("  stems containing ş or ç : %6d  (%.1f%%)%n", withSC, 100.0 * withSC / stems.size());
        System.out.printf("  stems containing tutuq ʼ: %6d  (%.1f%%)%n", withTutuq, 100.0 * withTutuq / stems.size());

        // ---- 3. collision cost of each folding policy ------------------
        System.out.printf("%n--- collision cost by folding policy ---%n");
        report("A  none (internal form as key)", forms, ScaleTest::policyNone);
        report("B  ours (ö→o ğ→g ç→c, drop ʼ)", forms, UzNormalizer::toSearchKey);
        report("C  blanket NFD (also ş→s)",     forms, ScaleTest::policyNfd);

        protectionCost(stems);
    }

    /**
     * Layer 3 acceptance metric.
     *
     * <p>Every entry here is a real Uzbek stem from a curated lexicon, so any
     * protection at all is a candidate false positive. Not all of them are errors —
     * a dictionary of Uzbek legitimately contains naturalised loanwords such as
     * {@code telefon} — so the breakdown separates exact list hits (intended) from
     * prefix extensions (the rule that can over-fire).
     *
     * <p>Over-protection is the expensive error: a wrongly marked stem loses every
     * one of its inflected forms from search.
     */
    private static void protectionCost(List<String> stems) {
        ProtectionMarker pm = ProtectionMarker.get();
        System.out.printf("%n--- L3 false-protection cost on %d real Uzbek stems ---%n", stems.size());
        System.out.printf("    lists: %d brands, %d loanword stems, %d function words%n",
                pm.brandCount(), pm.loanwordCount(), pm.functionWordCount());

        Map<ProtectionVerdict.Reason, Integer> byReason = new EnumMap<>(ProtectionVerdict.Reason.class);
        Map<ProtectionVerdict.Reason, List<String>> examples = new EnumMap<>(ProtectionVerdict.Reason.class);
        int protectedCount = 0, prefixExtension = 0;
        List<String> prefixExamples = new ArrayList<>();

        for (String stem : stems) {
            ProtectionVerdict v = pm.check(stem);
            if (v.level() == Protection.NONE) continue;
            protectedCount++;
            byReason.merge(v.reason(), 1, Integer::sum);
            examples.computeIfAbsent(v.reason(), k -> new ArrayList<>());
            if (examples.get(v.reason()).size() < 8) examples.get(v.reason()).add(stem);

            // a loanword hit that is NOT the whole word = the prefix rule extending
            if (v.reason() == ProtectionVerdict.Reason.LOANWORD_STEM
                    && v.matched() != null
                    && !UzNormalizer.normalize(stem).searchKey().equals(v.matched())) {
                prefixExtension++;
                if (prefixExamples.size() < 15) {
                    prefixExamples.add(stem + "<-" + v.matched());
                }
            }
        }

        System.out.printf("%n  protected: %d / %d  (%.2f%%)%n",
                protectedCount, stems.size(), 100.0 * protectedCount / stems.size());
        for (Map.Entry<ProtectionVerdict.Reason, Integer> e : byReason.entrySet()) {
            System.out.printf("    %-18s %5d   %s%n", e.getKey(), e.getValue(),
                    String.join(" ", examples.getOrDefault(e.getKey(), List.of())));
        }
        System.out.printf("%n  of which prefix extensions (the over-fire risk): %d  (%.3f%%)%n",
                prefixExtension, 100.0 * prefixExtension / stems.size());
        for (String ex : prefixExamples) System.out.println("        " + ex);
    }

    private static String policyNone(String internal) {
        return internal;
    }

    /** alifbo's {@code foldSearchKeyLoose}: strip every combining mark. */
    private static String policyNfd(String internal) {
        String d = Normalizer.normalize(UzNormalizer.toSearchKey(internal), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            if (Character.getType(d.charAt(i)) != Character.NON_SPACING_MARK) sb.append(d.charAt(i));
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC);
    }

    private static void report(String label, List<NormalizedForm> forms,
                               java.util.function.Function<String, String> keyFn) {
        Map<String, Set<String>> buckets = new HashMap<>();
        for (NormalizedForm f : forms) {
            buckets.computeIfAbsent(keyFn.apply(f.internal()), k -> new LinkedHashSet<>())
                   .add(f.internal());
        }
        int collidingKeys = 0, wordsLost = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : buckets.entrySet()) {
            if (e.getValue().size() > 1) {
                collidingKeys++;
                wordsLost += e.getValue().size() - 1;
                if (examples.size() < 6) examples.add(e.getKey() + " <- " + e.getValue());
            }
        }
        System.out.printf("  %-32s keys=%6d  colliding=%4d  merged-away=%4d (%.2f%%)%n",
                label, buckets.size(), collidingKeys, wordsLost, 100.0 * wordsLost / forms.size());
        for (String ex : examples) System.out.println("        " + ex);
    }
}
