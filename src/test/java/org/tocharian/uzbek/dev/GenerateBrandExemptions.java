package org.tocharian.uzbek.dev;

import org.tocharian.uzbek.script.*;
import java.nio.file.*; import java.nio.charset.StandardCharsets; import java.util.*;

/** Build-time generator for brand-exemptions.txt. Run after editing brands.txt. */
public class GenerateBrandExemptions { public static void main(String[] a) throws Exception {
  Set<String> lex=new HashSet<>();
  for(String s: Files.readAllLines(Path.of(a[0]),StandardCharsets.UTF_8)){
    s=s.trim(); if(!s.isEmpty()) lex.add(UzNormalizer.normalize(s).searchKey()); }
  TreeMap<String,List<String>> ex=new TreeMap<>();
  for(String line: Files.readAllLines(Path.of("src/main/resources/uz_lex/brands.txt"),StandardCharsets.UTF_8)){
    line=line.trim(); if(line.isEmpty()||line.startsWith("#"))continue;
    String k=UzNormalizer.normalize(line).searchKey();
    if(lex.contains(k)) ex.computeIfAbsent(k,x->new ArrayList<>()).add(line);
  }
  StringBuilder sb=new StringBuilder();
  sb.append("# Brand entries that are ALSO real Uzbek dictionary stems.\n#\n");
  sb.append("# These are deliberately NOT given FULL protection. A brand present in the\n");
  sb.append("# lexicon needs none: Layer 4 finds it as a known root and leaves it whole.\n");
  sb.append("# Protecting it instead costs real recall, because the same string is often a\n");
  sb.append("# common Uzbek word — 'uzum' is the marketplace AND the word for grape, 'bosh'\n");
  sb.append("# (Бош = Bosch) is the word for head, 'olcha' is cherry, 'ravon' is fluent.\n");
  sb.append("# Blocking morphology on those removes every inflected form from search.\n#\n");
  sb.append("# Protection is for brands the lexicon does NOT know, whose affix-stripping\n");
  sb.append("# fallback in Layer 4 would mangle them.\n#\n");
  sb.append("# GENERATED from brands.txt x a 46k-stem Uzbek lexicon. Regenerate after\n");
  sb.append("# editing brands.txt; do not hand-edit. Format: key<TAB>spellings\n\n");
  for(Map.Entry<String,List<String>> e: ex.entrySet())
    sb.append(e.getKey()).append('\t').append(String.join(", ",e.getValue())).append('\n');
  Files.writeString(Path.of("src/main/resources/uz_lex/brand-exemptions.txt"), sb.toString(), StandardCharsets.UTF_8);
  System.out.println("exempted "+ex.size()+" brand keys: "+ex.keySet());
}}
