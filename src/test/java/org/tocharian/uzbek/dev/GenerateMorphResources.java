package org.tocharian.uzbek.dev;

import org.tocharian.uzbek.script.UzNormalizer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Build-time generator for the Layer 4 resources.
 *
 * <p>Reads the third-party linguistic sources from {@code dependencies/} — which
 * is not part of this repository — and writes the three tables the analyzer
 * loads. Run it again whenever a source is updated.
 *
 * <pre>
 *   java ... GenerateMorphResources &lt;stems.txt&gt; &lt;dependencies-dir&gt;
 * </pre>
 *
 * <p>Everything is keyed on the Layer 1 search key, so the morphology operates in
 * the same space the index does and an affix written {@code mish} matches a token
 * normalized to {@code miş}.
 */
public final class GenerateMorphResources {

    /** Archiphonemes used in the affix table, and the surfaces each stands for. */
    private static final Map<Character, String> ARCHI = Map.of(
        'T', "dt",     // voicing assimilation:   -Tir  -> dir / tir
        'G', "gkq",    // -Ga  -> ga / ka / qa
        'Y', "ay",     // present tense: -Ydi -> adi / ydi
        'Q', "qgk"     // causative:     -Qaz -> qaz / gaz / kaz
    );

    public static void main(String[] args) throws Exception {
        Path stems = Path.of(args[0]);
        Path deps  = Path.of(args[1]);
        Path outDir = Path.of("src/main/resources/uz_morph");
        Files.createDirectories(outDir);

        // The gold table is read first: it contributes lemmas to the root lexicon.
        Gold gold = readGold(deps);

        writeRoots(stems, deps, gold, outDir.resolve("roots.tsv"));
        writeForms(gold, outDir.resolve("forms.tsv"));
        writeAffixes(deps, outDir.resolve("affixes.tsv"));
        writeAllomorphs(deps, outDir.resolve("stem-allomorphs.tsv"));
    }

    // -------------------------------------------------------- gold table

    /** A UniMorph-style inflection table: surface form -> lemma, plus the lemmas themselves. */
    private record Gold(Map<String, String> forms, Map<String, TreeSet<String>> lemmaPos) {}

    private static final Map<String, String> UNIMORPH_POS = Map.of(
        "N", "NOUN", "V", "VERB", "ADJ", "ADJ", "ADV", "ADV",
        "PRON", "PRON", "NUM", "NUM", "CNJ", "CNJ", "INTJ", "INTJ", "IMIT", "IMIT");

    /**
     * Read the 145,844-row UniMorph table shipped with uzbek_morph.
     *
     * <p>Two conventions have to be reconciled. Their verb lemmas are the {@code -moq}
     * infinitive, ours is the bare stem, and a lookup that disagreed with the rules
     * would send {@code berdi} and {@code beribdi} to different terms depending on
     * which path happened to fire. So {@code -moq} is stripped — but only when what
     * remains is a verb stem the table itself attests, because {@code barmoq}
     * "finger" and {@code beshbarmoq} are nouns that merely end that way.
     */
    private static Gold readGold(Path deps) throws Exception {
        Path f = deps.resolve("uzbek-morph-rulebased/uzbek_morph/dataset/uzbek_unified.txt");
        if (!Files.exists(f)) {
            System.out.println("  (no uzbek_unified.txt; skipping the gold table)");
            return new Gold(Map.of(), Map.of());
        }
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);

        // Pass 1: every attested verb stem, so -moq can be stripped safely.
        Set<String> verbStems = new HashSet<>();
        for (String line : lines) {
            String[] p = line.split("\t");
            if (p.length < 3 || !p[2].startsWith("V")) continue;
            String lemma = p[0].trim();
            if (lemma.endsWith("moq")) {
                verbStems.add(UzNormalizer.normalize(lemma.substring(0, lemma.length() - 3)).searchKey());
            }
        }

        Map<String, String> forms = new HashMap<>();
        Map<String, TreeSet<String>> lemmaPos = new HashMap<>();
        int ambiguous = 0;

