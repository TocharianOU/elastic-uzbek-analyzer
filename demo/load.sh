#!/usr/bin/env bash
# Create the demo index with the three-field recipe and load the ten listings.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
curl -fs 'localhost:9200/_cluster/health?wait_for_status=yellow&timeout=30s' >/dev/null
curl -s -XDELETE localhost:9200/products >/dev/null || true
curl -s -XPUT localhost:9200/products -H 'Content-Type: application/json' \
     --data-binary "@$ROOT/demo/index-recipe.json" >/dev/null
curl -s -XPOST 'localhost:9200/products/_bulk?refresh=true' \
     -H 'Content-Type: application/x-ndjson' \
     --data-binary "@$ROOT/demo/bulk-plain.ndjson" \
| python3 -c "import sys,json;d=json.load(sys.stdin);print('indexed',len(d['items']),'docs, errors:',d['errors'])"
