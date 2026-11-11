# Headless CPN Tool — Task Plan

Source: *A Headless, Cross-Platform Coloured Petri Net Tool* (Dragos Slovineanu, supervisor Patrick Stünkel, HVL/UiB, Oct 2026).

Specs: [vision](docs/vision.md) · [feature spec with definitions of done](docs/feature-spec.md) ·
[comparison with CPN Tools and earlier HVL tools](docs/comparison.md) · [AI process and checks](docs/ai-process.md).

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
  stands for intinf and time (D8), and `@+` is typed `'a * int -> 'a` (its timed result is handled at runtime, T3.2).
- **T2.5 Evaluator.** A tree-walking interpreter: closures, pattern matching, exceptions, and the multiset operators (`++`, `--`, `` ` ``). Include the SML Basis subset that models use (`List`, `String`, `Int`, `Real`, `Option`).
  *Done:* the I1 nets are rebuilt with text inscriptions and give identical traces.
  *Status:* done. Editable documents compile to runnable nets (`CpnDocument.compile`), so the app runs edited
  models and `cpn simulate` runs `cpn-document` files. The JSON example nets rebuilt from their CPN ML text give
  identical traces for several seeds. Colour set ranges and `subset ... by` predicates are evaluated. Arc
  inscriptions that are patterns (``2`(n,d)``, `Data n`) bind variables from tokens. Simplifications: delays
  (`@+`) were ignored and `time()` was 0 until T3.2 (now supported); `int` values are Ints and `intinf`/`time` values Longs (D8),
  converted when tokens reach a place.
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
  *Status:* done (2026-11-10).
  - **Import:** all 49 corpus models import into the editable model; **26 of them run**. The details are in
    [docs/corpus-results.md](docs/corpus-results.md), and the mapping and normalisations in
    [docs/cpn-format.md](docs/cpn-format.md).
  - **Where it's used:** `cpn simulate x.cpn` and the app (desktop and web) open `.cpn` files. What the engine
    ignores (monitors, priorities) is reported as notes. Code segments and inhibitor/reset
    arcs are reported as problems.
  - **XML:** a small reader in common code (D7).
  - **Corpus gaps fixed along the way:** standard colour sets declared again, declarations in dependency order,
    unbracketed guard lists, `x::xs` and summed patterns, I/O sockets with two arcs, CPN Tools' list library.
  - **Biggest remaining gap:** in CPN Tools `'a ms` *is* `'a list` (9 models). That is T2.10.
- **T2.10 CPN Tools language compatibility (from T3.1).** Treat multisets as lists the way CPN Tools does, add
  colour set `mult` and `sort_ms`, and decide on `real` equality and per-declaration checking (an unused broken
  declaration does not block a model in CPN Tools).
  *Done:* the corresponding corpus models in docs/corpus-results.md run.
- **T3.1b .cpn export.** Write documents as CPN Tools 4.x XML, including models made from scratch in the app (F5).
  *Done:* exported files have the CPN Tools structure (automated), and five models made from scratch open and run in
  CPN Tools, recorded in `docs/interop-log.md`.
  *Status:* done as far as our own tests can tell (2026-11-10); **the check in CPN Tools is still to do**, because it
  needs Windows.
  - **Where it's used:** `cpn convert model --to cpn -o out.cpn`, and *Export .cpn…* in the app (desktop and web).
  - **An imported model** is written onto its original XML. Unchanged parts (graphics, monitors, options, binders,
    declarations) pass through, so an unchanged model comes back **byte for byte**; all 49 corpus models do.
  - **A model made in the app** becomes a complete CPN Tools 4.0.1 file.
  - **Models for CPN Tools:** five made from scratch are in `interop/`, with the steps for checking them in
    [docs/interop-log.md](docs/interop-log.md).
  - **Found and fixed along the way:** CPN Tools allows `with a | b | timed` (a trailing bar before `timed`), which our
    parser had read as a constant named `timed`.
