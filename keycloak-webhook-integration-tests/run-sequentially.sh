#!/usr/bin/env bash
# Runs every integration test class on its own, one after another: each gets its own Gradle run, test
# JVM and containers, all gone before the next class starts. That keeps memory use low on a laptop, and
# whatever finished is kept even if the run is stopped halfway.
#
#   keycloak-webhook-integration-tests/run-sequentially.sh                     # every class, full settings
#   keycloak-webhook-integration-tests/run-sequentially.sh -Pevents=2000       # extra arguments go to every run
#   ONLY=Cluster keycloak-webhook-integration-tests/run-sequentially.sh         # only classes matching a regex
#
# Results: build/integration-test-runs/<timestamp>/ with summary.txt, one log per class, and the XML reports.
set -u

module_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(dirname "$module_dir")"
cd "$repo_dir"

run_dir="build/integration-test-runs/$(date '+%Y%m%d-%H%M%S')"
mkdir -p "$run_dir/logs" "$run_dir/reports"
summary="$run_dir/summary.txt"

# Every concrete test class in the module, found in the sources so new ones are picked up by themselves.
classes=$(grep -rhoE '^class [A-Za-z0-9]+Test\b' "$module_dir/src/test/kotlin" | awk '{print $2}' | sort | grep -E "${ONLY:-.}")
package="com.vymalo.keycloak.webhook.it"

echo "Integration tests, one class at a time; results in $run_dir" | tee "$summary"
failed=0
n=0
for cls in $classes; do
  n=$((n + 1))
  log="$run_dir/logs/$(printf '%02d' "$n")-$cls.log"
  started=$(date +%s)
  ./gradlew :keycloak-webhook-integration-tests:integrationTest --tests "$package.$cls" --console=plain "$@" > "$log" 2>&1
  code=$?
  report="$module_dir/build/test-results/integrationTest/TEST-$package.$cls.xml"
  counts="no report"
  if [ -f "$report" ]; then
    cp "$report" "$run_dir/reports/"
    counts=$(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$report" | head -1)
  fi
  [ "$code" -ne 0 ] && failed=$((failed + 1))
  printf '%-36s %-6s %5ss  %s\n' "$cls" "$([ "$code" -eq 0 ] && echo ok || echo FAILED)" "$(( $(date +%s) - started ))" "$counts" | tee -a "$summary"
done

echo "$n classes, $failed failed" | tee -a "$summary"
[ "$failed" -eq 0 ]
