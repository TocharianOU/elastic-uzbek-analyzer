# Elasticsearch Uzbek Analyzer Plugin

[![Build](https://github.com/TocharianOU/elastic-uzbek-analyzer/actions/workflows/build.yml/badge.svg)](https://github.com/TocharianOU/elastic-uzbek-analyzer/actions/workflows/build.yml)

Cross-script text analysis for Uzbek. One search key from Latin-1995,
Latin-2026 and Cyrillic input, plus morphology, so that a shopper who types
`qopqogi` finds the listing spelled `қопқоғи`.

## The problem

Uzbek is written several ways at once, and a product catalogue contains all of
them:

| Orthography | "its lid" |
|---|---|
| Latin 1995 | `qopqogʻi` |
| Latin 2026 | `qopqoği` |
| Cyrillic | `қопқоғи` |
| Perso-Arabic (`uzs`) | قوپقوغی |

The Latin apostrophe alone arrives in a dozen spellings, or not at all —
`qopqogʻi` `qopqog'i` `qopqog'i` `` qopqog`i `` `qopqogi`. All the same word.
Elasticsearch ships 34 language analyzers and Turkish is the only Turkic one, so
today each spelling finds only itself.

## Install

```bash
./gradlew assemble                                    # ES 8.x, Java 17
./gradlew assemble -PelasticsearchVersion=9.4.0 \
                   -PluceneVersion=10.4.0 -PesMajor=9  # ES 9.x, Java 21

bin/elasticsearch-plugin install file:///path/to/build/distributions/uzbek-analyzer-plugin-0.1.0-es8.zip
```

Then restart the node.

## Use

The plugin registers one ready-made analyzer and the pieces to build your own.

| Name | Kind | What it does |
|---|---|---|
| `uzbek` | analyzer | the whole chain, root only |
| `uzbek_split` | analyzer | the whole chain, root plus affixes |
| `uzbek_normalize` | char filter | fold any orthography to the internal form |
| `uzbek_tokenizer` | tokenizer | apostrophes are letters, model codes stay whole |
| `uzbek_morph` | token filter | protect, then reduce to the root |
| `uzbek_morph_split` | token filter | protect, then emit root and affixes |

```
GET _analyze
{ "analyzer": "uzbek", "text": "Телефон учун қопқоғи" }
-> telefon  ucun  qopqoq

GET _analyze
{ "analyzer": "uzbek", "text": "Telefon uchun qopqogʻi" }
-> telefon  ucun  qopqoq
```

### Recommended mapping

Three fields, because over-stemming a product name is worse than missing a
morphological variant. Recall comes from the analyzed field, precision from the
others.

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

The analyzer must run at query time too. Cross-script matching works because
both sides are folded by the same function, not because either side is special.

## Architecture

```
L0  identify   which orthography; Uzbek Cyrillic or Russian
L1  normalize  fold to one internal form, keep an offset map
L2  tokenize   apostrophes are letters; model codes stay whole
L3  protect    mark what morphology must not touch
L4  morphology strip a validated affix; restore the root
L5  recipe     the three-field mapping above
```

Every layer is a pure function with its own tests, so each can be measured
before the one above it exists.

## Measured

35 tests, and against 57,807 stems from a curated Uzbek lexicon:

| | |
|---|---|
| Normalization | ~500k tokens/sec, single-threaded |
| Cross-script fold | every spelling of a word collapses to one key |
| Fold collision cost | 0.97%, versus 1.50% for blanket diacritic stripping |
| Layer 3 false protection | ~0 after lexicon-based brand exemption |

See [docs/layer-status.md](docs/layer-status.md) for the numbers, the reasoning
behind each decision, and the known limitations.

## Try it

[demo/](demo/) brings up Elasticsearch and Kibana with ten listings written
every which way.

```bash
docker compose -f demo/docker-compose.yml up -d
./gradlew assemble && demo/install-plugin.sh
demo/load.sh
demo/search.sh qopqogi
```

## Word lists

`src/main/resources/uz_lex/` and `uz_morph/`.

- `brands.txt`, `loanwords.txt` — **seed lists**, compiled from general knowledge
  of the Uzbek market rather than from a real catalogue. Extend them from live
  listing data.
- `function_words.txt` — words that never take an affix.
- `brand-exemptions.txt`, `roots.tsv`, `affixes.tsv`, `stem-allomorphs.tsv` —
  **generated, do not hand-edit.** Regenerate with the tools in
  `org.tocharian.uzbek.dev`.

## Licence

Apache 2.0. [NOTICE.txt](NOTICE.txt) lists the linguistic resources used.
