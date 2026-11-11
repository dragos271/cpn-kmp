# Corpus results: importing real CPN Tools models (T3.1)

Last run: 2026-11-11 (after T2.10), on the 49 models in [corpus/](../corpus/README.md).

To reproduce:

```bash
./gradlew :cpn-io:jvmTest --tests '*CorpusImportTest*'
```

The run writes the full table to `cpn-io/build/reports/corpus-import.md`. CI fails if any model
stops importing, or if fewer than 35 models run.

## Summary

| Outcome | Models | Meaning |
|---|---|---|
| **Imports** | **49 / 49** | The `.cpn` file is read into an editable model: pages, layout, inscriptions, declarations, hierarchy |
| **Runs** | **35 / 49** | It type-checks, compiles and simulates 200 steps (or to a dead marking) without errors |
| Does not run yet | 14 / 49 | A problem is reported by name; the causes are listed below |

"Runs" means the model executes. It does not yet mean the model gives the same results as CPN
Tools; that comparison is T4.2. One model is checked by hand: the textbook's deterministic
protocol, figure 2.1, delivers all six packets and goes dead after 30 steps, as in the book.

Two of the 35 are timed models, and since T3.2 they run with model time: `10-1TimedProtocol` and
`10-19TimedStateSpaces`. For the timed protocol, 20 seeds all deliver the data with a clock that
never goes backwards. The other timed models stop earlier, for the reasons in the table.

## Why 14 models do not run yet

T2.10 (multisets as lists, `mult`, `sort_ms`) made 9 more models run:
- the course's `lecture1`, `lecture3`, `lecture4` and the three `lecture6-statespaces` models;
- `twophasecommit`;
- the book's `5-30MultipleReceivers`;
- `DistributedDataBase`.

| Cause | Models | What CPN Tools does | Where to fix |
|---|---|---|---|
| **SML file I/O and `use`**: `TextIO`, loading `.sml` files (MQTT's functions live in `mqtt.sml`), code segments that write logs | 7: the four NatCPN models, `QueueSystem`, `DisCSP`, `MQTT` | Runs the SML/NJ Basis and loads the files | Out of scope (instrumentation and logging), or offer a stub |
| **Timed patterns** such as `p@t` (timestamps in function arguments) | 3: the three `PerformanceProtocol` models | Supports them | Not supported yet: `@` (absolute timestamps) is not part of T3.2 |
| **Inhibitor and reset arcs** | 2: `lecture2-ptnets`, `singlePaxos` | Supports them | Open question for the supervisor |
| **Equality on `real`** | 1: `fuelstation-cpn-ide` | Its SML/NJ version accepts `=` on reals, which SML'97 does not | Decide whether to allow it |
| **An unused broken declaration**: `var dp : DATAP`, where `DATAP` is never declared | 1: `3-1UnionRecord` | Checks each declaration on its own, so an unused bad one does not block simulation | Per-declaration checking |

## Fixed while running the corpus

Running real models found these gaps in our importer and engine. Each is fixed and has a test.

| Gap | Effect | Fix |
|---|---|---|
| CPN Tools files declare `UNIT`, `INT`, `BOOL`, … themselves | Almost every model failed with "already declared" | The standard colour sets may be declared once more |
| `check()` built colour sets without the evaluator | `colset X = int with 1..n` failed | Declarations run in the evaluator during the check |
| CPN Tools resolves declarations by dependency, not file order | Variables declared before their colour set failed | The importer sorts declarations by dependency |
| Layout text can contain leftovers, such as a stray `v` after a declaration | Parse errors | Only the first declaration in a layout is used (comments kept); a layout that does not start with `colset`/`var` is rebuilt from the XML structure |
| Guards written as `a, b` without brackets | Parse errors | `parseGuard` reads them as a guard list |
| `x::xs` patterns on list tokens | The variables could not be bound | `::` is a pattern |
| Sums of patterns such as ``1`(n1,1)++1`(n2,2)`` | The variables could not be bound | Each summand becomes its own arc |
| An I/O socket connected by one arc each way | Rejected as malformed | Accepted, as in CPN Tools |
| CPN Tools' list library (`mem`, `rm`, `ins`, `^^`, `list_to_ms`, …) | Unbound names | Added (semantics provisional until T4.2) |
| **Multisets are lists** (`'a ms` = `'a list` in CPN Tools) | 9 models | `ms` and `list` are one type; at runtime list functions take multisets and the other way round; at a place, a list is one token if the place holds such lists, and otherwise a multiset of its elements (T2.10) |
| Colour set `mult` and `sort_ms` | Unbound names | Added (T2.10) |
| One mistake in the declarations gave many problems | E.g. 12 problems for one type error in `5-30MultipleReceivers` | A failed declaration's names and colour sets no longer produce follow-on errors (T2.10) |
