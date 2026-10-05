#!/usr/bin/env bash
# Falla si idp.security.dev-mode puede quedar activo fuera de values-local.yaml (estatico y renderizado).
set -euo pipefail
cd "$(dirname "$0")/../.."
HELM="${HELM:-helm}"
CHART=deploy/helm/idp
fail=0

for f in "$CHART"/values*.yaml; do
  [ "$(basename "$f")" = "values-local.yaml" ] && continue
  if grep -Eiq 'dev[-_.]?mode"?[[:space:]]*[:=][[:space:]]*"?true|IDP_SECURITY_DEV_MODE' "$f"; then
    echo "ERROR dev-mode activo en $f (solo permitido en values-local.yaml)"; fail=1
  fi
done

for f in "$CHART"/values*.yaml; do
  name=$(basename "$f")
  [ "$name" = "values-local.yaml" ] && continue
  if "$HELM" template idp-release "$CHART" -f "$f" | grep -Eq 'IDP_SECURITY_DEV_MODE|security\.dev-mode'; then
    echo "ERROR el render con $name contiene dev-mode"; fail=1
  fi
done

# Salvaguarda del propio check: con values-local debe renderizarse (si no, el chequeo seria ciego).
"$HELM" template idp-release "$CHART" -f "$CHART/values-local.yaml" | grep -q 'IDP_SECURITY_DEV_MODE' \
  || { echo "ERROR values-local ya no renderiza dev-mode: revisar check_dev_mode.sh"; fail=1; }

[ "$fail" -eq 0 ] && echo "OK dev-mode solo en values-local.yaml"
exit "$fail"
