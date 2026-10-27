# Headless CPN Tool — Task Plan

Source: *A Headless, Cross-Platform Coloured Petri Net Tool* (Dragos Slovineanu, supervisor Patrick Stünkel, HVL/UiB, Oct 2026).

Goal: a Kotlin Multiplatform library + CLI that loads, simulates and analyses Coloured Petri Nets
with no GUI, runs existing CPN Tools `.cpn` models, and supports a Python-like inscription syntax
that keeps ML semantics.

"Headless" here means: the engine works with no editor. You drive it from a **CLI** (`cpn simulate`, `cpn statespace`, …),
from a **library API** (JVM / JS / native), and in **CI pipelines**.

A **Kotlin Multiplatform app** (Compose Multiplatform, desktop + web) is also required. It is a front-end
*on top of* the headless engine: it uses the same public API as the CLI, and the engine modules never depend on it
(track A below).

---

## Architecture (target)

```
cpn-app          Compose Multiplatform app: desktop (macOS, Windows, Linux) + web (Wasm)
cpn-cli          Clikt CLI → JVM jar + native binaries (macOS, Linux, Windows)
cpn-api          Public facade: JVM API, @JsExport JS/WASM API, C-ABI native lib
─────────────────────────────────────────────────────────── shared commonMain ──
cpn-io           .cpn (CPN Tools XML) importer, JSON model format, report writers,
                 editable model document (ids, layout, inscription text) → compiled to cpn-core nets
cpn-statespace   Reachability graph, SCC graph, standard state-space report
cpn-sim          Enabling, binding search, firing, random/stepwise sim, timed nets
cpn-lang         Lexers + parsers (CPN ML and Python-like) → shared AST
                 → type checker (Hindley–Milner) → evaluator, values, multisets
cpn-core         Net model: places, transitions, arcs, colour sets, markings
```

Rule: `cpn-core`, `cpn-lang`, `cpn-sim`, `cpn-statespace`, `cpn-io` are 100% `commonMain`, with no
platform code. Platform-specific code lives only in `cpn-api`, `cpn-cli` and `cpn-app`.

---

## Decisions to settle before T1

| # | Decision | Default proposal |
|---|----------|------------------|
| D1 | Kotlin / Gradle versions | Latest stable Kotlin 2.x, Gradle version catalogs, convention plugins in `build-logic/` |
| D2 | Targets | `jvm`, `js(IR)` (node + browser), `wasmJs`, `macosArm64`, `macosX64`, `linuxX64`, `mingwX64` |
| D3 | Timed nets in scope? | Yes, but in I3 (untimed first) |
| D4 | CPN ML subset | The subset used by the CPN Tools example models + textbook (Jensen & Kristensen 2009); grow from the corpus |
| D5 | Python-like syntax: indentation-based or not? | Indentation for declarations; expressions are plain infix. Spec is written in T2.6 |
| D6 | Reference outputs from CPN Tools | Run CPN Tools 4.x on Windows (or Wine) via Access/CPN, export to JSON golden files once; tests compare offline |
| D7 | XML parsing in commonMain | `io.github.pdvrieze.xmlutil` (KMP) or a small hand-written SAX parser |
| D8 | Big integers (SML `IntInf`) | Plain `Long` for `int`; add a KMP bignum lib only if the corpus needs it |
| D9 | App | Compose Multiplatform; desktop (JVM) + web (wasmJs); simulate, inspect **and edit** models |

---

## Iteration I1 — Net core and simulator

The goal is to simulate hand-built nets whose inscriptions are Kotlin lambdas, with no language yet.

- **T1.1 Project skeleton.** Gradle multi-module KMP build with all targets from D2, `kotlin-test`, ktlint/detekt, and a GitHub Actions matrix (ubuntu / macos / windows) running `allTests`.
  *Done:* an empty test passes on JVM, JS and native in CI on all 3 OSes.
- **T1.2 Multiset.** `Multiset<T>` with `+`, `-`, `<=`, scalar `*`, `size`, an iterator, and the CPN `1`a++2`b` text form. Make it immutable and hash-consable.
  *Done:* property-based tests (Kotest property) for the algebra laws.
- **T1.3 Net model.** `Place(name, colourSet, initMarking)`, `Transition(name, guard)`, `Arc(place, transition, direction, expr)`, plus `Page` and `Net`. Hierarchy (substitution transitions, port/socket fusion) is modelled now and flattened in T1.5.
  *Done:* a sample net (producer/consumer) can be built through a small Kotlin DSL.
