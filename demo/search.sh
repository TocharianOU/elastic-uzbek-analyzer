#!/usr/bin/env bash
# Search the demo index the way a shop should: recall from the analyzed field,
# precision from the exact one, with the raw term ranked highest of all.
set -euo pipefail
Q="${1:?usage: search.sh <query>}"
curl -s localhost:9200/products/_search -H 'Content-Type: application/json' -d "{
  \"size\": 10,
  \"query\": { \"bool\": { \"should\": [
    { \"match\":      { \"title\":       { \"query\": \"$Q\" } } },
    { \"match\":      { \"title.exact\": { \"query\": \"$Q\", \"boost\": 3 } } },
    { \"term\":       { \"title.raw\":   { \"value\": \"$Q\", \"boost\": 10 } } }
  ] } },
  \"_source\": [\"title\"]
}" | python3 -c "
import sys,json
h=json.load(sys.stdin)['hits']['hits']
print(f'  query: $Q  ->  {len(h)} hit(s)')
for d in h: print(f\"    {d['_score']:5.2f}  [{d['_id']}] {d['_source']['title']}\")
"
