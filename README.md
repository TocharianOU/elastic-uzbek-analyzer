# Elasticsearch Uzbek Analyzer

[![Oʻzbekcha](https://img.shields.io/badge/Til-O%CA%BBzbekcha-1eb53a)](README_uz.md)
[![Downloads](https://img.shields.io/github/downloads/TocharianOU/elastic-uzbek-analyzer/total)](https://github.com/TocharianOU/elastic-uzbek-analyzer/releases)
[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue)](LICENSE)

Uzbek text analysis for Elasticsearch. Four writing systems fold to one search
key, and inflected words reduce to their stem, so a shopper who types `qopqogi`
finds the listing spelled `қопқоғи`.

## The problem

Uzbek is written four ways at once, and a product catalogue contains all of them:

| Orthography | "its lid" |
|---|---|
| Latin, 1995 | `qopqogʻi` |
| Latin, 2026 reform | `qopqoği` |
| Cyrillic | `қопқоғи` |
| Perso-Arabic (`uzs`) | `قوپقوغی` |

The Latin apostrophe alone arrives in a dozen spellings, or not at all —
`qopqogʻi`, `qopqog'i`, `qopqog’i`, `` qopqog`i ``, `qopqogi`. Every one is the
same word.

Elasticsearch ships 34 language analyzers and Turkish is the only Turkic one, so
today each spelling finds only itself. In a lexicon of 46,000 Uzbek stems the
apostrophe appears in **four different codepoints and the correct one not once**.

## Install

One line. Pick the one matching your Elasticsearch major version, then restart
the node.

```bash
# Elasticsearch 8.x
bin/elasticsearch-plugin install https://github.com/TocharianOU/elastic-uzbek-analyzer/releases/latest/download/uzbek-analyzer-plugin-es8.zip
```

```bash
# Elasticsearch 9.x
bin/elasticsearch-plugin install https://github.com/TocharianOU/elastic-uzbek-analyzer/releases/latest/download/uzbek-analyzer-plugin-es9.zip
```

Those URLs always resolve to the newest release, so they stay correct and there
is no version number to keep up to date. Checksums are published beside each
asset; specific versions are on the
[Releases](https://github.com/TocharianOU/elastic-uzbek-analyzer/releases) page.

Docker needs the same command inside the image:

```bash
docker exec -it <container> bin/elasticsearch-plugin install --batch \
  https://github.com/TocharianOU/elastic-uzbek-analyzer/releases/latest/download/uzbek-analyzer-plugin-es8.zip
docker restart <container>
```

Check it took:

```bash
curl localhost:9200/_cat/plugins?v
```

<details>
<summary>Building from source instead</summary>

```bash
# Elasticsearch 8.x — Java 17, the committed Gradle wrapper
./gradlew assemble

# Elasticsearch 9.x — Java 21 or later, which needs Gradle 8.x. The wrapper is
# 7.6.1 and cannot run on Java 21 ("Unsupported class file major version").
gradle assemble -PesMajor=9 -PelasticsearchVersion=9.4.0 -PluceneVersion=10.4.0

bin/elasticsearch-plugin install file://$PWD/build/distributions/uzbek-analyzer-plugin-0.1.0-es8.zip
```

</details>

Built against 8.7.0 and 9.4.0. CI also installs the 8.x artifact on 8.19.15,
since the stable plugin API is meant to carry across a major.

## Use

```
GET _analyze
{ "analyzer": "uzbek", "text": "Телефон учун қопқоғи" }
-> telefon  ucun  qopqoq

GET _analyze
{ "analyzer": "uzbek", "text": "Telefon uchun qopqogʻi" }
-> telefon  ucun  qopqoq
```

| Name | Kind | What it does |
|---|---|---|
| `uzbek` | analyzer | the whole chain, root only |
| `uzbek_split` | analyzer | the whole chain, root plus affixes |
| `uzbek_normalize` | char filter | fold any orthography to the internal form |
| `uzbek_tokenizer` | tokenizer | apostrophes are letters, model codes stay whole |
| `uzbek_morph` | token filter | protect, then reduce to the root |
| `uzbek_morph_split` | token filter | protect, then emit root and affixes |

### Recommended mapping

Three fields, because over-stemming a product name costs more than missing a
morphological variant. Recall comes from the analyzed field, precision from the
other two.

```json
{
  "settings": { "analysis": { "analyzer": {
    "uz_lemma": { "type": "custom", "char_filter": ["uzbek_normalize"],
                  "tokenizer": "uzbek_tokenizer", "filter": ["uzbek_morph"] },
    "uz_exact": { "type": "custom", "char_filter": ["uzbek_normalize"],
                  "tokenizer": "uzbek_tokenizer" }
  } } },
  "mappings": { "properties": { "title": {
    "type": "text", "analyzer": "uz_lemma",
    "fields": {
      "exact": { "type": "text",    "analyzer": "uz_exact" },
      "raw":   { "type": "keyword", "ignore_above": 256 }
    }
  } } }
}
```

Query all three and let the exact ones outrank:

```json
{ "query": { "bool": { "should": [
  { "match": { "title":       { "query": "qopqogi" } } },
  { "match": { "title.exact": { "query": "qopqogi", "boost": 3 } } },
  { "term":  { "title.raw":   { "value": "qopqogi", "boost": 10 } } }
] } } }
```

The analyzer has to run at query time too. Cross-script matching works because
both sides are folded by the same function, not because either side is special.

## How it works

```
L0  identify   which orthography; Uzbek Cyrillic or Russian
L1  normalize  fold to one internal form, keep an offset map
L2  tokenize   apostrophes are letters; model codes stay whole
L3  protect    mark what morphology must not touch
L4  morphology look the form up; else strip a validated affix
L5  recipe     the three-field mapping above
```

The internal form is the Latin alphabet approved in September 2026 — `ö ğ ş ç`
for `oʻ gʻ sh ch` — which gives one codepoint per phoneme, so later layers use
plain character logic instead of tracking digraph boundaries.

Every layer is a pure function with its own tests, so each was measured before
the one above it existed. [docs/layer-status.md](docs/layer-status.md) has the
numbers, the reasoning behind each decision, and what was tried and rejected.

## Measured

45 tests. The corpora differ by what is being measured, so each is named.

| | |
|---|---|
| Normalization | ~400k tokens/sec, single-threaded |
| Cross-script fold | every spelling of a word reaches one key |
| Fold collision cost | 0.97% of 42,869 stems, against 1.50% for blanket diacritic stripping |
| Layer 3 false protection | 368 of 42,869 stems (0.86%), all of them intended |
| Layer 4, rules alone | 63.2% against the 37,762-form attested table, held out |
| Layer 4 over-decomposition | 292 of 65,727 roots (0.44%) |
| Tables in heap | ~15.6 MB, loaded once and shared |

## Try it

[demo/](demo/) brings up Elasticsearch and Kibana with eleven listings written
every which way, and runs the same query against the plugin and against the
`standard` analyzer in one index.

```
=== query: qopqogi

  standard analyzer  -- what a catalogue gets today
    [3] Telefon uchun qopqogi himoya plyonkasi bilan
    -> 1 hit(s)

  uzbek analyzer     -- this plugin
    [1] Samsung Galaxy A54 uchun silikon qopqogʻi qora
    [2] Xiaomi Redmi Note 12 qopqog'i shaffof
    [3] Telefon uchun qopqogi himoya plyonkasi bilan
    [4] Телефон учун қопқоғи кўк рангли
    [11] تېلېفون اوچون قوپقوغی
    -> 5 hit(s)
```

## What this does not do yet

Stated here rather than left to be discovered.

- **The brand and loanword lists are seeds**, compiled from general knowledge of
  the Uzbek market rather than from a catalogue. They are the first thing to
  replace with real listing data.
- **Nothing has been measured on real catalogue text.** Every number above comes
  from lexicons. They say the analyzer is linguistically sound; they do not say
  how it behaves on product titles, which differ in brand density, code-switching
  and typo rate.
- **Perso-Arabic is transliterated, not deciphered.** The script is an abjad and
  short vowels are often unwritten, so `قوپقوغی` reaches the same key as its Latin
  and Cyrillic spellings but `دولت` yields `dolt` where the word is `davlat`.
  These listings match each other reliably and the other scripts only where the
  consonant skeleton carries its vowels.
- **Derivational morphology is out of scope.** The affix inventory is
  inflectional, so `oʻqi` and `oʻqituvchi` are not related by rule. Many derived
  forms happen to be listed as lemmas, which covers part of it.
- **Some inflected forms are lemmas in the source data**, so `shahar` and `shahri`
  remain separate terms. Fixing that needs a curated lemma list, not better rules.

## Word lists

`src/main/resources/uz_lex/` and `uz_morph/`. Anything marked generated is
rebuilt by the tools in `org.tocharian.uzbek.dev` and should not be hand-edited —
`brand-exemptions.txt` in particular must be regenerated together with
`roots.tsv`, since a brand is exempted from protection on the promise that
Layer 4 knows it as a root.

## Contributing

The two most useful things:

1. **Real product titles.** A few thousand listing titles is all it takes to
   tune the brand and loanword lists properly, which is the largest single gap.
2. **Fixing the Uzbek README.** [README_uz.md](README_uz.md) has not been
   reviewed by a native speaker. If something reads wrong or stilted, an issue
   or a pull request is very welcome.

## Changelog

[CHANGELOG.md](CHANGELOG.md).

## Licence

Apache 2.0. [NOTICE.txt](NOTICE.txt) lists the linguistic resources this is
built from and their licences.
