# Local demo

Eleven Uzbek product listings, written every which way, indexed through the
plugin. Each is there to break something.

## Run it

```bash
docker compose -f demo/docker-compose.yml up -d   # ES :9200, Kibana :5601
./gradlew assemble
demo/install-plugin.sh                            # install and restart the node
demo/load.sh                                      # create the index, load the documents
demo/compare.sh qopqogi
```

512m heap each; measured use is about 1.0 GB for Elasticsearch and 650 MB for
Kibana. Security is disabled — a throwaway node on localhost, nothing more. Tear
down with `docker compose -f demo/docker-compose.yml down -v`.

Elasticsearch data lives in a named volume. Without one, any change to the
container settings recreates it and wipes the data directory, taking the
`.kibana*` system indices with it. Kibana does not notice — it still believes its
migration ran — and serves `500 Internal Server Error` with
`Saved object [space/default] not found` buried in the logs. If that happens,
`docker restart uz-kibana` and re-run `demo/load.sh`.

## The documents

| # | title | why it is here |
|---|---|---|
| 1 | Samsung Galaxy A54 uchun silikon qopqog**ʻ**i qora | Latin 1995, correct U+02BB |
| 2 | Xiaomi Redmi Note 12 qopqog**'**i shaffof | ASCII apostrophe |
| 3 | Telefon uchun qopqogi himoya plyonkasi bilan | apostrophe omitted |
| 4 | Телефон учун қопқоғи кўк рангли | Cyrillic |
| 5 | Чехол для телефона Samsung силиконовый | Russian, must not be stemmed as Uzbek |
| 6 | Noutbuk uchun sumka Lenovo IdeaPad Slim 15 | brand absent from the lexicon, plus a model number |
| 7 | Apple iPhone 15 Pro Max 256GB qulay narxda | brand present in the lexicon, plus model codes |
| 8 | K**е**l va koʻring: Artel muzlatgich yangi | a Cyrillic е hiding inside a Latin word |
| 9 | Oʻzbekiston shaharlari xaritasi kitobi | oʻ in an ordinary Uzbek word |
| 10 | Zaryadkalar va kabellar toʻplami Baseus | inflected Russian loanword |
| 11 | تېلېفون اوچون قوپقوغی | Perso-Arabic |

Documents 1, 2, 3, 4 and 11 are one product described in four scripts. A shopper
typing any of those spellings expects all of them.

## What the queries show

`demo/compare.sh <query>` runs the query against two fields of the same index:
`title.standard`, which is what an Uzbek catalogue gets from Elasticsearch today,
and `title`, which goes through this plugin.

| query | standard | plugin | |
|---|---:|---:|---|
| `qopqogi` | 1 | **5** | the lazy spelling finds every script |
| `qopqogʻi` | 1 | **5** | so does the correct one |
| `қопқоғи` | 1 | **5** | and the Cyrillic one |
| `قوپقوغی` | 1 | **5** | and the Perso-Arabic one |
| `kitoblar` | 0 | **1** | morphology: a plural query finds `kitobi` |
| `telefonlar` | 0 | **3** | reaches Latin, Cyrillic and Perso-Arabic |
| `chexol` | 0 | **1** | a Latin query reaches a Cyrillic listing |
| `kel` | 0 | **1** | finds the listing with the hidden Cyrillic е |

Document 8 is the one worth dwelling on. It looks completely ordinary; its `Kеl`
carries a Cyrillic `е` that no proofreader will catch. Without the plugin that
listing is unreachable by any query a customer would type, and nothing in the
Elasticsearch logs says so.

Document 11 shows the limit rather than the win. `تېلېفون` reaches `telefon` and
`قوپقوغی` reaches `qopqoq`, so the listing is findable — but `اوچون` comes out as
`ucon` where the Latin is `uchun`, because the short vowel is not written and no
table can recover it. Perso-Arabic listings match each other reliably and the
other scripts only where the consonant skeleton happens to carry its vowels.

`demo/search.sh <query>` is the other half: it queries all three fields of the
recommended mapping with descending boost, so an exactly-spelled match outranks a
merely-normalized one.

A fuller set with expected results is in [queries.md](queries.md).

## Files

| | |
|---|---|
| `docker-compose.yml` | ES + Kibana, 512m heap each |
| `products.tsv` | the listings and why each is there |
| `bulk-plain.ndjson` | bulk body — raw titles, the plugin does the work |
| `index-recipe.json` | the three-field mapping, plus a `standard` field to compare against |
| `install-plugin.sh` | install the built zip into the running node |
| `load.sh` | create the index and load the documents |
| `compare.sh` | one query, both analyzers, same index |
| `search.sh` | the recommended three-field query |
| `queries.md` | queries to try, with the counts each should return |

Regenerate `bulk-plain.ndjson` after editing `products.tsv`:

```bash
java -cp build/dev:src/main/resources org.tocharian.uzbek.dev.BuildDemoIndex
```
