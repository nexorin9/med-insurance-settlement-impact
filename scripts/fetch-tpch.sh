#!/usr/bin/env bash
# fetch-tpch.sh -- Generate the 8 TPC-H base tables with the dss (dbgen) generator.
#
# Contract (Task 2 / sample-data owner):
#   * scale factor >= 1 (default 1, override with TPCH_SF or the first argument)
#   * output: data/tpch/<table>.tbl for the 8 TPC-H tables
#   * deterministic + idempotent: same parameters -> reuse, print SKIP; changed -> rebuild
#   * offline degrade: when the generator is unavailable (no network / no compiler),
#     copy the committed minimal seed set from src/test/resources/seed, print the
#     Chinese "offline degrade" hint + the real data scale, and still exit 0
#   * pure ASCII source: the Chinese hint is emitted as UTF-8 octal escapes by printf
#     (bash 3.2 printf has no \xHH support, so octal \nnn is used)
#   * no patient-identifiable data; synthetic TPC-H-shaped rows only
set -u

TABLES=(customer lineitem nation orders part partsupp region supplier)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$ROOT/data/tpch"
SEED_DIR="$ROOT/src/test/resources/seed"
MARKER="$OUT_DIR/.params"

# ---- scale factor: accept env or arg, enforce integer >= 1 ----
SF="${TPCH_SF:-${1:-1}}"
case "$SF" in
  ''|*[!0-9]*) SF=1 ;;
esac
if [ "$SF" -lt 1 ]; then SF=1; fi

PARAM="sf=${SF};tables=8"

mkdir -p "$OUT_DIR"

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

print_scale() {
  echo "DATA SCALE: tables=8 sf=$SF"
  local t n
  for t in "${TABLES[@]}"; do
    n=0
    if [ -f "$OUT_DIR/$t.tbl" ]; then n="$(wc -l < "$OUT_DIR/$t.tbl" | tr -d ' ')"; fi
    echo "  $t.tbl: $n lines"
  done
}

# Print the Chinese phrase for "offline degrade" as UTF-8 octal escapes
# (bytes E7 A6 BB E7 BA BF E9 99 8D E7 BA A7). Keeping it escaped preserves
# the pure-ASCII requirement for this script.
emit_offline_hint() {
  printf 'HINT: '
  printf '\347\246\273\347\272\277\351\231\215\347\272\247'
  printf '\n'
}

# Last-resort offline degrade: copy the committed minimal seed set.
degrade_to_seed() {
  local t
  for t in "${TABLES[@]}"; do
    if [ -s "$SEED_DIR/$t.tbl" ]; then
      cp -f "$SEED_DIR/$t.tbl" "$OUT_DIR/$t.tbl"
    else
      printf '# minimal fallback seed for %s (committed seed unavailable)\n' "$t" > "$OUT_DIR/$t.tbl"
    fi
  done
  echo "OFFLINE DEGRADE: dss generator unavailable, using committed minimal seed set."
  emit_offline_hint
  echo "Data scale after degrade:"
  print_scale
}

# Try to obtain a dss/dbgen binary and generate 8 .tbl files. Return 0 on success.
generate_with_dss() {
  local dbgen="" tmpdl="" built="" work="" t mfd

  # 1. locate a prebuilt generator (env override, PATH, or tools/)
  if [ -n "${TPCH_DBGEN:-}" ] && [ -x "$TPCH_DBGEN" ]; then
    dbgen="$TPCH_DBGEN"
  elif command -v dbgen >/dev/null 2>&1; then
    dbgen="$(command -v dbgen)"
  elif [ -x "$ROOT/tools/dbgen" ]; then
    dbgen="$ROOT/tools/dbgen"
  fi

  # 2. otherwise try to download + build the TPC-H dss generator source
  if [ -z "$dbgen" ] && [ -n "${TPCH_TOOLKIT_URL:-}" ] \
     && command -v curl >/dev/null 2>&1 \
     && command -v tar >/dev/null 2>&1 \
     && command -v make >/dev/null 2>&1; then
    tmpdl="$(mktemp -d)" || tmpdl=""
    if [ -n "$tmpdl" ] \
       && curl -fsSL --connect-timeout 5 --max-time 30 "$TPCH_TOOLKIT_URL" -o "$tmpdl/tpch.tar.gz" >/dev/null 2>&1 \
       && ( cd "$tmpdl" && tar xzf tpch.tar.gz ) >/dev/null 2>&1; then
      for mfd in "$tmpdl"/Makefile "$tmpdl"/*/Makefile; do
        if [ -f "$mfd" ]; then
          ( cd "$(dirname "$mfd")" && make ) >/dev/null 2>&1 || true
        fi
      done
      built="$(find "$tmpdl" -type f -name dbgen 2>/dev/null | head -n 1)"
      if [ -n "$built" ] && [ -x "$built" ]; then dbgen="$built"; fi
    fi
  fi

  if [ -z "$dbgen" ] || [ ! -x "$dbgen" ]; then
    [ -n "$tmpdl" ] && rm -rf "$tmpdl"
    return 1
  fi

  # 3. run dss (dbgen -s SF -f) in a work dir, then copy the 8 .tbl files
  work="$(mktemp -d)" || work=""
  if [ -z "$work" ]; then
    [ -n "$tmpdl" ] && rm -rf "$tmpdl"
    return 1
  fi
  if ! ( cd "$work" && "$dbgen" -s "$SF" -f ) >/dev/null 2>&1; then
    rm -rf "$work"
    [ -n "$tmpdl" ] && rm -rf "$tmpdl"
    return 1
  fi
  for t in "${TABLES[@]}"; do
    if [ ! -s "$work/$t.tbl" ]; then
      rm -rf "$work"
      [ -n "$tmpdl" ] && rm -rf "$tmpdl"
      return 1
    fi
    cp -f "$work/$t.tbl" "$OUT_DIR/$t.tbl"
  done
  rm -rf "$work"
  [ -n "$tmpdl" ] && rm -rf "$tmpdl"
  return 0
}

main() {
  if up_to_date; then
    echo "SKIP: parameters unchanged ($PARAM), reusing existing data in $OUT_DIR"
    print_scale
    exit 0
  fi

  echo "REBUILD: parameters changed or data missing ($PARAM)."
  local t
  for t in "${TABLES[@]}"; do rm -f "$OUT_DIR/$t.tbl"; done

  local source
  if generate_with_dss; then
    source="dss"
    echo "Generated 8 TPC-H tables via dss (dbgen -s $SF -f)."
  else
    source="offline-seed"
    degrade_to_seed
  fi

  printf '%s\n' "$PARAM" > "$MARKER"
  echo "source=$source"
  print_scale
  exit 0
}

main "$@"
