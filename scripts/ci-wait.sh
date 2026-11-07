#!/usr/bin/env bash
# Wait for the Android CI job of a commit and report the result.
#   scripts/ci-wait.sh            # current HEAD
#   scripts/ci-wait.sh <sha>      # a specific commit
# Prints the run, the job status every 30s, then on success the build release APK link,
# on failure the Kotlin compiler errors / failed tests from the job log.
# Auth: $GITHUB_TOKEN / $GH_TOKEN if set (needed for this private repo outside a session whose
# proxy already authenticates api.github.com).
set -euo pipefail

REPO="KyongSik-Yoon/openWispr"
API="https://api.github.com/repos/$REPO"
SHA="${1:-$(git rev-parse HEAD)}"
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
AUTH=()
[ -n "$TOKEN" ] && AUTH=(-H "Authorization: Bearer $TOKEN")

api() { curl -sS -m 30 "${AUTH[@]}" -H "Accept: application/vnd.github+json" "$API/$1"; }
py() { python3 -c "$1"; }

echo "Waiting for CI on ${SHA:0:7}…"
RUN=""
for _ in $(seq 1 40); do
  RUN=$(api "actions/runs?head_sha=$SHA&per_page=20" | py '
import sys, json
runs = [r for r in json.load(sys.stdin).get("workflow_runs", []) if r["name"] == "CI"]
print("%s %s" % (runs[0]["id"], runs[0]["run_number"]) if runs else "")')
  [ -n "$RUN" ] && break
  sleep 10
done
[ -z "$RUN" ] && { echo "No CI run found for $SHA"; exit 1; }
RUN_ID=${RUN%% *}; RUN_NO=${RUN##* }
echo "Run #$RUN_NO  https://github.com/$REPO/actions/runs/$RUN_ID"

while true; do
  read -r STATUS CONCL JOB_ID < <(api "actions/runs/$RUN_ID/jobs" | py '
import sys, json
j = [x for x in json.load(sys.stdin).get("jobs", []) if x["name"].startswith("Android")]
print("%s %s %s" % (j[0]["status"], j[0]["conclusion"], j[0]["id"]) if j else "queued None 0")')
  echo "$(date +%T) android: $STATUS/$CONCL"
  [ "$STATUS" = "completed" ] && break
  sleep 30
done

if [ "$CONCL" = "success" ]; then
  api "releases/tags/build-$RUN_NO" | py '
import sys, json
r = json.load(sys.stdin)
for a in r.get("assets", []): print("APK:", a["browser_download_url"])' || true
  echo "Phone: open OpenWispr → Update (or Settings → Updates → Check)."
  exit 0
fi

echo "---- failure excerpt ----"
curl -sSL -m 60 "${AUTH[@]}" "$API/actions/jobs/$JOB_ID/logs" \
  | sed 's/^[0-9TZ:.-]* //' \
  | grep -aE "^e: |error:|FAILED|Test.*>.*FAILED|AssertionError|Expected|What went wrong" \
  | head -60
exit 1