        for (String line : lines) {
            String[] p = line.split("\t");
            if (p.length < 3) continue;
            String lemmaKey = citation(UzNormalizer.normalize(p[0].trim()).searchKey(), verbStems);
            String surfaceKey = UzNormalizer.normalize(p[1].trim()).searchKey();
            if (lemmaKey.isEmpty() || surfaceKey.isEmpty()) continue;
            // 68% of the source is multi-word: compound and auxiliary-verb
            // constructions such as "afzal korar edi". A token-level analyzer
            // never sees those as one string, so carrying them would only pad
            // the plugin with entries nothing can look up.
            if (surfaceKey.indexOf(' ') >= 0 || lemmaKey.indexOf(' ') >= 0) continue;

            String pos = UNIMORPH_POS.getOrDefault(p[2].split(";")[0].trim(), "X");
            lemmaPos.computeIfAbsent(lemmaKey, k -> new TreeSet<>()).add(pos);

            if (surfaceKey.equals(lemmaKey)) continue;   // roots.tsv covers these

            String existing = forms.get(surfaceKey);
            if (existing == null) {
                forms.put(surfaceKey, lemmaKey);
            } else if (!existing.equals(lemmaKey)) {
                ambiguous++;
                // 0.2% of surfaces admit two readings, nearly all of them related
                // verb stems (ajratganlar is ajramoq or ajratmoq). Keep the lemma
                // that shares more of the surface, which is the one whose stem the
                // surface was actually built on.
                if (commonPrefix(surfaceKey, lemmaKey) > commonPrefix(surfaceKey, existing)) {
                    forms.put(surfaceKey, lemmaKey);
                }
            }
        }
        System.out.printf("gold table         %d single-token forms, %d lemmas (%d ambiguous)%n",
                forms.size(), lemmaPos.size(), ambiguous);
        return new Gold(forms, lemmaPos);
    }

    /** Their verb citation form is the -moq infinitive; ours is the bare stem. */
    private static String citation(String lemmaKey, Set<String> verbStems) {
        if (lemmaKey.endsWith("moq")) {
            String stem = lemmaKey.substring(0, lemmaKey.length() - 3);
            if (verbStems.contains(stem)) return stem;
        }
        return lemmaKey;
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static void writeForms(Gold gold, Path out) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Attested inflected forms and the lemma each belongs to.\n");
        sb.append("# Consulted before any rule runs: a form that is listed here needs no\n");
        sb.append("# analysis, and the rules only handle what is missing. Verb lemmas are\n");
        sb.append("# the bare stem, not the -moq infinitive, so lookup and rules agree.\n");
        sb.append("# GENERATED from uzbek_morph uzbek_unified.txt (Salaev, MIT). Do not hand-edit.\n");
        sb.append("# surface<TAB>lemma\n");
        new TreeMap<>(gold.forms()).forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("forms.tsv          " + gold.forms().size() + " forms");
    }

    // ------------------------------------------------------------- roots

    private static void writeRoots(Path stems, Path deps, Gold gold, Path out) throws Exception {
        // A root can carry several parts of speech — Uzbek noun/verb homonyms are
        // common — so this is a set per root, not a single tag.
        Map<String, TreeSet<String>> pos = new HashMap<>();
        Path csvDir = deps.resolve("data-lemma-stems-pos");
        Map<String, String> fileToPos = Map.ofEntries(
            Map.entry("Nouns", "NOUN"), Map.entry("Verbs", "VERB"),
            Map.entry("Adjectives", "ADJ"), Map.entry("Adverbs", "ADV"),
            Map.entry("Numerals", "NUM"), Map.entry("Pronouns", "PRON"),
            Map.entry("Conjunctions", "CNJ"), Map.entry("Particles", "PRT"),
            Map.entry("Particle", "PRT"), Map.entry("Auxiliaries", "AUX"),
            Map.entry("Interjections", "INTJ"), Map.entry("Imitations", "IMIT"));

        for (String line : Files.readAllLines(stems, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            pos.computeIfAbsent(UzNormalizer.normalize(s).searchKey(), k -> new TreeSet<>());
        }
        // POS comes from the per-part-of-speech CSVs, which the source ships inside
        // a zip. Read it in place rather than requiring a manual unpack.
        Path zip = csvDir.resolve("CSV_files.zip");
        if (Files.exists(zip)) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".csv")) continue;
                    String base = entry.getName();
                    base = base.substring(base.lastIndexOf('/') + 1).replace(".csv", "");
                    String tag = fileToPos.get(base);
                    if (tag == null) continue;
                    String raw = new String(zf.getInputStream(entry).readAllBytes(),
                                            StandardCharsets.UTF_8).replace("\uFEFF", "");
                    // Column 1 is the shared stem a family is built from; column 2
                    // lists the lemmas of THIS part of speech. Tagging column 1 by
                    // filename is wrong — Verbs.csv lists "abad" as the stem behind
                    // "abadiylash", but "abad" is not itself a verb.
                    for (String[] row : parseCsv(raw)) {
                        if (row.length < 2 || row[1].isBlank()) continue;
                        for (String lemma : row[1].split(",")) {
                            String k = UzNormalizer.normalize(lemma.trim()).searchKey();
                            if (k.isEmpty()) continue;
                            pos.computeIfAbsent(k, x -> new TreeSet<>()).add(tag);
                        }
                    }
                }
            }
        } else {
            System.out.println("  (no CSV_files.zip; every root tagged X)");
        }
        // Lemmas the gold table knows and the stem list does not.
        int added = 0;
        for (Map.Entry<String, TreeSet<String>> e : gold.lemmaPos().entrySet()) {
            if (!pos.containsKey(e.getKey())) added++;
            pos.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
        }
        pos.remove("");
        System.out.println("  + " + added + " lemmas from the gold table");

        StringBuilder sb = new StringBuilder();
        sb.append("# Uzbek root lexicon, keyed on the Layer 1 search key.\n");
        sb.append("# GENERATED from UzbekLemmaStems-POS-Dataset (Apache-2.0). Do not hand-edit.\n");
        sb.append("# A root may carry several parts of speech; all are listed. X means the\n");
        sb.append("# source gives none, in which case the analyzer applies no POS constraint.\n");
        sb.append("# key<TAB>pos[,pos...]\n");
        new TreeMap<>(pos).forEach((k, v) ->
            sb.append(k).append('\t').append(v.isEmpty() ? "X" : String.join(",", v)).append('\n'));
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("roots.tsv          " + pos.size() + " roots");
    }

    // ----------------------------------------------------------- affixes

    private static void writeAffixes(Path deps, Path out) throws Exception {
        Path f = deps.resolve("uzmorphanalyser/src/UzMorphAnalyser/affixes.csv");
        String raw = Files.readString(f, StandardCharsets.UTF_8)
                          .replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        List<String[]> rows = parseCsv(raw);
        String[] hdr = rows.get(0);
        int iAffix = indexOf(hdr, "affix"), iPos = indexOf(hdr, "pos"), iCase = indexOf(hdr, "cases");

        // surface -> every POS the source attributes to it. One surface routinely
        // belongs to several: -i is a possessive on a noun and part of a verb
        // ending elsewhere, so storing only the first would make the POS check
        // below reject valid analyses.
        TreeMap<String, TreeSet<String>> expanded = new TreeMap<>();
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            if (row.length <= iAffix) continue;
            String affix = row[iAffix].trim();
            if (affix.isEmpty()) continue;
            String pos = (row.length > iPos ? row[iPos].trim() : "");
            if (pos.isEmpty()) pos = "X";
            for (String surface : expand(affix)) {
                String key = UzNormalizer.normalize(surface).searchKey();
                if (key.isEmpty()) continue;
                expanded.computeIfAbsent(key, k -> new TreeSet<>()).add(pos);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Uzbek inflectional affix surfaces, keyed on the Layer 1 search key.\n");
        sb.append("# GENERATED from UzMorphAnalyser affixes.csv (Salaev, MIT). Do not hand-edit.\n");
        sb.append("# The source enumerates whole affix CHAINS rather than slots, so this is a\n");
        sb.append("# surface list: -larimizdangina is one entry, not four. Optional buffer\n");
        sb.append("# segments (i) (a) (t) (s) (n) and the archiphonemes T=d/t G=g/k/q Y=a/y\n");
        sb.append("# Q=q/g/k are expanded into every surface here.\n");
        sb.append("# Every POS the source gives a surface is listed, because one surface\n");
        sb.append("# often serves several: -i is a possessive on a noun and part of a verb\n");
        sb.append("# ending elsewhere. The analyzer uses this to reject a verb affix sitting\n");
        sb.append("# on a stem the lexicon calls a noun.\n");
        sb.append("# surface<TAB>pos[,pos...]\n");
        expanded.forEach((k, v) -> sb.append(k).append('\t').append(String.join(",", v)).append('\n'));
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("affixes.tsv        " + expanded.size() + " surfaces");
    }

    /** Expand {@code (x)} optionals and archiphoneme letters into concrete surfaces. */
    static List<String> expand(String affix) {
        List<String> work = new ArrayList<>(List.of(affix));

        // optional buffer segments: (i)m -> im, m
        boolean changed = true;
        while (changed) {
            changed = false;
            List<String> next = new ArrayList<>();
            for (String s : work) {
                int open = s.indexOf('(');
                int close = open >= 0 ? s.indexOf(')', open) : -1;
                if (open < 0 || close < 0) { next.add(s); continue; }
                String inner = s.substring(open + 1, close);
                String head = s.substring(0, open), tail = s.substring(close + 1);
                next.add(head + inner + tail);
                next.add(head + tail);
                changed = true;
            }
            work = next;
        }

        // archiphonemes
        for (Map.Entry<Character, String> e : ARCHI.entrySet()) {
            List<String> next = new ArrayList<>();
            for (String s : work) {
                int at = s.indexOf(e.getKey());
                if (at < 0) { next.add(s); continue; }
                for (char c : e.getValue().toCharArray()) {
                    next.add(s.substring(0, at) + c + s.substring(at + 1));
                }
            }
            work = next;
        }
        // a second pass catches affixes carrying two different archiphonemes
        List<String> out = new ArrayList<>();
        for (String s : work) {
            if (s.chars().anyMatch(c -> ARCHI.containsKey((char) c))) out.addAll(expand(s));
            else out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // -------------------------------------------------------- allomorphs

    private static void writeAllomorphs(Path deps, Path out) throws Exception {
        TreeMap<String, TreeSet<String>> map = new TreeMap<>();
        Path dir = deps.resolve("data-inflectional-units");
        for (String f : List.of("Inflectional_change.xml", "Inflectional_decrease.xml",
                                "Inflectional_increase.xml")) {
            Path p = dir.resolve(f);
            if (!Files.exists(p)) continue;
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(p.toFile());
            NodeList rows = doc.getElementsByTagName("row");
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                String stem = text(row, "stem_inflectional");
                String lemma = text(row, "lemma");
                if (stem.isEmpty() || lemma.isEmpty()) continue;
                String sk = UzNormalizer.normalize(stem).searchKey();
                for (String l : lemma.split("[,;]")) {
                    String lk = UzNormalizer.normalize(l.trim()).searchKey();
                    if (lk.isEmpty() || lk.equals(sk)) continue;
                    map.computeIfAbsent(sk, k -> new TreeSet<>()).add(lk);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Stem allomorphs: the shape a stem takes under inflection, and the lemma.\n");
        sb.append("# Covers the two morphophonemic processes a suffix-stripper cannot undo on\n");
        sb.append("# its own: the dropped vowel (ayir+il -> ayril, so 'ayr' must resolve to\n");
        sb.append("# 'ayir') and voicing of a final voiceless stop (tilak+im -> tilagim).\n");
        sb.append("# GENERATED from UzbekInflectionalWords (Sharipov, MIT). Do not hand-edit.\n");
        sb.append("# inflected_stem<TAB>lemma[,lemma...]\n");
        map.forEach((k, v) -> sb.append(k).append('\t').append(String.join(",", v)).append('\n'));
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("stem-allomorphs.tsv " + map.size() + " stems");
    }

    // ------------------------------------------------------------ helpers

    private static String text(Element row, String tag) {
        NodeList n = row.getElementsByTagName(tag);
        return n.getLength() == 0 || n.item(0).getTextContent() == null
                ? "" : n.item(0).getTextContent().trim();
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].trim().equalsIgnoreCase(v)) return i;
        return -1;
    }

    private static List<String> readCsvFirstColumn(Path f) throws Exception {
        List<String> out = new ArrayList<>();
        String raw = Files.readString(f, StandardCharsets.UTF_8).replace("﻿", "");
        for (String[] row : parseCsv(raw)) if (row.length > 0 && !row[0].isBlank()) out.add(row[0].trim());
        return out;
    }

    /** Minimal RFC4180 reader: quoted fields, embedded commas and newlines. */
    static List<String[]> parseCsv(String raw) {
        List<String[]> rows = new ArrayList<>();
        List<String> field = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') inQuotes = true;
            else if (c == ',') { field.add(cur.toString()); cur.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (cur.length() > 0 || !field.isEmpty()) {
                    field.add(cur.toString()); cur.setLength(0);
                    rows.add(field.toArray(new String[0])); field = new ArrayList<>();
                }
            } else cur.append(c);
        }
        if (cur.length() > 0 || !field.isEmpty()) {
            field.add(cur.toString());
            rows.add(field.toArray(new String[0]));
        }
        return rows;
    }
}
