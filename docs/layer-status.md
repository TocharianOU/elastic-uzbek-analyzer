# Layer status

Each layer is a pure function with its own test entry point, so it can be
measured before the plugin exists. Run one at a time:

```bash
./gradlew test        # 35 JUnit tests across every layer
./gradlew assemble    # build/distributions/uzbek-analyzer-plugin-*.zip
```

The dev harnesses take a lexicon file and report measurements rather than
pass/fail:

```bash
CP=build/dev:src/main/resources
java -cp $CP org.tocharian.uzbek.dev.LayerCli    # l0 | l1 | fold | leaks | homoglyph | offsets | l3
java -cp $CP org.tocharian.uzbek.dev.ScaleTest stems.txt
```

| Layer | Class | State |
|---|---|---|
| L0 identify | `script.ScriptDetector` | done |
| L1 normalize | `script.UzNormalizer`, `Homoglyphs`, `Apostrophes`, `ArabicScript` | done; Perso-Arabic transliterated, lossily |
| L2 tokenize | `tokenize.UzbekTokenizer` | done |
| L3 protect | `protect.ProtectionMarker` | done (seed word lists) |
| L4 morphology | `morph.UzMorphAnalyzer` | done |
| L5 index recipe | `demo/index-recipe.json`, README | done |
| plugin wiring | `UzbekAnalyzer` + six `@NamedComponent` factories | done |

## Internal form

The Latin alphabet approved in September 2026: `ö ğ ş ç` replace `oʻ gʻ sh ch`.
One codepoint per phoneme, which is what lets later layers use plain character
logic instead of tracking digraph boundaries.

`ng` is **not** collapsed. It is a digraph in the official alphabet, and
collapsing it would be wrong across morpheme boundaries — `non+ga → nonga` is
/n/+/g/, not /ŋ/. Standard Uzbek has no productive vowel harmony, so no later
layer needs /ŋ/ as an atomic segment: treating the final `g` as voiced already
selects the right `-ga`/`-da` allomorph.

## Search-key folding

Folded by collision risk, not by "is it a diacritic":

| Fold | Collision risk | Decision |
|---|---|---|
| `ç→c` | none — `c` is not a letter of the Uzbek alphabet | do it, free |
| `ö→o`, `ğ→g` | real (`oʻt` grass / `ot` horse) | do it — the apostrophe is omitted constantly, recall outweighs it |
| `ş→s` | real (`bosh` head / `bos` press) | **don't** — `sh` is a digraph, never mistyped, so folding buys no recall |

Measured on 42,869 distinct stems:

| Policy | Keys | Colliding keys | Stems merged away |
|---|---:|---:|---:|
| A none | 42,407 | 0 | 0 |
| **B ours** | 41,990 | 412 | **417 (0.97%)** |
| C blanket NFD (also `ş→s`) | 41,759 | 610 | 642 (1.50%) |

Grading beats blanket diacritic stripping by 35% fewer collisions at zero recall
cost. 0.97% is an upper bound on harm — some of those merges are correct
(`şerqozi`/`şerqözi` is one name spelled two ways).

## What was actually taken from the reference repos

No code has been inherited. Every layer is written from scratch; the references
earned their place as design input and as a cross-check.

| Repo | What it contributed | Form |
|---|---|---|
| `alifbo` | the 2026 alphabet mapping; the U+015F vs U+0219 trap; the two-tier search-key idea; its Cyrillic table as a cross-check — which caught a real bug of ours (`ы` unmapped) | design, read not copied |
| `data-lemma-stems-pos` | 42,869 stems as the measurement corpus | test data only, not vendored |
| the Uyghur plugin | `build.gradle` shape, `esplugin` block, group and package conventions | engineering convention only |
| `uzmorphanalyser` | its 124 non-affixed stems became `function_words.txt` | data, MIT |
| the other 11 repos | nothing yet | queued for L4 |

## Layer 1 — Perso-Arabic

Transliterated to the 1995 Latin orthography first, then folded by the Latin
path, so there is one set of rules for `oʻ` and `sh` rather than two that could
drift. The two offset maps compose, so a highlight still lands on the original.

The mapping comes from the Lutfiy project (MIT). Ported faithfully — the Java
output is byte-identical to Lutfiy's own — with one correction on top.

`و` and `ی` are *matres lectionis*: each serves as a consonant and as a vowel.
The source table calls them consonants everywhere, which turns `قوپقوغی` into
`qvpqvgʻi` and `بویوک` into `bvivk`. Position settles the question — after a
consonant they are vowels — and the decision is made against the character
already produced rather than the source letter, because the preceding character
may itself have been one of the two. With that, all six spellings of "its lid"
across four scripts reach one key:

