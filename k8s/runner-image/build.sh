#!/bin/bash
# Build the Puklic self-hosted runner image.
# Pattern follows jervis/k8s/build_kb.sh (host Docker + docker push).
# Per design doc: docs/03_infrastructure/architect-reports/2026-05-24-k8s-runner-design.md §3.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

REGISTRY="registry.damek-soft.eu/jandamek"
DATE=$(date +%Y%m%d)
SHA=$(git -C "${REPO_ROOT}" rev-parse --short HEAD)
TAG="${REGISTRY}/puklic-runner:${DATE}-${SHA}"
LATEST="${REGISTRY}/puklic-runner:latest"

echo "=== Building puklic-runner image ==="
echo "Tag:    ${TAG}"
echo "Latest: ${LATEST}"

# Build
docker build --platform linux/amd64 \
  -t "${TAG}" -t "${LATEST}" \
  -f "${SCRIPT_DIR}/Dockerfile" "${SCRIPT_DIR}"
echo "✓ Image built"

# Scan for HIGH/CRITICAL CVEs (fail build if found)
if command -v trivy >/dev/null 2>&1; then
  echo "=== Trivy scan (HIGH,CRITICAL) ==="
  trivy image --severity HIGH,CRITICAL --exit-code 1 "${TAG}" \
    || { echo "✗ Trivy found HIGH/CRITICAL vulnerabilities — review before pushing"; exit 1; }
  echo "✓ Trivy scan green"
else
  echo "⚠ Trivy not installed; skipping CVE scan. Install via 'brew install trivy' or apt."
fi

# Smoke test — override entrypoint so we don't trigger runner registration
echo "=== Smoke test ==="
docker run --rm --entrypoint /bin/bash "${TAG}" -c \
  "java -version && cmake --version && openssl version && test -x /usr/local/bin/appimagetool && echo 'appimagetool present:' && ls -l /usr/local/bin/appimagetool" \
  || { echo "✗ Smoke test failed"; exit 1; }
echo "✓ Smoke test passed"

# Push both tags
echo "=== Pushing images ==="
docker push "${TAG}"
docker push "${LATEST}"
echo "✓ pushed ${TAG} + :latest"
echo
echo "Use this tag in AutoscalingRunnerSet (arc-runner-set-values.yaml):"
echo "  ${TAG}"