- **T3.1c Round-trip stability (important, 2026-11-09 meeting).** `.cpn` → us → `.cpn`, `.cpn` → JSON → `.cpn`
  and app save → open, repeated for 5 cycles (F6).
  *Done:* in CI for the whole corpus and ≥ 500 random nets, nothing is lost after one cycle and the bytes
  are identical from the second export on. Every normalisation is listed in `docs/cpn-format.md`.
  *Status:* done with T3.1b. These tests run in CI:
  - `CorpusRoundTripTest`, on all 49 corpus models:
    - the XML reader and writer reproduce each file exactly;
    - an unchanged import exports to the identical bytes;
    - a generated file imports as the same model;
    - exporting is a fixed point over 5 cycles.
  - `CpnToolsWriterTest`, on 500 random nets (pages, hierarchy, fusion, ports, awkward names): the same checks for
    `.cpn`, plus save → open → save for the app's document format; edits are written and everything else is kept.
  - `.cpn` → document → `.cpn` is covered by combining the generated-file and document-format tests.
- **T3.2 Timed nets.** Timed colour sets, `@+` delays, timestamps on tokens, a global clock, and the earliest-time step semantics.
  Time is an **integer** (2026-11-09 meeting). A token is available only when its timestamp ≤ the clock, and
  delays can be on transitions and on output arcs (F7).
  *Done:* matches the CPN Tools traces for the timed protocol example.
  *Status:* done (2026-11-10). **Comparing with real CPN Tools traces is still to do** (it needs CPN Tools, T4.1).
  - **Core:** `TimedMultiset` (tokens with integer timestamps), timed places, transition delays, and the model time in
    each `Marking`, so the state space (T3.3) sees it.
  - **Semantics** (Jensen & Kristensen ch. 10):
    - a step is enabled only when the timed tokens it consumes are available;
    - produced tokens get the time + the transition's delay + the arc's delay;
    - when nothing is enabled now, the clock moves to the earliest time something is.
  - **CPN ML:** `@+` on arcs and in initial markings (``1`x@+5``), time regions `@+expr` (type-checked as int), and
    `time()`. Delays do nothing in nets without timed colour sets, as in CPN Tools.
  - **Output:** the CLI and the app show the model time, and tokens with timestamps (``1`x@5+++1`y@9``).
  - **Checked by hand against the book:** the textbook timed protocol runs from its `.cpn` file. Send Packet fires at
    0, Transmit Packet at 9, and the retransmission is due at 109. Over 20 seeds the clock never goes backwards and
    all data arrives.
  - **Provisional:** which tokens of one colour are removed when several are available (the latest ones). Check
    against CPN Tools.
- **T3.3 State space.** BFS/DFS reachability graph with marking hashing and compact storage, plus limits (nodes, time).
  *Status:* done (2026-11-11). `StateSpace.build(net, limits)` in `cpn-statespace`, and `cpn statespace model` in the CLI
  (text or JSON output; exit code 2 for a partial state space).
  - **Exploration:** breadth-first, with nodes numbered from 1 as CPN Tools numbers them, and arcs labelled by binding
    element. Limits on nodes, arcs and time give a *partial* state space.
  - **Timed nets:** markings include the model time, and a marking where nothing is enabled now continues at the next
    enabling time, as in CPN Tools.
  - **Checked independently:**
    - dining philosophers: 11 nodes and 30 arcs, counted by hand;
    - the deterministic protocol: a chain of 31 nodes;
    - the limit protocol: **13,215 nodes and 52,784 arcs** with one dead marking where all data is received. These
      are the numbers I recall the textbook printing; check them against the book.
    - Every arc is checked to be a firing of its source, with one arc for each enabled binding element.
  - **Expected partial results:** the protocols without limits and the timed protocols have infinite state spaces,
    as the book explains.
  - **Simplification:** markings are stored as objects, not encoded compactly. The limit protocol takes 0.4 s on the
    JVM; compact storage can wait until the benchmarks (T4.4) show it is needed.
- **T3.4 SCC + standard report.** Tarjan SCC, home markings, dead markings, dead/live transitions, integer/multiset bounds, and fairness. Output matches the CPN Tools "Standard Report" sections.
- **T3.5 Model corpus.** Collect `.cpn` models: CPN Tools examples, textbook models and public repos. Record licence and source.
  *Done:* at least 30 models in `corpus/` with a manifest.
  *Status:* done. 49 distinct models from four public collections are in `corpus/`:
  - the Jensen & Kristensen textbook;
  - Kristensen's CPN Tools course;
  - CPN IDE's samples;
  - HVL's MC/DC case studies (MQTT, Paxos, …).

  Each model's source, commit and SHA-256 is in `corpus/sources.json`; none of the sources declares a licence.
  `scripts/corpus-manifest.py` records the features each model uses. Of the 49, 11 are timed and 20 hierarchical;
  8 use priorities, 6 monitors, and 2 inhibitor/reset arcs. CI checks the checksums, and `.gitattributes` keeps
  the bytes unchanged on Windows.
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
  *Status:* done; syntax, colour-set and type errors are live (T2.2, T2.3, T2.4), and edited models run (T2.5).
- **A6 Open and save.** Desktop file dialogs; web upload / download. Open `.cpn` files once T3.1 exists (done: a `.cpn`
  file is never overwritten by Save, because Save asks where to write until export, T3.1b, exists).
  *Status:* done for JSON models and the editable document format; `.cpn` waits for T3.1.
- **A7 State-space view.** After T3.4: the standard report and an explorable state graph.
- **A8 Distribution.** Desktop installers (dmg / msi / deb) and the web build published from CI (e.g. GitHub Pages).

## Supervisor meeting 2026-11-09

The meeting notes and where each point is handled:

| Point | Where | Status |
|---|---|---|
| Run the earlier tool and compare features (supported / not supported) | [comparison.md](docs/comparison.md) | Matrix done from the theses. Running them needs access: Gjesdal's editor repo is private and Garvik's F# simulator is not public |
| Stable under repeated import/export (important) | T3.1c, F6 | Done: unchanged `.cpn` files come back byte for byte (49/49); 500 random nets and 5-cycle fixed point in CI |
| Import existing `.cpn` files from the web | T3.5 corpus, T3.1 | Done: 49/49 import, 26/49 run ([results](docs/corpus-results.md)) |
| Make `.cpn` files from scratch and run them in another CPN tool | T3.1b, F5 | Export done, 5 models in `interop/`; **opening them in CPN Tools is still to do** ([log](docs/interop-log.md)) |
| Timed nets: token availability, delays, integer time | T3.2, F7 | Done; comparison with CPN Tools traces waits for T4.1 |
| The report at the end of the earlier work (state-space report) | T3.3, T3.4, F8 | State space done (T3.3); the standard report is T3.4 |
| Document the process, how prompts were used, how AI work is checked, when it is accepted | [ai-process.md](docs/ai-process.md) | Done; kept up to date per task |
| Play with the green tests | `scripts/mutation-check.py`, ai-process.md §3 | Done: 6 deliberate bugs, 2 survived, tests added |
| State space analysis | T3.3, T3.4, A7 | `cpn statespace` done (T3.3); SCCs and the report next (T3.4), then the view in the app (A7) |
| Spec-driven development: vision.md, feature-spec.md (DoD, scope) | [vision.md](docs/vision.md), [feature-spec.md](docs/feature-spec.md) | Done |

Order from here: **T3.5** (start the corpus) → **T3.1** import → **T3.1b** export → **T3.1c** round trip →
**T3.2** timed → **T3.3/T3.4** state space and report → A7. T2.6–T2.9 (Python-like syntax) follow after.

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