- **T1.4 Marking and firing.** `Marking` (map place → multiset). `isEnabled(t, binding)` and `fire(t, binding)` → new marking.
  *Done:* unit tests on textbook ch. 2 examples.
- **T1.5 Hierarchy flattening.** Turn substitution transitions and fusion sets into one flat net with qualified names (`Page'Place 1`).
  *Done:* a hierarchical example gives the same behaviour as its hand-flattened version.
- **T1.6 Binding search.** Enumerate the enabled bindings of a transition from arc patterns and the guard. Bind variables from input-arc patterns first, then check the guard. Unbound variables come from small colour sets.
  *Done:* matches the textbook binding examples, including the "unbound variable over a finite colour set" case.
- **T1.7 Simulator.** `step()`, `run(maxSteps)`, random choice with a seedable PRNG (deterministic across platforms!), and stop conditions.
  *Done:* the same seed gives the same trace on JVM, JS and native (cross-platform determinism test).
- **T1.8 Minimal CLI.** `cpn simulate <file.json> --steps N --seed S`, using a temporary JSON net format.
  *Done:* runs as a native binary on macOS and Linux.

## Iteration I2 — SML parser, types, evaluator

The goal is real CPN ML inscriptions, plus a Python-like front-end that shares the same core.

- **T2.1 Shared AST.** Expressions, patterns, declarations (`colset`, `var`, `val`, `fun`, `local`, `exception`), with source positions on every node for diagnostics.
- **T2.2 CPN ML lexer + parser.** A hand-written recursive-descent parser with SML operator precedence and infix declarations.
  *Done:* parses every declaration block in the corpus (T3.4).
- **T2.3 Colour sets.** `unit`, `bool`, `int` (with ranges), `string`, `enum`, `index`, `product`, `record`, `union`, `list`, `subset`, and `alias`. Each has membership, `all()` for finite sets, `size`, an order, and the CPN Tools standard functions (`ord`, `col`, `mkstr`, `ran`, …).
- **T2.4 Type checker.** Hindley–Milner with SML value restriction, equality types and overloading (`+` on int and real). Error messages should point to the inscription.
  *Done:* types all corpus declarations; golden tests for error messages.
  *Status:* done and used by the app's checks; checking the whole corpus waits for T3.5. Simplifications: `int`
  stands for intinf and time (D8), and `@+` is typed `'a * int -> 'a` until timed nets (T3.2).
