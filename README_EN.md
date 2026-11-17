# Med-Insurance Settlement Impact Analysis

## 1. What & Who

A small desktop tool for hospital medical-insurance offices and IT departments. When a data-extraction view is changed, it answers one question: which settlement-list fields are affected, and why? It parses view SQL into column-level edges, walks the downstream field-level lineage graph, aligns the result with the 193-item settlement-list dictionary, and produces a reviewable impact list plus a Chinese vendor note. The model is optional and only drafts text; every write-back requires a human confirmation. No patient-identifiable data is stored.

Typical users: medical-insurance data specialists before monthly settlement, interface engineers on view-change day, supervisors and internal auditors reviewing past changes, and vendor engineers who need a traceable explanation.

## 2. Core Capabilities

- **Change one column, see the blast radius**: downstream hop-by-hop traversal to affected settlement-list fields, separating confirmed impact from suspected reach.
- **Two scopes, kept apart**: strict and conservative reachability are shown side by side; filter-only changes are reported separately because they change the cohort, not the value expression.
- **Never guess when SQL is unclear**: `SELECT *`, mismatched UNION branches, unresolvable subqueries and unsupported dialects become manual-review items with line and column positions.
- **Deterministic core**: Oracle-dialect parsing, four edge kinds (DIRECT / DERIVED / AGGREGATE / FILTER), topological order, cycle detection, strict and conservative BFS, shortest-path evidence chains.
- **Explainable output**: a Chinese vendor note with change point, affected fields, per-row reasoning, scope and hop count; an impact CSV; a two-version diff replay.
- **Human gate and audit**: `CONFIRMED` can only be reached by `Actor.HUMAN`; idempotency keys deduplicate write-backs; an append-only audit chain, failed-event log and restart-safe local store keep batches traceable.
- **Graceful degradation**: unreachable database keeps the previous graph and marks it stale; unreachable model falls back to name matching, labels attribution as missing, and forbids write-back; write-back failures retry at most twice with backoff, then export a change order.
- **Desktop-resident**: three-pane workbench plus system tray, periodic view-digest rescan, and a full CLI (`doctor`, `parse-view`, `analyze`, `export`, `diff`, `version`) for headless operation.

## 3. Quick Start

Requirements: JDK 17+, Maven 3.9+ (build only). Runtime needs no server and opens no inbound port.

```bash
cd med-insurance-settlement-impact
mvn -B clean package                       # build + 264 JUnit5 tests + shaded single jar
java -jar target/med-insurance-settlement-impact.jar          # open the desktop workbench
java -jar target/med-insurance-settlement-impact.jar doctor   # environment self-check
```

Copy `.env.example` to `.env` and export its nine variables before connecting to real systems; `doctor` reports only variable names, never values. On a headless machine the window is skipped and every subcommand stays usable.

```bash
java -jar target/med-insurance-settlement-impact.jar parse-view \
  --sql src/main/resources/samples/views/v_bill_fee.sql --out /tmp/edges.json
java -jar target/med-insurance-settlement-impact.jar analyze \
  --view V_BILL_FEE --sql src/main/resources/samples/views/v_bill_fee.sql \
  --field FEE_AMT --out /tmp/impact.csv --note /tmp/vendor-note.md
```

Full command and parameter reference: `docs/cli.md`. Real terminal output and export samples: `docs/examples.md`. State machine, graph algorithms and audit chain: `docs/architecture.md`. Real runtime screenshots: `docs/screenshots/`.

## 4. Repository Layout

```
med-insurance-settlement-impact/
├── README.md                 Chinese product documentation (authoritative)
├── README_EN.md              This English summary
├── pom.xml                   Java 17 + Maven, shaded single jar
├── .env.example              Nine environment variable placeholders
├── .gitignore                Excludes .env / target / data / logs / caches
├── qrcode.jpg                Project QR code
├── docs/                     CLI, examples, architecture, design tokens, benchmarks, screenshots
├── scripts/                  Reproducible sample-data generation (offline fallback)
├── src/main/java/.../        config, model, sqlparse, lineage, catalog, attribution,
│                             adapter, store, service, report, ui, App
├── src/main/resources/       12 desensitised view SQL files + settlement field dictionary
└── src/test/                 264 JUnit5 tests: golden, edge cases, protocol, headless UI
```

Full documentation: see README.md (Chinese)

---

## Follow Us

Scan the QR code to follow our official account for updates and community access:

![Follow Us](qrcode.jpg)