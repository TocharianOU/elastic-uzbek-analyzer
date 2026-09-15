#!/usr/bin/env bash
# Run one query two ways: against the raw field (what Elasticsearch does today)
# and against the Layer 1 search key (what the analyzer will do).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
Q="$1"
KEY=$(java -Dfile.encoding=UTF-8 -cp "$ROOT/build/dev:$ROOT/src/main/resources" \
        org.tocharian.uzbek.dev.Key "$Q")

hits() {  # $1=field $2=query
  curl -s "localhost:9200/products/_search?size=10" -H 'Content-Type: application/json' \
    -d "{\"query\":{\"match\":{\"$1\":{\"query\":\"$2\"}}},\"_source\":[\"title\"]}" \
  | python3 -c "
import sys,json
h=json.load(sys.stdin)['hits']['hits']
print('    none' if not h else '\n'.join(f'    [{d[\"_id\"]}] {d[\"_source\"][\"title\"]}' for d in sorted(h,key=lambda x:int(x['_id']))))
print(f'    -> {len(h)} hit(s)')"
}

printf '\n=== query: %s   (key: %s)\n' "$Q" "$KEY"
printf '\n  A. standard analyzer on the raw title  -- today\n'
hits title "$Q"
printf '\n  B. Layer 1 search key                  -- with the analyzer\n'
hits title_uz "$KEY"
