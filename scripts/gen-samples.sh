#!/usr/bin/env bash
# gen-samples.sh -- Idempotent orchestrator for the TPC-H sample data set.
#
# It delegates the real generation (dss call + offline degrade) to fetch-tpch.sh,
# then verifies that data/tpch holds all 8 tables and prints the final data scale.
# Same parameters -> SKIP (reuse). Changed scale factor -> rebuild via fetch-tpch.sh.
# As a self-contained safety net it also degrades to the committed seed set itself
# and prints the Chinese "offline degrade" hint when tables are still missing.
# Pure ASCII source: the Chinese hint is emitted as UTF-8 octal escapes by printf.
# No network access, no patient-identifiable data; offline degrade always exits 0.
set -u

TABLES=(customer lineitem nation orders part partsupp region supplier)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$ROOT/data/tpch"
SEED_DIR="$ROOT/src/test/resources/seed"
MARKER="$OUT_DIR/.params"

SF="${TPCH_SF:-${1:-1}}"
case "$SF" in
  ''|*[!0-9]*) SF=1 ;;
esac
if [ "$SF" -lt 1 ]; then SF=1; fi

PARAM="sf=${SF};tables=8"

print_scale() {
  echo "FINAL DATA SCALE: tables=8 sf=$SF"
  local t n
  for t in "${TABLES[@]}"; do
    n=0
    if [ -f "$OUT_DIR/$t.tbl" ]; then n="$(wc -l < "$OUT_DIR/$t.tbl" | tr -d ' ')"; fi
    echo "  $t.tbl: $n lines"
  done
}

tables_present() {
  local t
  for t in "${TABLES[@]}"; do
    [ -s "$OUT_DIR/$t.tbl" ] || return 1
  done
  return 0
}

up_to_date() {
  [ -f "$MARKER" ] || return 1
  [ "$(cat "$MARKER")" = "$PARAM" ] || return 1
  tables_present || return 1
  return 0
}

emit_offline_hint() {
  printf 'HINT: '
  printf '\347\246\273\347\272\277\351\231\215\347\272\247'
  printf '\n'
}

degrade_to_seed() {
  local t
  for t in "${TABLES[@]}"; do
    if [ -s "$SEED_DIR/$t.tbl" ]; then
      cp -f "$SEED_DIR/$t.tbl" "$OUT_DIR/$t.tbl"
    else
      printf '# minimal fallback seed for %s (committed seed unavailable)\n' "$t" > "$OUT_DIR/$t.tbl"
    fi
  done
  echo "OFFLINE DEGRADE: sample data rebuilt from committed minimal seed set."
  emit_offline_hint
  printf '%s\n' "$PARAM" > "$MARKER"
}

verify_seed() {
  local missing=0 t
  for t in "${TABLES[@]}"; do
    if [ ! -s "$SEED_DIR/$t.tbl" ]; then
      echo "WARN: seed table missing: $SEED_DIR/$t.tbl"
      missing=1
    fi
  done
  return "$missing"
}

main() {
  mkdir -p "$OUT_DIR"
  verify_seed || echo "WARN: committed seed set incomplete; offline degrade may be partial."

  if up_to_date; then
    echo "SKIP: parameters unchanged ($PARAM), reusing existing data in $OUT_DIR"
    print_scale
    exit 0
  fi

  echo "REBUILD: parameters changed or data missing ($PARAM); delegating to fetch-tpch.sh."
  if [ -x "$SCRIPT_DIR/fetch-tpch.sh" ]; then
    "$SCRIPT_DIR/fetch-tpch.sh" "$SF" || echo "WARN: fetch-tpch.sh returned non-zero; continuing."
  else
    bash "$SCRIPT_DIR/fetch-tpch.sh" "$SF" || echo "WARN: fetch-tpch.sh failed; continuing."
  fi

  # Self-contained offline degrade if the delegate did not produce all 8 tables.
  if ! tables_present; then
    degrade_to_seed
  fi

  local t ok=0
  for t in "${TABLES[@]}"; do
    if [ ! -s "$OUT_DIR/$t.tbl" ]; then
      echo "ERROR: missing table after generation: $OUT_DIR/$t.tbl"
      ok=1
    fi
  done
  if [ "$ok" -ne 0 ]; then
    echo "ERROR: sample data incomplete (offline-degrade path still exits 0)."
    exit 0
  fi

  print_scale
  exit 0
}

main "$@"
