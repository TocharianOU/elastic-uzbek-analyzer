package org.tocharian.uzbek.dev;

import org.tocharian.uzbek.morph.Analysis;
import org.tocharian.uzbek.morph.UzMorphAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * How much does the attested-form table actually buy?
 *
 * <p>Treats the table as a held-out test set: every one of its entries is a
 * surface form with a linguist's lemma attached. Lookup is bypassed and the rules
 * are asked for an answer, which is then compared against the gold one. What the
 * rules get wrong is exactly what the table is worth.
 */
public final class RuleAccuracy {

    public static void main(String[] args) throws Exception {
        Path forms = Path.of(args.length > 0 ? args[0] : "src/main/resources/uz_morph/forms.tsv");
        UzMorphAnalyzer m = UzMorphAnalyzer.get();

        int total = 0, correct = 0, identity = 0;
        Map<Analysis.Method, int[]> byMethod = new EnumMap<>(Analysis.Method.class);
        List<String> wrong = new ArrayList<>();

        for (String line : Files.readAllLines(forms, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] p = line.split("\t");
            if (p.length < 2) continue;
            String surface = p[0], gold = p[1];

            Analysis a = m.analyzeWithRulesOnly(surface);
            total++;
            int[] slot = byMethod.computeIfAbsent(a.method(), k -> new int[2]);
            slot[0]++;
            if (a.lemma().equals(gold)) {
                correct++;
                slot[1]++;
            } else {
                if (a.method() == Analysis.Method.IDENTITY) identity++;
                if (wrong.size() < 12) wrong.add(surface + " -> " + a.lemma() + "  (gold " + gold + ")");
            }
        }

        System.out.printf("%nrules alone, measured on %d attested forms%n", total);
        System.out.printf("  correct   %6d   %5.1f%%%n", correct, 100.0 * correct / total);
        System.out.printf("  wrong     %6d   %5.1f%%   (of which left unanalysed: %d)%n",
                total - correct, 100.0 * (total - correct) / total, identity);
        System.out.println("\n  by rule path (correct / attempted):");
        byMethod.forEach((k, v) -> System.out.printf("    %-14s %6d / %-6d  %5.1f%%%n",
                k, v[1], v[0], 100.0 * v[1] / v[0]));
        System.out.println("\n  what the table fixes, e.g.:");
        wrong.forEach(w -> System.out.println("    " + w));
    }
}
