# Elasticsearch Uzbek Analyzer Plugin

Cross-script text analysis for Uzbek: one search key from Latin-1995,
Latin-2026, Cyrillic and Arabic input.

> **Status: work in progress.** Layers 0, 1 and 3 are implemented and measured.
> Layers 2, 4 and 5 are not written yet, and the plugin does not build as an
> Elasticsearch artifact — the layers currently run as standalone Java.

## The problem

Uzbek is written four ways at once, and a product catalogue contains all of them:

| Orthography | "its lid" |
|---|---|
| Latin 1995 | `qopqogʻi` |
| Latin 2026 | `qopqoği` |
| Cyrillic | `қопқоғи` |
| Arabic (Southern Uzbek) | Afghanistan |

The Latin apostrophe alone arrives in a dozen spellings, or not at all:
`qopqogʻi` `qopqog'i` `qopqog'i` `qopqog\`i` `qopqogi`. Every one of these is the
same word and a shopper expects all of them to match. Elasticsearch ships 34
language analyzers; Turkish is the only Turkic one.

## Architecture

```
L0  identify   which orthography, and is it Uzbek or Russian
L1  normalize  fold everything to one internal form, keep an offset map
L2  tokenize   apostrophes are letters; model codes stay whole
L3  protect    mark what morphology must not touch
L4  morphology root + affix chain, lemma and split views
L5  recipe     three-field index mapping
```

Each layer is a pure function with its own test entry point, so it can be
measured before the layer above exists.

## Measured so far

On 42,869 distinct stems from a curated Uzbek lexicon:

| | |
|---|---|
| Normalization throughput | ~500k stems/sec, single-threaded |
| Cross-script fold | every spelling of a word collapses to one key |
| Fold collision cost | 0.97% (35% better than blanket diacritic stripping) |
| L3 false protection | ~0 after lexicon-based brand exemption |

```
qopqogʻi  qopqog'i  qopqog'i  qopqog`i  qopqogi  qopqoği  қопқоғи  ->  qopqogi
Oʻzbekiston  O'zbekiston  Ozbekiston  Özbekiston  Ўзбекистон       ->  ozbekiston
```

See [docs/layer-status.md](docs/layer-status.md) for the full numbers, the
design decisions behind them, and the known limitations.

## Running the layers

No Elasticsearch or Lucene needed for L0–L3.

```bash
javac -encoding UTF-8 -d build/dev $(find src/main/java src/test/java -name '*.java')

# all layers, or one: l0 | l1 | fold | leaks | homoglyph | offsets | l3
java -cp build/dev:src/main/resources org.tocharian.uzbek.dev.LayerCli

# measurement against a real lexicon (one stem per line)
java -cp build/dev:src/main/resources org.tocharian.uzbek.dev.ScaleTest stems.txt
```

## Word lists

`src/main/resources/uz_lex/` holds the lists Layer 3 consults.

- `brands.txt`, `loanwords.txt` — **seed lists**, compiled from general
  knowledge of the Uzbek market, not from a real catalogue. Extend them from
  live listing data.
- `function_words.txt` — words that never take an affix, from UzMorphAnalyser.
- `brand-exemptions.txt` — **generated, do not hand-edit.** Brands that are also
  real Uzbek dictionary stems, which are deliberately left unprotected: `uzum` is
  a marketplace and the word for grape, `Бош` (Bosch) folds to `bosh` "head",
  `Olcha` to `olcha` "cherry". Protecting those would delete a common noun from
  search. Regenerate with `org.tocharian.uzbek.dev.GenerateBrandExemptions`
  after editing `brands.txt`.

## Licence

Apache 2.0. See [NOTICE.txt](NOTICE.txt) for the linguistic resources used.
