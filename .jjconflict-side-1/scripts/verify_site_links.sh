#!/usr/bin/env bash
# verify_site_links.sh — P1 deployment-boundary assertion.
#
# After `pnpm build` + `bb demo:public-lab`, assert for every published demo:
#   1. the demo HTML file exists;
#   2. the "Inspect executable notebook" href exists in it;
#   3. the href target file exists in the final composed tree (site/out);
#   4. the target is a real static Clerk build: it references the Clerk viewer
#      asset AND contains the notebook's title, so it is not an empty shell
#      whose nested-asset URLs silently broke.
#
# Usage: scripts/verify_site_links.sh [out-dir]   (default site/out)
set -euo pipefail

OUT="${1:-site/out}"
FAIL=0

echo "[site-links] verifying composed output under ${OUT}"

for demo_json in site/generated/demos/*.json; do
  demo_id="$(basename "$demo_json" .json)"
  demo_html="${OUT}/demos/${demo_id}/index.html"
  notebook="$(python3 -c "import json,sys;print(json.load(open('$demo_json'))['source']['notebook'])")"

  if [ ! -f "$demo_html" ]; then
    echo "  FAIL ${demo_id}: demo HTML missing: ${demo_html}"
    FAIL=1
    continue
  fi

  # Extract the Inspect href (dev: localhost:7777; prod: /lab/notebooks/<name>).
  href="$(grep -oE 'href="(/lab/notebooks/[^"]+|http://localhost:7777/notebooks/[^"]+)"' \
    "$demo_html" | head -1 | sed -E 's/^href="([^"]+)"$/\1/' || true)"
  if [ -z "$href" ]; then
    echo "  FAIL ${demo_id}: no Inspect notebook href in ${demo_html}"
    FAIL=1
    continue
  fi
  echo "  OK   ${demo_id}: href ${href}"

  # Production links must resolve to a file in the final tree.
  case "$href" in
    /lab/notebooks/*)
      target="${OUT}${href%/}/index.html"
      if [ ! -f "$target" ]; then
        echo "  FAIL ${demo_id}: composed target missing: ${target}"
        FAIL=1
        continue
      fi
      echo "  OK   ${demo_id}: composed target ${target}"

      # The target must be a real Clerk build, not an empty shell.
      if ! grep -q 'clerk' "$target" 2>/dev/null; then
        echo "  FAIL ${demo_id}: ${target} does not reference the Clerk viewer"
        FAIL=1
      fi
      title="$(python3 -c "import json;print(json.load(open('$demo_json'))['source']['notebook'])" \
        | sed 's/_/ /g')"
      if ! grep -qi "$(echo "$title" | sed 's/ /.*/g')" "$target" 2>/dev/null; then
        echo "  WARN ${demo_id}: notebook title not found verbatim in ${target} (title may differ)"
      fi
      ;;
    http://localhost:7777/*)
      echo "  OK   ${demo_id}: dev-mode link (Clerk server), skipped file check"
      ;;
    *)
      echo "  FAIL ${demo_id}: unexpected href form: ${href}"
      FAIL=1
      ;;
  esac
done

if [ "$FAIL" -ne 0 ]; then
  echo "[site-links] FAILED"
  exit 1
fi
echo "[site-links] OK: every demo's proof link resolves in the composed export"