- **T2.5 Evaluator.** A tree-walking interpreter: closures, pattern matching, exceptions, and the multiset operators (`++`, `--`, `` ` ``). Include the SML Basis subset that models use (`List`, `String`, `Int`, `Real`, `Option`).
  *Done:* the I1 nets are rebuilt with text inscriptions and give identical traces.
- **T2.6 Python-like syntax spec.** Write the language definition (a thesis deliverable): grammar, mapping table SML ↔ new syntax, and what is deliberately not supported.
  *Done:* a written spec doc and a reviewed set of examples (e.g. `type Packet = (No, Data)`).
- **T2.7 Python-like parser.** Parses into the **same AST** as T2.2, so types and the evaluator are reused unchanged.
  *Done:* round-trip tests where the same model in both syntaxes gives an identical AST (modulo positions).
- **T2.8 SML → new-syntax translator.** Pretty-print the AST in the new syntax (used for migration and for the user study).
- **T2.9 Diagnostics.** A unified `Diagnostic(severity, range, message, hint)` type, with CLI output in a rustc-like format.

## Iteration I3 — State space and .cpn import

The goal is to run real CPN Tools models and produce state-space reports.

- **T3.1 .cpn importer.** Parse CPN Tools XML: pages, places, transitions, arcs, inscriptions, the declaration blocks (`globbox`), hierarchy (`subst`, port/socket, fusion), and initial markings.
  *Done:* imports the CPN Tools example models into `cpn-core`.
- **T3.2 Timed nets.** Timed colour sets, `@+` delays, timestamps on tokens, a global clock, and the earliest-time step semantics.
  *Done:* matches the CPN Tools traces for the timed protocol example.
- **T3.3 State space.** BFS/DFS reachability graph with marking hashing and compact storage, plus limits (nodes, time).
- **T3.4 SCC + standard report.** Tarjan SCC, home markings, dead markings, dead/live transitions, integer/multiset bounds, and fairness. Output matches the CPN Tools "Standard Report" sections.
- **T3.5 Model corpus.** Collect `.cpn` models: CPN Tools examples, textbook models and public repos. Record licence and source.
  *Done:* at least 30 models in `corpus/` with a manifest.
- **T3.6 Full CLI.** `cpn check`, `cpn simulate`, `cpn statespace`, `cpn convert --to python-like`, with `--format json|text` output for pipelines and exit codes for CI use.
- **T3.7 Public API.** A stable facade: `CpnModel.load()`, `.simulator(seed)`, `.stateSpace()`. JVM (Java-friendly), `@JsExport` for JS/WASM, and a C header for native. Generate docs with Dokka.

## Iteration I4 — Evaluation and write-up

- **T4.1 Reference harness.** Scripts that run each corpus model in CPN Tools (via Access/CPN) and dump enabled bindings, markings after N fixed-choice steps, and state-space size → golden JSON (D6).
- **T4.2 Differential tests (RQ1, RQ2).** Compare our output against the golden JSON per model: enabled bindings, markings, number of state-space nodes/arcs, and report values.
- **T4.3 Compatibility study (RQ2).** The % of the corpus that runs unchanged. Target ≥ 80%. Categorise the failures.
- **T4.4 Benchmarks (RQ3).** Simulation steps/s and state-space time/memory, on JVM vs native vs JS and against CPN Tools. Use kotlinx-benchmark.
- **T4.5 Cross-platform CI report (RQ1).** The same suite green on JVM, native and JS × Linux, macOS and Windows.
- **T4.6 Syntax user study (RQ2).** Task material in both syntaxes, protocol, measurement of errors and time (needs ethics/consent per HVL rules).
- **T4.7 Write-up.** Architecture lessons, results and threats to validity.


## Track A — Kotlin Multiplatform app (desktop + web)

Runs alongside the iterations. The engine stays headless; the app only uses `cpn-api` / public engine APIs.

- **A1 App skeleton.** `cpn-app` module with Compose Multiplatform: desktop (JVM) and web (wasmJs, browser) from one
  shared UI in `commonMain`. First screen runs the example model through the real engine.
  *Done:* `./gradlew :cpn-app:run` opens a window on macOS; the web build runs in a browser; CI builds both.
- **A2 Model document.** An editable model separate from the executable `Net`: element ids, positions, page
  structure, declarations and inscriptions as text. Saved as JSON; compiled to a `Net` (JSON-expression inscriptions
  now, CPN ML after T2.5). Automatic layout for models without positions. The `.cpn` importer (T3.1) produces it too.
- **A3 Net canvas.** Draw places, transitions, arcs, inscriptions and markings; pan and zoom; select elements;
  one tab per page of a hierarchical net.
- **A4 Simulation UI.** Enabled transitions highlighted; choose a binding or fire at random; step / run with a seed;
  reset; trace panel; stop reasons. Same results as `cpn simulate` for the same seed.
- **A5 Editor.** Create, move and delete places, transitions and arcs; edit names, colour sets, initial markings,
  inscriptions and declarations with live syntax and type errors (T2.2, T2.4, T2.9); undo / redo.
  Executing edited CPN ML inscriptions needs T2.5.
  *Status:* done; syntax, colour-set and type errors are live (T2.2, T2.3, T2.4).
- **A6 Open and save.** Desktop file dialogs; web upload / download. Open `.cpn` files once T3.1 exists.
  *Status:* done for JSON models and the editable document format; `.cpn` waits for T3.1.
- **A7 State-space view.** After T3.4: the standard report and an explorable state graph.
- **A8 Distribution.** Desktop installers (dmg / msi / deb) and the web build published from CI (e.g. GitHub Pages).

---

## Suggested order and dependencies

```
T1.1 → T1.2 → T1.3 → T1.4 → T1.6 → T1.7 → T1.8
                 └→ T1.5 (can run in parallel with T1.6)
T2.1 → T2.2 → T2.3 → T2.4 → T2.5 ─┬→ T2.7 → T2.8
          T2.6 (spec, parallel) ───┘    T2.9 (alongside)
T3.5 (start corpus early, during I1!) → T3.1 → T3.3 → T3.4 → T3.6 → T3.7
                                      └→ T3.2
T4.1 (needs Windows/Wine; start in I2) → T4.2 → T4.3 / T4.4 / T4.5 → T4.7
T4.6 (needs T2.7 + T2.8)
A1 → A2 → A3 → A4            (works on the current engine)
A2 + A3 + T2.4/T2.5 → A5 → A6 → (T3.1) .cpn in the app → (T3.4) A7 → A8
```

Start early, off the critical path: **T3.5 corpus** and **T4.1 reference harness**. Every later iteration
depends on having real models and golden outputs to test against.