```
qopqogʻi  qopqog'i  qopqogi  qopqoği  қопқоғи  قوپقوغی   ->  qopqogi
```

What it cannot do is supply an unwritten vowel: `دولت` gives `dolt` where the
word is `davlat`, and `کېلهجگی` gives `kelhjgi` for `kelajagi`. Perso-Arabic
listings therefore match each other reliably and the other scripts only when the
skeleton happens to carry its vowels.

## Layer 3 — protection

Two levels, because the failure modes differ. A brand or model code must survive
whole (`FULL`). A Russian loanword must keep its stem but still lose Uzbek
affixes (`STEM_LOCKED`): `telefonlar` has to reduce to `telefon`, while
`zaryadka` must not reduce to `zaryad` — its `-ka` is Russian stem material and
also the Uzbek dative allomorph after a voiceless stop.

Rules fire in descending confidence and short-circuit: digit, non-Uzbek letter,
Russian script, brand, function word, loanword prefix.

### Brands in the lexicon are NOT protected

The rule that matters, and it is the opposite of the obvious one:

> A brand the lexicon already knows needs no protection — Layer 4 finds it as a
> root and leaves it whole. Protecting it instead costs real recall, because in a
> Turkic market brand names are frequently ordinary words.

Measured collisions in the seed list: `uzum` is the largest marketplace in
Uzbekistan *and* the word for grape; `Бош` (Bosch) folds to `bosh` "head";
`Olcha` to `olcha` "cherry"; `Ravon` to `ravon` "fluent". Protecting those would
remove every inflected form of a common noun from search.

So `brand-exemptions.txt` is generated by intersecting `brands.txt` with the
lexicon, and exempted brands fall through to Layer 4. This removed all 24 brand
false positives.

**Invariant:** `brand-exemptions.txt` and Layer 4's root lexicon must be
generated from the same word list. The exemption is a promise that Layer 4 knows
the word; if the two drift, an exempted brand hits affix-stripping and is
mangled.

### False-protection cost

Every entry below is a real Uzbek stem, so any protection is a candidate false
positive:

| Reason | Count | Verdict |
|---|---:|---|
| `NON_UZBEK_LETTER` | 204 | correct — foreign proper nouns (`barcelona`, `chicago`) |
| `FUNCTION_WORD` | 106 | correct — these never inflect |
| `LOANWORD_STEM` | 58 | correct — naturalised loanwords are in an Uzbek dictionary |
| `BRAND` | **0** | eliminated by the exemption rule |
| **total** | 368 / 42,869 (0.86%) | |

Of the loanword hits, only 10 (0.023%) come from the prefix rule extending past
the listed stem — `telefonist`, `monitoring`, `vilkali` — and those are loanword
derivatives themselves, so locking their stems is right. `MIN_PREFIX_STEM = 5`
is set from this measurement, not from intuition.

## Layer 4 — morphology

Analysis is table-first. A 37,762-entry inflection table compiled by a linguist
is consulted before any rule runs; the rules exist to handle what is missing from
it rather than to re-derive what it already states.

### What the table is worth

Treating it as held-out data — bypass lookup, ask the rules, compare against the
gold lemma:

| | forms | |
|---|---:|---|
| rules answer correctly | 23,882 | **63.2%** |
| rules answer wrongly | 1,911 | 5.1% |
| rules cannot answer at all | 11,969 | 31.7% |

Where the rules do fire they are precise — `AFFIX_STRIP` is right 95.8% of the
time — they simply do not fire often enough on verbs. The 11,969 they leave
untouched are deep verb chains such as `ajramagansizlarmi`
(`ajra`+`ma`+`gan`+`siz`+`lar`+`mi`), which the chain-enumerating affix table was
never going to cover.

The `OOV_STRIP` fallback scores 0 of 778 on exact match here, which reads worse
than it is: on a verb form it removes a nominal ending and returns a partial stem,
which still unifies `ajramaganlar` with `ajramagan` even though neither is the
lemma.

Two conventions had to be reconciled first. The source gives verb lemmas as the
`-moq` infinitive and the rules give the bare stem, so a word would land on a
different term depending on which path fired. `-moq` is stripped, but only when
what remains is a verb stem the table itself attests — `barmoq` "finger" and
`beshbarmoq` are nouns that merely end that way.

