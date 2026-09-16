# Changelog

## 0.1.1 — 2026-09-16

### Fixed

- Words spelled with `ch` are stemmed again. The token filter read the script
  off the search key, where `ç` has become `c`, and locked every such word as
  foreign: `chiroqlar`, `choynaklar`, `muzlatgichlar` never reduced to their
  root. 7,345 roots of the lexicon were affected.
- Russian words are recognised inside the plugin. `ы` and `щ` now survive the
  char filter as `ı` and `ŝ`, so `щетка` is no longer cut by the Uzbek dative
  rule. Index terms are unchanged.
- A minority script inside a field is folded. Script is decided per run, so a
  Cyrillic word in a Latin title, or a Perso-Arabic word next to a Latin brand,
  reaches the same terms as everywhere else.
- In a Cyrillic title, a Latin word with an apostrophe no longer has its last
  letter turned into Cyrillic `і`.
- A model-code run such as `Max/256GB` also emits its word, `max`, at the same
  position.

### Reindexing

Titles containing `ch`, mixed scripts or joined model codes produce different
terms. Reindex those fields to pick up the fix; other documents are unaffected.

## 0.1.0 — 2026-09-16

First release. Cross-script Uzbek analysis for Elasticsearch: Latin 1995, the
2026 Latin reform, Cyrillic and Perso-Arabic fold to one search key, and
inflected words reduce to their stem.

### Components

`uzbek` and `uzbek_split` analyzers; `uzbek_normalize` char filter;
`uzbek_tokenizer`; `uzbek_morph` and `uzbek_morph_split` token filters.

### Built for

Elasticsearch 8.x (built against 8.7.0, Java 17) and 9.x (against 9.4.0,
Java 21). CI also installs the 8.x artifact on 8.19.15.

### What it does

- Folds the four orthographies to the 2026 Latin alphabet, one codepoint per
  phoneme, keeping an offset map so highlighting survives the length changes.
- Repairs Cyrillic/Latin homoglyphs, which otherwise leave a listing that no
  query can reach and nothing reports.
- Keeps apostrophes inside their word and model codes whole.
- Marks brands, model codes, Russian text and loanword stems as off limits to
  morphology, using the lexicon to decide which brands actually need it.
- Reduces inflected forms by lookup against a 37,762-entry attested table, then
  by validated affix stripping.

### Install

Version-less artifacts are published alongside the versioned ones, so
`releases/latest/download/uzbek-analyzer-plugin-es8.zip` stays valid across
releases and the README has no version number to keep up to date.

### Known limits

The brand and loanword lists are seeds compiled from general knowledge rather
than from a catalogue. Nothing has been measured on real catalogue text.
Perso-Arabic is transliterated rather than deciphered, so unwritten short vowels
are guessed. Derivational morphology is out of scope. See the README for the
full list.
