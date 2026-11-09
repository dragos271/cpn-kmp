# Feature specification

This file is the contract for each feature. For every feature it gives:

- **scope**: what is in and what is out;
- **definition of done (DoD)**: checks anyone can run to decide whether the feature is finished;
- **status**: where the feature stands.

The reasons behind the features are in [vision.md](vision.md), and the order of work is in
[TASKS.md](../TASKS.md). Work produced by the AI is accepted against these DoDs, as described in
[ai-process.md](ai-process.md).

Status values: **done**, **partial**, **not started**.

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

**Status: not started** (T3.1).

## F5 `.cpn` export

**Scope.** In: write any document our tool can hold as CPN Tools 4.x XML, so it opens in CPN Tools,
including models made from scratch in the app. Out: CPN Tools state that only the GUI uses, such as
open binders and window positions; we write fixed defaults for these.

**DoD.**
- The exported files are valid XML in the CPN Tools 4.x DTD structure (tested automatically).
- At least five models made from scratch in our app were opened and simulated in CPN Tools, and
  optionally in CPN IDE. Each check is recorded in `docs/interop-log.md` with the tool version, the
  date, what was checked and a screenshot.

**Status: not started.**

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

**Status: partial.** The app's JSON documents already round-trip (cpn-io tests). `.cpn` round trips
wait for F4 and F5.

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

**Status: not started** (T3.2). Today `@+` is parsed and ignored, and `time()` is 0.

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

**Status: not started** (T3.3, T3.4).

## F9 Python-like inscription syntax

**Scope.** As in the T2.6 spec: a second front-end that produces the same AST as CPN ML, so types
and evaluation are shared.

**DoD.**
- There is a written spec with a mapping table from SML to the new syntax.
- The same model written in both syntaxes gives an identical AST.
- There is a translator from SML to the new syntax.

**Status: not started** (T2.6–T2.8).

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

**Status: not started** (T3.5, T4.1).

---

## Out of scope

| Feature | Why |
|---|---|
| Monitors, performance reports, batch simulation replications | Not needed for RQ1–RQ3 |
| Access/CPN extension protocol | We are the engine; we don't plug into CPN Tools |
| Full SML (modules and functors) | Not used in the model corpus (D4) |
| Real-valued time | The supervisor asked for integer time; CPN Tools models rarely need more |
| Multi-user editing, a cloud service | Not part of the thesis |
| Copying the CPN Tools GUI | The app follows draw.io conventions instead (decided 2026-10-27) |

## Open questions for the supervisor

- **Inhibitor and reset arcs:** CPN Tools has them, but they are outside the textbook CPN
  definition. Should we support them?
- **Transition priorities:** these come up in Gjesdal's 2PC model (`P_HIGH`). Should we support
  them?
- **Earlier tools:** we need access to Gjesdal's editor (`smartoceanplatform/cpn-editor`, private)
  and to Garvik's F# simulator, to run them and complete [comparison.md](comparison.md).
