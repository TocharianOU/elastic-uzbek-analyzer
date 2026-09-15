#!/usr/bin/env bash
# Install the freshly built plugin into the demo node and restart it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIP=$(ls "$ROOT"/build/distributions/*.zip | head -1)
echo "installing $(basename "$ZIP")"
docker cp "$ZIP" uz-es:/tmp/plugin.zip
docker exec uz-es bin/elasticsearch-plugin remove uzbek-analyzer-plugin 2>/dev/null || true
docker exec uz-es bin/elasticsearch-plugin install --batch file:///tmp/plugin.zip
docker restart uz-es >/dev/null
# Wait for a real status, not merely a reply. A node that answers /_cluster/health
# while still recovering will reject the very next request with
# "state not recovered / initialized".
until curl -fs 'localhost:9200/_cluster/health?wait_for_status=yellow&timeout=5s' >/dev/null 2>&1; do
  sleep 2
done
curl -s 'localhost:9200/_cat/plugins?v'
