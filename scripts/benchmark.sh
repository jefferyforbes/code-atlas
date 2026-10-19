#!/usr/bin/env bash
# ==============================================================================
# Atlas Performance & Cold-Start Benchmark Suite
#
# Measures latency across:
#   1. Cold-start binary invocation (--version)
#   2. Symbol lookup (action: symbol)
#   3. Two-tier caller traversal (action: callers, depth: 2)
#   4. Blast-radius impact analysis (action: impact, depth: 2)
#   5. Complexity ratchet audit (audit --complexity)
#   6. Full Clean Architecture & BOOST-003 audit (audit)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PROJECT="${1:-code-atlas}"
ITERATIONS="${ITERATIONS:-5}"

# Resolve atlas executable
if [[ -n "${ATLAS_BIN:-}" && -x "$ATLAS_BIN" ]]; then
    BINARY="$ATLAS_BIN"
elif [[ -x "$REPO_ROOT/build/bin/macosArm64/releaseExecutable/atlas.kexe" ]]; then
    BINARY="$REPO_ROOT/build/bin/macosArm64/releaseExecutable/atlas.kexe"
elif [[ -x "$HOME/.local/bin/atlas" ]]; then
    BINARY="$HOME/.local/bin/atlas"
elif command -v atlas >/dev/null 2>&1; then
    BINARY="$(command -v atlas)"
else
    echo "Atlas executable not found. Building release binary..."
    (cd "$REPO_ROOT" && ./gradlew linkReleaseExecutableMacosArm64 --offline)
    BINARY="$REPO_ROOT/build/bin/macosArm64/releaseExecutable/atlas.kexe"
fi

echo "================================================================================"
echo " Atlas Benchmark Suite"
echo "================================================================================"
echo " Binary:     $BINARY"
echo " Project:    $PROJECT"
echo " Iterations: $ITERATIONS (with 1 warmup run per test)"
echo " OS:         $(uname -sm)"
echo " Date:       $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "================================================================================"
echo ""

# High-precision timer using Python 3 perf_counter without process startup overhead in measurement
time_cmd_ms() {
    python3 -c '
import sys, time, subprocess
t0 = time.perf_counter()
subprocess.run(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print(int((time.perf_counter() - t0) * 1000))
' "$@"
}

# Run a benchmark case
# Usage: run_bench "Label" command args...
run_bench() {
    local label="$1"
    shift
    local cmd=("$@")

    # Warmup
    "${cmd[@]}" >/dev/null 2>&1 || true

    local times=()
    local total=0
    local min=999999
    local max=0

    for ((i=1; i<=ITERATIONS; i++)); do
        local elapsed
        elapsed=$(time_cmd_ms "${cmd[@]}")

        times+=("$elapsed")
        total=$((total + elapsed))
        if (( elapsed < min )); then min=$elapsed; fi
        if (( elapsed > max )); then max=$elapsed; fi
    done

    local avg=$((total / ITERATIONS))
    printf "%-38s | Avg: %4d ms | Min: %4d ms | Max: %4d ms\n" "$label" "$avg" "$min" "$max"
}

printf "%-38s | %-12s | %-12s | %-12s\n" "Benchmark Scenario" "Average" "Min" "Max"
echo "---------------------------------------+--------------+--------------+--------------"

run_bench "1. Cold-Start CLI (--version)" \
    "$BINARY" --version

run_bench "2. Polyglot Symbol Query" \
    "$BINARY" query --action symbol --target "AtlasMcpServer" --project "$PROJECT" --workspace "$REPO_ROOT"

run_bench "3. Callers Traversal (depth=2)" \
    "$BINARY" query --action callers --target "PlatformFiles" --depth 2 --project "$PROJECT" --workspace "$REPO_ROOT"

run_bench "4. Blast-Radius Impact (depth=2)" \
    "$BINARY" query --action impact --target "SqliteGraphStore" --depth 2 --project "$PROJECT" --workspace "$REPO_ROOT"

run_bench "5. Complexity Ratchet Audit" \
    "$BINARY" audit --complexity --project "$PROJECT" --workspace "$REPO_ROOT"

run_bench "6. Architecture & BOOST-003 Audit" \
    "$BINARY" audit --project "$PROJECT" --workspace "$REPO_ROOT"

echo "---------------------------------------+--------------+--------------+--------------"
echo "Benchmark completed successfully."
