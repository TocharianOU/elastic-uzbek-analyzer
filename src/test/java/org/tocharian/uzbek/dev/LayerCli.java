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

import java.util.List;

/**
 * Standalone exerciser for one layer at a time. No Elasticsearch, no Lucene —
 * each layer is a pure function, so it can be measured on its own before it is
 * wired into the plugin.
 *
 * <pre>
 *   java ... LayerCli l0        # orthography / language identification
 *   java ... LayerCli l1        # normalization to the internal form
 *   java ... LayerCli fold      # cross-script equivalence (the one that matters)
 *   java ... LayerCli offsets   # offset-map integrity
 * </pre>
 */
public final class LayerCli {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        String layer = args.length > 0 ? args[0] : "all";
        switch (layer) {
            case "l0"      -> l0();
            case "l1"      -> l1();
            case "fold"    -> fold();
            case "l3" -> l3();
            case "leaks" -> leaks();
            case "homoglyph" -> homoglyph();
            case "offsets" -> offsets();
            default        -> { l0(); l1(); fold(); leaks(); homoglyph(); offsets(); l3(); }
        }
        System.out.printf("%n==== %d passed, %d failed ====%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ Layer 0

    private static void l0() {
        head("L0  orthography / language identification");
        // text, expected ScriptId
        Object[][] cases = {
            {"Oʻzbekiston shaharlari",        ScriptId.UZ_LATN_1995},
            {"Özbekiston şaharlari",     ScriptId.UZ_LATN_2026},
            {"Ўзбекистон шаҳарлари", ScriptId.UZ_CYRL},
            {"Новый детский телефон", ScriptId.RU_CYRL},
            {"Samsung Galaxy S24 Ultra",           ScriptId.LATN_UNDETERMINED},
            {"Windows noutbuk",                    ScriptId.LATN_OTHER},
            // brand made only of Uzbek-legal letters: undecidable at L0 by design -> L3 handles it
            {"Lenovo IdeaPad Slim",                ScriptId.LATN_UNDETERMINED},
            {"Xiaomi Redmi Watch",                 ScriptId.LATN_OTHER},   // w
            {"Telefon uchun qopqoq",               ScriptId.LATN_UNDETERMINED},
            {"Ноутбук Lenovo IdeaPad", ScriptId.MIXED},
            {"128GB / 256GB",                      ScriptId.LATN_UNDETERMINED},
            {"15 999 000",                         ScriptId.NON_LINGUISTIC},
            {"ماشین",     ScriptId.UZ_ARAB},
        };
        for (Object[] c : cases) {
            String text = (String) c[0];
            ScriptId want = (ScriptId) c[1];
            ScriptDetector.Detection d = ScriptDetector.detect(text);
            check(d.script() == want, String.format("%-34s -> %s", trunc(text, 32), d));
        }
    }

    // ------------------------------------------------------------ Layer 1

    private static void l1() {
        head("L1  normalization to internal form");
        String[] samples = {
            "Oʻzbekiston",                    // ʻ  U+02BB  correct
            "O'zbekiston",                         // '  U+0027  ASCII
            "O’zbekiston",                    // '  U+2019  autocorrect
            "O`zbekiston",                         // `  U+0060  keyboard slip
            "Ozbekiston",                          //    omitted entirely
            "Özbekiston",                     // ö  2026
            "qopqogʻi",
            "shahar",
            "chexol",
            "yosh bola",
            "Isʼhoq",                         // s + tutuq + h  != sh
            "шаҳар",      // шаҳар
            "Елена",      // Елена -> Yelena
            "цирк",            // цирк
            "менинг",// менинг
        };
        for (String s : samples) {
            NormalizedForm f = UzNormalizer.normalize(s);
            System.out.printf("  %-18s %-12s int=%-16s key=%-16s%s%n",
                trunc(s, 17), f.source(), f.internal(), f.searchKey(),
                f.hasAmbiguity() ? "  " + f.ambiguities() : "");
        }
    }

    // ------------------------ the test that decides the product ----------

    private static void fold() {
        head("FOLD  cross-script / cross-spelling equivalence");
        String[][] groups = {
            // every real-world spelling of "its lid" must collapse to one key
            {"qopqogʻi", "qopqog'i", "qopqog’i", "qopqog`i", "qopqogi",
             "qopqoği", "қопқоғи"},
            // "Uzbekistan"
            {"Oʻzbekiston", "O'zbekiston", "Ozbekiston", "Özbekiston",
             "Ўзбекистон"},
            // "city" — sh digraph vs ş vs Cyrillic ш
            {"shahar", "şahar", "шаҳар"},
            // "case/cover" — a Russian loanword written three ways
            {"chexol", "çexol", "чехол"},
            // "word" with omitted apostrophe
            {"soʻz", "so'z", "soz", "söz", "сўз"},
        };
        for (String[] g : groups) {
            String base = UzNormalizer.normalize(g[0]).searchKey();
            StringBuilder detail = new StringBuilder();
            boolean ok = true;
            for (String v : g) {
                String k = UzNormalizer.normalize(v).searchKey();
                if (!k.equals(base)) ok = false;
                detail.append(String.format("%n        %-16s -> %s", v, k));
            }
            check(ok, "group key=" + base + detail);
        }
    }

    // ------------------------------------------------------------ Layer 3

    private static void l3() {
        head("L3  protection: what Layer 4 must not touch");
        ProtectionMarker pm = ProtectionMarker.get();
        System.out.printf("  lists: %d brands, %d loanword stems, %d function words%n%n",
                pm.brandCount(), pm.loanwordCount(), pm.functionWordCount());

        // token, expected level
        Object[][] cases = {
            // model codes and capacities
            {"SM-G998B",   Protection.FULL},
            {"128GB",      Protection.FULL},
            {"A2650",      Protection.FULL},
            // brands the lexicon does NOT know: protect, or L4's OOV fallback mangles them
            {"Galaxy",     Protection.FULL},
            {"Lenovo",     Protection.FULL},          // L0 cannot see this is a brand
            {"Redmi",      Protection.FULL},
            {"Huawei",     Protection.FULL},
            // brands the lexicon DOES know: no protection needed, L4 has them as roots.
            // Protecting these would also block the common Uzbek homonyms below.
            {"Samsung",    Protection.NONE},
            {"iPhone",     Protection.NONE},
            {"\u0421\u0430\u043C\u0441\u0443\u043D\u0433", Protection.NONE}, // Самсунг
            // the homonyms that made the exemption necessary
            {"uzum",       Protection.NONE},          // grape / the marketplace
            {"bosh",       Protection.NONE},          // head  / Бош = Bosch
            {"olcha",      Protection.NONE},          // cherry / Olcha
            {"ravon",      Protection.NONE},          // fluent / Ravon
            {"Windows",    Protection.FULL},          // w -> NON_UZBEK_LETTER, no list needed
            {"\u043D\u043E\u0432\u044B\u0439", Protection.FULL},  // новый -> RUSSIAN_SCRIPT
            {"va",         Protection.FULL},          // function word
            {"lekin",      Protection.FULL},          // function word
            // loanword stems: locked, but affixes stay strippable
            {"zaryadka",   Protection.STEM_LOCKED},
            {"zaryadkani", Protection.STEM_LOCKED},
            {"telefon",    Protection.STEM_LOCKED},
            {"telefonlar", Protection.STEM_LOCKED},
            {"chexollar",  Protection.STEM_LOCKED},
            // real Uzbek words: must stay open
            {"kitob",      Protection.NONE},
            {"kitoblarimiz", Protection.NONE},
            {"shahar",     Protection.NONE},
            {"o\u02BBqituvchi", Protection.NONE},
            {"yax\u015Fi", Protection.NONE},
            {"olma",       Protection.NONE},
            {"non",        Protection.NONE},
        };
        for (Object[] c : cases) {
            String tok = (String) c[0];
            Protection want = (Protection) c[1];
            ProtectionVerdict v = pm.check(tok);
            check(v.level() == want,
                String.format("%-14s -> %-12s %s", tok, v.level(), v.reason()
                    + (v.matched() == null ? "" : ":" + v.matched())));
        }
    }

    // -------------------------------------------------------- leak guard

    private static void leaks() {
        head("LEAK  no Cyrillic may survive into the key");
        // Russian product text is everywhere in an Uzbek catalogue. An unmapped
        // letter leaves a hybrid token that no Latin query can ever produce.
        String[] samples = {
            "\u043D\u043E\u0432\u044B\u0439",              // новый
            "\u043C\u044B\u043B\u043E",                      // мыло
            "\u0449\u0451\u0442\u043A\u0430",              // щётка
            "\u0434\u0435\u0442\u0441\u043A\u0438\u0439", // детский
            "\u043F\u0430\u043B\u044C\u0442\u043E",        // пальто
            "\u043E\u0431\u044A\u0435\u043A\u0442",        // объект
            "\u0437\u0430\u0440\u044F\u0434\u043A\u0430", // зарядка
            "\u043D\u0430\u0443\u0448\u043D\u0438\u043A", // наушник
        };
        for (String s : samples) {
            NormalizedForm f = UzNormalizer.normalize(s, ScriptId.UZ_CYRL);
            StringBuilder leak = new StringBuilder();
            for (int i = 0; i < f.internal().length(); i++) {
                char c = f.internal().charAt(i);
                if (c >= '\u0400' && c <= '\u04FF') leak.append(String.format("U+%04X ", (int) c));
            }
            check(leak.length() == 0,
                String.format("%-14s -> int=%-14s key=%-14s%s", s, f.internal(), f.searchKey(),
                    leak.length() == 0 ? "" : "  LEAK " + leak));
        }
    }

    // ------------------------------------------------------- homoglyphs

    private static void homoglyph() {
        head("HOMOGLYPH  stray Cyrillic letters inside Latin words");
        // Real defects found in a curated 46k-stem academic lexicon: core Uzbek
        // verb stems carrying a Cyrillic \u0435 among Latin letters.
        String[][] cases = {
            {"k\u0435l",   "kel"},    // come
            {"s\u0435v",   "sev"},    // love
            {"t\u0435p",   "tep"},    // kick
            {"ch\u0435k",  "\u00E7ek"},  // ch -> \u00E7
            {"z\u0435rik", "zerik"},  // be bored
            {"s\u0435p mayda urug\u02BB", "sep mayda uru\u011F"},  // evidence-less word, Latin context
        };
        for (String[] c : cases) {
            NormalizedForm f = UzNormalizer.normalize(c[0]);
            check(f.internal().equals(c[1]) && f.source().isUzbek(),
                String.format("%-22s -> int=%-18s %s", c[0], f.internal(), f.source()));
        }
    }

    // --------------------------------------------------------- offset map

    private static void offsets() {
        head("OFFSETS  srcIndex integrity (highlighting depends on this)");
        String[] samples = {
            "Oʻzbekiston shaharlari",
            "Ўзбекистон юрти",
            "Lenovo IdeaPad ноутбук 15\"",
        };
        for (String s : samples) {
            NormalizedForm f = UzNormalizer.normalize(s);
            int[] ix = f.srcIndex();
            boolean lenOk = ix.length == f.internal().length();
            boolean monotonic = true, inRange = true;
            for (int i = 0; i < ix.length; i++) {
                if (ix[i] < 0 || ix[i] >= s.length()) inRange = false;
                if (i > 0 && ix[i] < ix[i - 1]) monotonic = false;
            }
            check(lenOk && monotonic && inRange, String.format(
                "%-30s len=%s monotonic=%s inRange=%s  (%d->%d chars)",
                trunc(s, 28), lenOk, monotonic, inRange, s.length(), f.internal().length()));
        }
    }

    // ------------------------------------------------------------- helpers

    private static void head(String t) {
        System.out.println("\n--- " + t + " " + "-".repeat(Math.max(0, 58 - t.length())));
    }

    private static void check(boolean ok, String msg) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + msg);
        if (ok) pass++; else fail++;
    }

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
