# Local demo

Ten Uzbek product listings, written every which way, indexed through the real
plugin.

## Run it

```bash
docker compose -f demo/docker-compose.yml up -d   # ES :9200, Kibana :5601
./gradlew assemble
demo/install-plugin.sh                            # install and restart the node
demo/load.sh                                      # create the index, load 10 docs
demo/search.sh qopqogi
```

512m heap each; measured use is about 1.0 GB for Elasticsearch and 650 MB for
Kibana. Security is disabled — a throwaway node on localhost, nothing more.
Tear down with `docker compose -f demo/docker-compose.yml down -v`.

Elasticsearch data lives in a named volume. Without one, any change to the
container settings recreates it and wipes the data directory, taking the
`.kibana*` system indices with it. Kibana does not notice — it still believes its
migration ran — and serves `500 Internal Server Error` with
`Saved object [space/default] not found` buried in the logs. If that happens,
`docker restart uz-kibana` and re-run `demo/load.sh`.

## The ten documents

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

Documents 1–4 are one product described four ways. A shopper typing any of those
spellings expects all four.

## What the queries show

`demo/search.sh <query>`

| query | hits | |
|---|---:|---|
| `qopqogi` | 4 | the lazy spelling finds every variant, Cyrillic included |
| `qopqogʻi` | 4 | so does the correct one |
| `қопқоғи` | 4 | and the Cyrillic one |
| `kitoblar` | 1 | morphology: the plural query finds `kitobi` |
| `telefonlar` | 2 | reaches both the Latin and the Cyrillic listing |
| `chexol` | 1 | a Latin query reaches a Cyrillic listing |
| `kel` | 1 | finds the listing with the hidden Cyrillic е |

Document 8 is the one worth dwelling on. It looks completely ordinary; its `Kеl`
carries a Cyrillic `е` that no proofreader will catch. Without the plugin that
listing is unreachable by any query a customer would type, and nothing in the
Elasticsearch logs says so.

Ranking comes from the three-field recipe in `index-recipe.json`: recall from the
analyzed field, precision from `title.exact`, and an exact-term boost on
`title.raw`. An exactly-spelled match outranks a merely-normalized one.

## Files

| | |
|---|---|
| `docker-compose.yml` | ES + Kibana, 512m heap each |
| `products.tsv` | the ten listings and why each is there |
| `bulk-plain.ndjson` | bulk body, raw titles — the plugin does the work |
| `index-recipe.json` | the three-field mapping |
| `install-plugin.sh` | install the built zip into the running node |
| `load.sh` | create the index and load the documents |
| `search.sh` | query all three fields with descending boost |
| `compare.sh` | side-by-side against a `standard`-analyzer baseline |
