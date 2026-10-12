#!/usr/bin/env bash
# Render the Velero chart into the plain YAML the plugin applies. Run again after a chart or image change.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/plugin/src/main/resources/velero/velero.yaml"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
helm pull velero --repo https://vmware-tanzu.github.io/helm-charts --version 12.2.1 -d "$TMP"
helm template velero "$TMP"/velero-12.2.1.tgz -n velero --include-crds --skip-tests -f "$ROOT/tools/velero-values.yaml" > "$OUT"
echo "wrote $OUT ($(grep -c '^kind:' "$OUT") objects)"
