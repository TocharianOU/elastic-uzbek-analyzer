# Local demo

Ten Uzbek product listings, indexed twice: once the way Elasticsearch handles
them today, and once through the Layer 1 search key. The gap between the two
columns is what this plugin is for.

## Run it

```bash
docker compose -f demo/docker-compose.yml up -d     # ES on :9200, Kibana on :5601
javac -encoding UTF-8 -d build/dev $(find src/main/java src/test/java -name '*.java')
java -cp build/dev:src/main/resources org.tocharian.uzbek.dev.BuildDemoIndex

curl -XPUT localhost:9200/products -H 'Content-Type: application/json' -d @demo/mapping.json
curl -XPOST 'localhost:9200/products/_bulk?refresh=true' \
     -H 'Content-Type: application/x-ndjson' --data-binary @demo/bulk.ndjson

./demo/compare.sh qopqogi
```

512m heap each; measured use is ~970 MB for Elasticsearch and ~650 MB for
Kibana. Security is disabled — a throwaway node on localhost, nothing more.

Tear down with `docker compose -f demo/docker-compose.yml down -v`.

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

Documents 1–4 are the same product described four ways. A shopper typing any
one of those spellings expects all four.

## Results

`./demo/compare.sh <query>`

| query | today | with the key | what it shows |
|---|---:|---:|---|
| `qopqogi` | 1 | **4** | the lazy spelling finds every variant, Cyrillic included |
| `qopqogʻi` | 1 | **4** | so does the correct one |
| `қопқоғи` | 1 | **4** | and the Cyrillic one |
| `chexol` | 0 | **1** | a Latin query reaches a Cyrillic listing |
| `чехол` | 1 | 1 | unchanged, as it should be |
| `shaffof` | 1 | 1 | unchanged: no ambiguity to resolve |
| `kel` | 0 | **1** | finds the listing with the hidden Cyrillic е |

The last row is the one worth dwelling on. Document 8 looks completely ordinary;
its `Kеl` carries a Cyrillic е that no human proofreader will catch. Today that
listing is unreachable by any query a customer would type. Nothing in the
Elasticsearch logs says so.

## What is being demonstrated

Layers 2 and 4 do not exist yet, so the plugin cannot be installed. The Layer 1
key is computed by the same production code path
(`UzNormalizer.normalize(...).searchKey()`) and indexed as its own field, which
is exactly what the char_filter will emit. Column A is a real Elasticsearch
baseline, not a strawman: `standard` is what an Uzbek catalogue gets today.

Query-side normalization is the other half — `compare.sh` runs the query through
the same function before searching. Index and query must agree, or none of this
works.