68% of the source is multi-word (`afzal korar edi`, auxiliary-verb
constructions). A token-level analyzer never sees those as one string, so they
are dropped rather than shipped as entries nothing can look up.

### The rules

A validated suffix stripper rather than a finite-state morphotactic parser, and
the data decided that: the affix inventory it is built from enumerates whole
affix **chains** rather than slots — `-larimizdangina` is a single entry — so
there is no morphotactic grammar to reconstruct. An affix comes off only if what
remains is a real word.

Resources, all generated by `dev.GenerateMorphResources`:

| File | Rows | Source |
|---|---:|---|
| `forms.tsv` | 37,762 | uzbek_morph `uzbek_unified.txt` (MIT), single tokens only |
| `roots.tsv` | 65,727 | UzbekLemmaStems-POS-Dataset (Apache-2.0) + gold-table lemmas |
| `affixes.tsv` | 2,464 | UzMorphAnalyser affixes.csv (MIT), expanded |
| `stem-allomorphs.tsv` | 89 | UzbekInflectionalWords (MIT) |

The affix source writes optional buffer segments as `(i) (a) (t) (s) (n)` and
uses four archiphonemes — `T`=d/t, `G`=g/k/q, `Y`=a/y, `Q`=q/g/k. Generation
expands all of them into concrete surfaces.

### How a cut is chosen

All cuts that validate are collected, then scored. Neither search direction works
alone, which is the whole reason for scoring:

- **longest affix first** answers `xari` for `xaritasi`, because a long tail
  happens to leave a valid root, when the word is `xarita`+`si`;
- **shortest affix first** answers `otal`+`ar` for `otalar`, because `-ar` is an
  affix and `otal` is a root, when the word is `ota`+`lar`.

Stem length is therefore not the deciding property. Productivity of the affix is:
a core inflection outranks an incidental one, and among equals the longer stem
wins.

Analysis also re-enters itself on its own remainder, up to four passes. The affix
table enumerates chains and cannot enumerate all of them — `-larimizdagi` is
listed, `-larimizdagilar` is not — so one pass would answer
`kitoblarimizdagilar` with the unvalidated remainder `kitoblarimizdagi`, which is
not a word at all.

### Decomposing entries that are already in the lexicon

The source word list carries inflected shapes as entries of their own, so the
plain root check fires too early on exactly the words that most need analysis.
Measured over the 57,807 roots, 813 (1.41%) end in a core affix and decompose to
another root — and the two halves of that number behave completely differently:

| Family | Example | Verdict |
|---|---|---|
| `lar` plurals | `beglar`→`beg`, `betlar`→`bet`, `antibiotiklar`→`antibiotik` | almost all genuine; decompose |
| case endings | `sirka` (vinegar) is not `sir`+`ka`; likewise `tikka`, `anonimka`, `metodika` | almost all false; leave alone |

So plurals are stripped off known roots and case endings are not. Total
over-decomposition of the root lexicon after this: 185 of 57,807 (0.32%), of
which 161 are the intended plural unifications.

### Three decisions worth recording

**Shortest affix first, not longest.** Longest-match is the usual rule for a bare
stemmer and the wrong one once the remainder must be a real word: it discards
material a smaller cut would keep. `xaritasi` has a tail that happens to leave the
valid root `xari`, so longest-first answers `xari` when the word is `xarita`+`si`.
Long affixes are still found — nothing shorter validates for `kitoblarimizda`, so
it walks up to `-larimizda` and returns `kitob`.

**Part-of-speech agreement.** Shortest-first alone then mis-cuts `ayrildi` as
`ayri`+`ldi`, and `ayri` really is a word — but `-ldi` is a verb ending and
`ayri` is an adjective. Both tables carry POS, so the analyzer rejects an affix
that disagrees with its stem. An unknown POS on either side is never treated as
a disagreement.

**A direct lexicon hit beats the allomorph table.** The table lists the bound
shapes of stems that lose a vowel under inflection, but some of those shapes are
ordinary words in their own right: `qiz` is the bound form of `qizil` "red" and
also the everyday word for "girl". Consulting the table first turns `qizlar`
"girls" into `qizil` — not a worse lemma but a different word. The table only
gets a say when the remainder is not a word on its own, which is where it earns
its keep: `tilag` is not a word, so `tilagim` reaches `tilak`.

### Extracting part of speech

