# Feature specification

This file is the contract for each feature. For every feature it gives:

- **scope**: what is in and what is out;
- **definition of done (DoD)**: checks anyone can run to decide whether the feature is finished;
- **status**: where the feature stands.

The reasons behind the features are in [vision.md](vision.md), and the order of work is in
[TASKS.md](../TASKS.md). Work produced by the AI is accepted against these DoDs, as described in
[ai-process.md](ai-process.md).

Status values: **done**, **partial**, **not started**. Every point from the supervisor meetings is
tracked in [supervisor-notes.md](supervisor-notes.md).

## Definition of done for every feature

A feature is only done when all of these hold, in addition to its own DoD:

1. Its tests are in `commonTest`, and CI is green on Linux, macOS and Windows for JVM, JS, Wasm and
   native.
2. `./gradlew ktlintCheck` passes.
3. There is at least one test that would fail if the feature were removed or broken. This is
   checked by breaking the code on purpose (see [ai-process.md](ai-process.md#3-check-that-the-tests-can-fail)).
4. The user-facing docs are updated: README, `docs/`, and the status in TASKS.md.
5. It can be shown in two minutes, from the `cpn` command or in the app.
6. Every simplification is written down here or in TASKS.md, not hidden.

---

## F1 Untimed simulation

**Scope.** In: multisets, places, transitions, arcs, guards, the enabling and firing rule, binding
search, hierarchy (substitution transitions, port/socket, fusion sets), and a seeded random
simulator with stop conditions. Out: priorities on transitions and inhibitor/reset arcs (they are
not part of the textbook CPN definition; see the open questions).

**DoD.**
- The textbook chapter 2 examples give the expected enabled bindings.
- A hierarchical net behaves like its hand-flattened version.
- The same seed gives the same trace on JVM, JS and native.

**Status: done** (T1.2–T1.7).

## F2 CPN ML inscriptions

**Scope.** In: declarations (`colset`, `var`, `val`, `fun`, `datatype`, `exception`, `local`,
`globref`, `open`), every colour set kind, a Hindley–Milner type checker, an evaluator, and the
Basis functions that models use. Out: SML modules (`structure` and `signature`), and `IntInf`
bignums (decision D8).

**DoD.**
- The I1 nets rebuilt with CPN ML text give identical traces.
- Every name the type checker knows has a value at runtime.
- Every corpus declaration block parses and type-checks (waits for F4).

**Status: done**, except checking the corpus.

## F3 Editable model and app

**Scope.** In: a model document with ids, layout and inscription text; a desktop and web app that
can draw, edit, undo and redo, show live errors, simulate, and open and save files. Out:
collaborative editing, and styling arcs and nodes (colours, line widths).

**DoD.**
- An edited model runs in the app, and gives the same trace as `cpn simulate` for the same seed.
- Errors point at the element and the text that is wrong.

**Status: done** (A1–A6).

## F4 `.cpn` import

**Scope.** In:
- the CPN Tools 4.x XML: pages, places, transitions and arcs with positions;
- every inscription, including names, colour sets, initial markings, guards, arc expressions and
  `@+` delays;
- declarations (`globbox`, including nested blocks);
- hierarchy (`subst`, port/socket, fusion);
- arcs that go both ways (`BOTHDIR`).

Out: monitors, simulation options and graphics-only elements (auxiliary shapes and text, kept
unchanged only where F6 needs them).

**DoD.**
- At least 30 corpus models (F12) import without crashing.
- Every element that is not supported is reported by name: nothing is silently dropped.
- Each imported model type-checks, or its failures are categorised.

**Status: done** (T3.1, 2026-11-10).
- All 49 corpus models import; 35 type-check and run (26 before T2.10 made multisets lists).
- The remaining 14 are categorised in [corpus-results.md](corpus-results.md): 7 because they use SML file
  I/O or load `.sml` files, 3 because they need absolute timestamps (`@`), 2 because they use inhibitor
  arcs, and 2 for smaller gaps.
- Ignored features are listed in the import notes, and unsupported ones are reported as problems.

## F5 `.cpn` export

**Scope.** In: write any document our tool can hold as CPN Tools 4.x XML, so it opens in CPN Tools,
including models made from scratch in the app. Out: CPN Tools state that only the GUI uses, such as
open binders and window positions; we write fixed defaults for these.

**DoD.**
- The exported files are valid XML in the CPN Tools 4.x DTD structure (tested automatically).
- At least five models made from scratch in our app were opened and simulated in CPN Tools, and
  optionally in CPN IDE. Each check is recorded in `docs/interop-log.md` with the tool version, the
  date, what was checked and a screenshot.

**Status: done, except the manual check.**
- Export works from `cpn convert` and from the app.
- The structure checks and the five models made from scratch are tested automatically.
- Opening the five files in CPN Tools ([interop-log.md](interop-log.md)) is still to do, because it needs Windows.

## F6 Round-trip stability under repeated import/export (important)

This was raised as important in the supervisor meeting on 2026-11-09.

**Scope.** Every path through our formats:

- `.cpn` → our tool → `.cpn`
- `.cpn` → our document JSON → `.cpn`
- the app's save → open → save

**DoD.** This is a property, checked by automated tests on the whole corpus and on randomly
generated nets:

1. **Nothing is lost.** For model *M*, `import(export(import(M)))` equals `import(M)`. "Equal"
   means the same pages, places, transitions and arcs with the same ids, names, inscription text,
   declarations, hierarchy and positions (within 0.01). The two must also behave the same: the same
   initial enabled bindings, and the same trace for seeds 1, 2 and 3.
2. **Exporting is a fixed point.** From the second export on, the bytes are identical:
   `export(import(export(import(M))))` gives exactly the same file as `export(import(M))`. Running
   this for 5 cycles is a CI test.
3. **Normalisations are listed.** Any change we make on the first import is listed in
   `docs/cpn-format.md`, for example reformatting whitespace or adding missing ids. Nothing else
   may change.
4. **Random nets.** A property test generates random nets (random pages, places, arcs, colour sets
   and markings) and checks 1 and 2 for at least 500 cases per CI run.
5. **The same in the app.** Opening and saving a model 5 times in the app gives identical files.

**Status: done** (T3.1b, T3.1c).
- All 49 corpus models come back byte for byte when unchanged, and generated files import as the
  same model.
- Exporting is a fixed point over 5 cycles.
- 500 random nets per CI run pass the same checks, and the app's own format passes save → open → save.
- The normalisations of a *new* file are listed in [cpn-format.md](cpn-format.md#writing).

## F7 Timed nets

**Scope.** In:
- timed colour sets (`colset T = ... timed`);
- tokens with an **integer** timestamp, so a token is *available* only when its time ≤ the global
  clock;
- delays on transitions (`@+` in the time region) and on output arcs (`expr @+ d`);
- `time()`;
- a global integer clock with the CPN Tools earliest-time step rule: when nothing is enabled now,
  move the clock to the earliest time at which something becomes enabled.

Out: real-valued time, and `@@` absolute timestamps (unless the corpus needs them).

**DoD.**
- The timed protocol example from Jensen & Kristensen gives the same sequence of (time, transition)
  as CPN Tools for a fixed choice sequence.
- Tokens are not available before their time (unit tests).
- The clock never goes backwards (a property test).
- The app shows timestamps (`1`x@5`) and the clock.

**Status: done, except the comparison with CPN Tools** (T3.2, 2026-11-10).
- The textbook timed protocol runs with model time. Its first steps (Send Packet at 0, Transmit Packet at 9,
  retransmission due at 109) were checked by hand against the semantics.
- Availability, delays, the clock moving forward, and "the clock never goes backwards" are tested, and the app
  shows timestamps and the clock.
- Matching CPN Tools' trace needs CPN Tools (T4.1).

## F8 State space and standard report

**Scope.** In:
- a reachability graph built breadth-first, with limits on nodes and on time;
- SCCs;
- the CPN Tools standard report: statistics, home markings, dead markings, dead and live
  transitions, integer and multiset bounds, and fairness;
- `cpn statespace`, with text and JSON output;
- the report view in the app (A7).

Out: symmetry and equivalence reductions, the sweep-line method, and CTL/ASK-CTL model checking
(possible future work).

**DoD.**
- The node and arc counts and the report values equal CPN Tools on at least 10 corpus models
  (golden files, T4.1).
- The report format follows the CPN Tools section order.

**Status: done in the CLI and library; the app view (A7) and the comparison with CPN Tools (T4.2) remain.**
- The reachability graph (T3.3) and the standard report with SCCs (T3.4) are done: `cpn statespace --report`.
- Sizes and report values are checked independently on textbook models: the limit protocol has 13,215 nodes,
  52,784 arcs and 5,013 SCCs.
- Example reports are in [reports/](reports/).
- Node and arc counts equal CPN Tools on 11 models, using the CPN Tools reports in Garvik's
  thesis ([reference/garvik-2022](../reference/garvik-2022/README.md), `CpnToolsReferenceTest`).
  That covers the counts part of the DoD for those models; the other report values still need
  CPN Tools output (T4.1).

## F9 Python-like inscription syntax

**Scope.** As in the T2.6 spec: a second front-end that produces the same AST as CPN ML, so types
and evaluation are shared.

**DoD.**
- There is a written spec with a mapping table from SML to the new syntax.
- The same model written in both syntaxes gives an identical AST.
- There is a translator from SML to the new syntax.

**Status: on hold.** At the latest supervisor meeting the direction changed: inscriptions stay
Standard ML, as in CPN Tools, and Python comes in as the language for *external functions* (F13).
To confirm with the supervisor before F9 is dropped.

## F10 Diagnostics

**Scope.** One `Diagnostic` type, used by the command line, the app and the API.

**DoD.**
- Golden tests cover the error messages.
- Command-line output is in a rustc-like format.

**Status: partial.** Located errors exist, but there is no unified type yet (T2.9).

## F11 Public API and full CLI

**Scope.** `CpnModel.load()`, `.simulator(seed)` and `.stateSpace()`, plus the commands
`cpn check|simulate|statespace|convert`.

**DoD.** API docs are generated with Dokka, and the exit codes are documented.

**Status: partial.** `cpn simulate` exists (T3.6, T3.7).

## F12 Model corpus and reference outputs

**Scope.**
- At least 30 `.cpn` models collected from the web, with their source and licence;
- golden outputs from CPN Tools for them: enabled bindings, markings after N fixed steps, and state
  space size.

**DoD.**
- `corpus/manifest.json` lists every model.
- The golden files are generated by a script that is in the repository.

**Status: partial.** The corpus is done (T3.5): 49 models, listed in `corpus/manifest.json` with their source
and the features they use. The golden outputs from CPN Tools wait for T4.1.

## F13 External functions in Python

**Why.** In a real model some values come from data, not from the modeller. In the pathology lab
(F15), how long sectioning takes depends on the specimen, the worker and the day; a lab worker who
has fitted those times in Python should be able to hand over a function, `delay_section`, that the
model calls, instead of the modeller typing a delay into every transition.

**Scope.** In:
- a declaration that binds a CPN ML name to a Python function, with a CPN ML type:
  ```sml
  fun delaySection (s : SPECIMEN, w : WORKER) : int = 30 (* @python lab_delays.delay_section *)
  ```
  The body is an ordinary SML fallback, so the file still opens and runs in CPN Tools; our tool
  calls the Python function instead (decision to confirm: annotation in a comment vs. a new
  `extern` keyword, which CPN Tools would reject);
- values passed as JSON: `int`, `real`, `string`, `bool`, enumerations (as strings), products and
  lists (as arrays), records (as objects), unions (as `{"tag": ..., "value": ...}`);
- checks: the result is type-checked against the declared type, and an error says which function
  returned what, at which step;
- reproducibility: the Python side gets a seed derived from the simulation seed, so a seeded run
  gives the same trace again (with the same Python packages);
- runtime: on the JVM and native, a Python process started once per simulation and talked to over
  standard input/output; in the browser, Pyodide (later).

Out: Python in inscriptions themselves (that was F9); calling the engine *from* Python (a possible
later Python package).

**DoD.**
- A model with an external delay function runs from `cpn simulate` and in the desktop app.
- The same model, with the fallback body, opens and runs in CPN Tools unchanged.
- A wrong return type, an exception in Python, and a missing module each give a located error.
- Overhead per call is measured and reported in [performance.md](performance.md).

**Status: not started** (design above to agree with the supervisor).

## F14 Performance report from simulation

**Why.** The end goal of the pathology case is a report that supports planning: for example, how
turnaround time changes with one more lab worker. CPN Tools does this with monitors and its
performance report; Garvik's tool and ours do not yet.

**Scope.** In:
- data collectors on places and transitions (CPN Tools "monitors"): counts, token waiting times,
  queue lengths, resource utilisation;
- batch runs: N independent replications with different seeds, each until a stop time or step
  count;
- `cpn perf model.cpn --replications 30 --until 10000`, writing a report (Markdown/HTML and JSON)
  with mean, standard deviation and 95 % confidence interval per measure;
- parameter sweeps with `--set` (e.g. `--set WORKERS=3,4,5`), so scenarios can be compared side by
  side.

Out: CPN Tools' own monitor code (SML functions written into `.cpn` files) at first; importing those
is a later step.

**DoD.**
- On a textbook queueing model (M/M/1 as a CPN), the reported mean waiting time is within the
  confidence interval of the analytical value.
- Replications are reproducible from the seed on every platform.
- The pathology model (F15) produces a report with turnaround time per specimen type.

**Status: partial** (2026-11-25). Done: monitors (marking size, occurrences, observations with a
CPN ML expression), replications with Student's t confidence intervals, warm-up, scenario sweeps
with `--set`, text/Markdown/JSON output: `cpn perf` ([performance-reports.md](performance-reports.md)).
Checked: the M/M/1 queue's utilisation, L, W and throughput lie in their 95 % intervals, and a
deterministic queue gives exact values (`PerformanceTest`); replications repeat exactly from their
seed on JVM, JS and Wasm. Remaining: the pathology lab report (F15), monitors in the app, importing
CPN Tools' own monitors.

## F15 Real-world case: pathology lab

**Why.** To show the tool on a real process, not only on textbook models.

**Scope.** A timed, hierarchical CPN of the specimen flow in a pathology lab: reception and
registration, grossing, tissue processing (batch, overnight), embedding, sectioning, staining,
scanning, diagnosis by a pathologist and reporting. Workers, pathologists and machines are
resources; delays come from external Python functions (F13) fitted to lab data; the output is the
performance report (F14): turnaround time, queue lengths, utilisation, and what-if scenarios.

**DoD.**
- The model runs in our tool and, with fallback delays, in CPN Tools.
- The lab confirms that the process structure is right (validation by a domain expert).
- At least two what-if scenarios are compared in a report.

**Status: not started.** Data access and ethics are cleared: the supervisor works with the lab
(2026-11-25). Still needed: the process description and the timing data from the lab.

---

## Out of scope

| Feature | Why |
|---|---|
| Access/CPN extension protocol | We are the engine; we don't plug into CPN Tools |
| Full SML (modules and functors) | Not used in the model corpus (D4) |
| Real-valued time | The supervisor asked for integer time; CPN Tools models rarely need more |
| Multi-user editing, a cloud service | Not part of the thesis |
| Copying the CPN Tools GUI | The app follows draw.io conventions instead (decided 2026-10-27) |

## Open questions for the supervisor

- **Inhibitor and reset arcs:** CPN Tools has them, but they are outside the textbook CPN
  definition. Only 2 of the 49 corpus models use them. Should we support them?
- **Transition priorities:** these come up in Gjesdal's 2PC model (`P_HIGH`) and in 8 of the 49
  corpus models, including MQTT and Paxos. Should we support them?
- **Monitors:** 6 corpus models have monitors. The plan is to import them and keep them, without
  running them.
- **Python external functions (F13):** annotation in an SML comment (the file still opens in CPN
  Tools) or a new `extern` declaration? And is F9 (a Python-like syntax for inscriptions) dropped?
- **Earlier tools:** we need access to Gjesdal's editor (`smartoceanplatform/cpn-editor`, private)
  to run it and complete [comparison.md](comparison.md). Garvik's F# simulator was received and
  run on 2026-11-23 ([performance.md](performance.md)).
