# Queries to try

Expected results are for the eleven documents in `demo/products.tsv`. If a count
differs, something is wrong — that is the point of listing them.

Bring the stack up first:

```bash
docker compose -f demo/docker-compose.yml up -d
./gradlew assemble && demo/install-plugin.sh && demo/load.sh
```

Kibana Dev Tools: <http://localhost:5601/app/dev_tools#/console>

---

## 1. One product, four scripts

Every spelling of "its lid" should return the same five documents.

```
GET products/_search
{ "query": { "match": { "title": "qopqogi" } }, "_source": ["title"] }
```

| query | expect |
|---|---|
| `qopqogʻi` — correct U+02BB | 5 hits: 1, 2, 3, 4, 11 |
| `qopqog'i` — ASCII apostrophe | same 5 |
| `qopqogi` — no apostrophe at all | same 5 |
| `qopqoği` — 2026 reform letter | same 5 |
| `қопқоғи` — Cyrillic | same 5 |
| `قوپقوغی` — Perso-Arabic | same 5 |

Compare against what a catalogue gets today — one query, both analyzers, same
index:

```bash
demo/compare.sh qopqogi        # standard: 1 hit,  plugin: 5 hits
demo/compare.sh қопқоғи
```

## 2. Morphology

An inflected query should find the uninflected listing and the reverse.

```
GET products/_search
{ "query": { "match": { "title": "kitoblar" } }, "_source": ["title"] }
```

| query | expect | why |
|---|---|---|
| `kitob` / `kitoblar` / `kitobni` | 1 hit: 9 | doc 9 says `kitobi` |
| `shahar` / `shaharlar` | 1 hit: 9 | doc 9 says `shaharlari` |
| `telefon` / `telefonlar` | 3 hits: 3, 4, 11 | Latin, Cyrillic and Perso-Arabic |
| `zaryadka` / `zaryadkalar` | 1 hit: 10 | doc 10 says `Zaryadkalar` |
| `kabel` | 1 hit: 10 | doc 10 says `kabellar` |

`zaryadka` is the one to watch. Its `-ka` is Russian stem material and also the
Uzbek dative allomorph, so a naive stemmer would cut it to `zaryad` and stop
matching. It must not.

## 3. The invisible defect

```
GET products/_search
{ "query": { "match": { "title": "kel" } }, "_source": ["title"] }
```

Expect 1 hit: document 8. Its first word is `Kеl` with a **Cyrillic е** among
Latin letters — invisible to a proofreader. `demo/compare.sh kel` shows the
`standard` analyzer returning nothing at all. Today such a listing is unreachable
by any query a customer would type, and nothing in the logs says so.

## 4. Russian text, and what must not be touched

| query | expect | why |
|---|---|---|
| `chexol` | 1 hit: 5 | a Latin query reaching a Cyrillic Russian listing |
| `чехол` | 1 hit: 5 | the same listing from its own script |
| `Samsung` | 2 hits: 1, 5 | the brand in both a Latin and a Cyrillic title |
| `Lenovo` | 1 hit: 6 | brand absent from the lexicon, protected by list |
| `Artel` | 1 hit: 8 | brand present in the lexicon, left to Layer 4 |

## 5. Look inside

`_analyze` is the fastest way to see what the plugin did.

```
GET _analyze
{ "analyzer": "uzbek", "text": "Telefon uchun qopqogʻi" }
```

| input | output |
|---|---|
| `Telefon uchun qopqogʻi` | `telefon ucun qopqoq` |
| `Телефон учун қопқоғи` | `telefon ucun qopqoq` |
| `تېلېفون اوچون قوپقوغی` | `telefon ucon qopqoq` |
| `kitoblarimizdagilar` | `kitob` |
| `SM-G998B 128GB iPhone 15` | `sm-g998b 128gb iphone 15` |
| `Kеl va koʻring` | `kel va kor` |

The first two lines are the whole plugin in one place: two alphabets, identical
terms. The third shows the limit — `اوچون` comes out as `ucon` where the Latin is
`uchun`, because the short vowel is not written and no table can recover it.

The model-code line shows Layer 3 working: nothing was stemmed, split or folded.

The split view keeps affixes searchable, at the root's position:

```
GET _analyze
{ "analyzer": "uzbek_split", "text": "kitoblarimizdagilar" }
-> kitob  larimizdagi  lar
```

## 6. Ranking

`demo/search.sh` runs the recommended three-field query, so an exactly-spelled
match outranks a merely-normalized one.

```bash
demo/search.sh qopqogi
```

```
  6.84  [3]  Telefon uchun qopqogi ...      <- spelled exactly as queried
  0.96  [11] تېلېفون اوچون قوپقوغی
  0.82  [4]  Телефон учун қопқоғи ...
  0.76  [2]  Xiaomi Redmi Note 12 qopqog'i ...
  0.71  [1]  Samsung Galaxy A54 ... qopqogʻi ...
```

Recall comes from `title`, precision from `title.exact` and `title.raw`.

## 7. Your own text

The demo index is eleven documents, so it can only show what it was built to
show. To try the analyzer on anything else, skip the index entirely:

```
GET _analyze
{ "analyzer": "uzbek", "text": "<paste any Uzbek text>" }
```

Worth pasting: a real product title, a Cyrillic paragraph, a title mixing Russian
and Uzbek, anything with brand names in it. Tokens that come back unchanged when
you expected a stem are the interesting ones — those are the gaps the word lists
and lexicon do not cover yet.
