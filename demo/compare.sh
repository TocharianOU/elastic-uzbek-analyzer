#!/usr/bin/env bash
# The same query against the same documents, analyzed two ways.
#
# title.standard is what an Uzbek catalogue gets from Elasticsearch today;
# title goes through this plugin. Both fields are in the one index, so this is a
# live comparison rather than a remembered one.
set -euo pipefail
Q="${1:?usage: compare.sh <query>}"

hits() {
  curl -s localhost:9200/products/_search -H 'Content-Type: application/json' \
    -d "{\"query\":{\"match\":{\"$1\":{\"query\":\"$Q\"}}},\"size\":10,\"_source\":[\"title\"]}" \
  | python3 -c "
import sys,json
h=json.load(sys.stdin)['hits']['hits']
if not h: print('    none')
else:
    for d in sorted(h,key=lambda x:int(x['_id'])): print(f\"    [{d['_id']}] {d['_source']['title']}\")
print(f'    -> {len(h)} hit(s)')"
}

printf '\n=== query: %s\n' "$Q"
printf '\n  standard analyzer  -- what a catalogue gets today\n'
hits "title.standard"
printf '\n  uzbek analyzer     -- this plugin\n'
hits "title"