The source ships one CSV per part of speech, but column 1 is the *stem a family
is built from* and column 2 lists the lemmas of that part of speech. Tagging
column 1 by filename is wrong: `Verbs.csv` lists `abad` as the stem behind
`abadiylash`, and `abad` is not a verb. Tagging by column 2 instead moved
`telefon`, `xarita` and `qopqoq` out of VERB, and grew the lexicon from 41,984 to
57,807 entries.

## Known limitations

- **Perso-Arabic (`uzs`) is transliterated, not deciphered.** Needs vowel restoration
  from an unvocalised abjad plus presentation-form folding. Flagged in the
  result's `ambiguities` so nothing downstream mistakes it for normalized text.
- **Offsets assume NFC input.** `srcIndex` is measured after NFC; NFC is
  length-preserving for pre-composed Latin and Cyrillic, but decomposed input
  would shift offsets. Put an `icu_normalizer` upstream if that matters.
- **`sʼh` needs the tutuq belgisi.** `Isʼhoq` correctly yields `ishoq`, but a
  user who omits the tutuq gets `işoq` and will not match. Undecidable without
  a lexicon; L4's business.
- **Latin Uzbek vs Latin brand names is undecidable at L0.** 87.2% of real stems
  carry no orthographic marker. L0 reports `LATN_UNDETERMINED` rather than
  guessing; deciding is L3's job (protected-term list) and L4's (lexicon lookup).
- **Derivational morphology is out of scope.** The affix inventory is
  inflectional, so `oʻqi` → `oʻqituvchi` is not related by the analyzer. Many
  derived forms happen to be listed as lemmas in their own right, which covers a
  good part of it; the rest falls to the timid OOV fallback.
- **Genuine morphological ambiguity is resolved by rule, not by context.** A
  suffix stripper has no sentence to look at. Where several analyses validate and
  agree on part of speech, a core inflection wins, then the longer stem. That is
  defensible and consistent, not always linguistically right.
- **Inflected forms in the source lexicon still cost some unification.** Only the
  plural family is decomposed off known roots, so `shahar` and `shahri` remain
  separate terms — both are listed as lemmas, and stripping `-i` from every known
  root would break far more words than it would join. A curated lemma list would
  fix this properly; the affix rules cannot.
- **Brand exemptions and the root lexicon must be regenerated together.** A brand
  is exempted from protection on the promise that Layer 4 knows it as a root. If
  the two drift apart, an exempted brand falls through to affix stripping.

## Findings from real data

The 46k-stem academic lexicon used for measurement contains the apostrophe in
**four different codepoints**, and the correct one (U+02BB) **zero times**:

| Codepoint | Name | Count |
|---|---|---:|
| U+0027 | APOSTROPHE | 5,661 |
| U+2018 | LEFT SINGLE QUOTATION MARK | 894 |
| U+0060 | GRAVE ACCENT | 126 |
| U+2019 | RIGHT SINGLE QUOTATION MARK | 27 |

It also contains 18 core verb stems with a stray Cyrillic `е` (U+0435) among
Latin letters — `kеl` "come", `sеv` "love", `tеp` "kick", `kеs` "cut". These
normalize fine but classify as `MIXED`, so morphology would be skipped and they
would never match a clean query. `Homoglyphs` repairs 17 of 18; the last,
`sеp`, consists entirely of shared-shape letters and resolves only from
surrounding context.

If a hand-curated lexicon is this inconsistent, catalogue text is worse.

## Bugs found and fixed while measuring

1. **Positive Latin heuristics misclassified brands.** `x` and `ng` were used as
   Uzbek evidence; "Galaxy" has `x`, "Samsung" has `ng`. Replaced with a sound
   negative test (`w` / standalone `c` are not Uzbek letters) and an honest
   `LATN_UNDETERMINED` for the 87.2% that carry no marker.
2. **The homoglyph table listed uppercase lookalikes as lowercase pairs.**
   `к м т н в` look like `K M T H B` in capitals but nothing like `k m t h b`
   lowercase. Repair runs after case folding, so those entries were wrong — and
   harmful: they made words like `chek` consist entirely of "homoglyphs",
   destroying the evidence needed to pick a repair direction.
3. **Cyrillic `ы` was unmapped, leaking into the search key.** `новый` folded to
   `novыy` — a hybrid token no Latin query can produce. Russian product names are
   everywhere in an Uzbek catalogue, so this was silent, permanent data loss.
   Found by cross-checking our hand-written table against `alifbo`'s. Fixed, and
   a leak guard now records any surviving Cyrillic codepoint as an
   `cyrillic.unmapped` ambiguity so the same class of gap cannot be silent again.
