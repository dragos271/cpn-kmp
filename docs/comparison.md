# Comparison with CPN Tools and the earlier HVL tools

Last updated 2026-11-23. This file is regenerated as features land; the status of each of our
features is in [feature-spec.md](feature-spec.md).

## The tools

| | CPN Tools 4.x | Garvik (2022) | Gjesdal (2023) | This thesis |
|---|---|---|---|---|
| What it is | Editor, simulator, state space | Compiler and runtime, run from a terminal | Graphical editor | Headless engine + CLI + app |
| Language / stack | SML simulator, BETA GUI | F# / .NET | JavaScript, Electron, GoJS | Kotlin Multiplatform, Compose |
| Runs on | Windows (others via Wine) | .NET 6 (Windows, macOS, Linux) | Electron desktop | JVM, JS, Wasm, native; app on desktop + web |
| Source | Open source (cpntools.org) | Private repository `smartoceanplatform/cpn-simulator`; we have a copy (2026-11-23) | **Private**: `smartoceanplatform/cpn-editor` | Public: `dragos271/cpn-kmp` |
| Ran by us? | Not yet (needs Windows or Wine) | Yes, 2026-11-23, on .NET 8: his 5 models in 11 sizes ([performance.md](performance.md)) | **No**: repository is private | Yes, in CI on 3 OSes |

**Source of the facts.** For Garvik's tool, the facts come from his thesis (*A Compiler and
Runtime Environment for Execution of CPN Models*, HVL 2022) and, since 2026-11-23, from his source
code. It reads `.cpn` files whose
inscriptions are written in **F#**, not CPN ML (models from CPN Tools have to be translated by
hand), compiles them to F#, and simulates them from an interactive terminal; it explores state
spaces and reports their size. It supports flat nets only (no hierarchy), no timed nets and no
subset colour sets. Every model needs its own build: the compiler writes F# code for the model, the
code is added to the `examples` project, `Simulator.fs` is edited to open that model, and the
project is rebuilt; changing a constant such as the number of workers means doing it again. For
Gjesdal's editor, everything comes from that thesis itself (chapters 4, 6
and 7).

**Garvik's state space table reproduced.** His Table 6.1 and Appendix B give the CPN Tools state
space size of 11 models. Our state space gives the same nodes and arcs for all 11 (from 11 / 30 up
to 59 050 / 314 946), checked in CI; see [reference/garvik-2022](../reference/garvik-2022/README.md).
This also found and fixed one bug in our engine.

Cells marked **?** need the tool to be run. Gjesdal's editor is still private.

## Feature matrix

✅ supported · 🟡 partial · ❌ not supported · ? unknown

| Feature | CPN Tools | Garvik F# | Gjesdal editor | **Ours** | Notes on ours |
|---|---|---|---|---|---|
| **Modelling** | | | | | |
| Places, transitions, arcs | ✅ | ✅ | ✅ | ✅ | |
| Graphical editor | ✅ | ❌ | ✅ | ✅ | Desktop and web app |
| Undo / redo | ✅ | ❌ | ✅ (GoJS) | ✅ | |
| Copy / cut / paste | ✅ | ❌ | ✅ (GoJS) | ❌ | Not done yet |
| Declarations editor | ✅ | n/a | ❌ (thesis §6.2.6) | ✅ | |
| Live syntax and type errors | ✅ | ❌ compile errors, the first one only | ❌ | ✅ | |
| Substitution transitions and hierarchy | ✅ | ❌ flat nets only | 🟡 one file per submodule, linked by hand | ✅ | Pages and port/socket assignment |
| Fusion places | ✅ | ❌ | ❌ | ✅ (engine) | No editing UI for fusion sets yet |
| Inhibitor and reset arcs | ✅ | ❌ | 🟡 arrowhead style only | ❌ | Open question |
| Transition priorities | ✅ | ❌ | 🟡 text field only | ❌ | Open question |
| Styling (colours, line width) | ✅ | n/a | 🟡 colour only | ❌ | Out of scope |
| **Language** | | | | | |
| CPN ML colour sets | ✅ | 🟡 F# types; no subsets | ❌ text only | ✅ | Every kind in T2.3 |
| CPN ML type checking | ✅ | 🟡 F# compiler, on F# inscriptions | ❌ | ✅ | Hindley–Milner |
| CPN ML evaluation | ✅ | 🟡 inscriptions must be rewritten in F# | ❌ | ✅ | Interpreted |
| Python-like inscriptions | ❌ | ❌ | ❌ | ❌ | Planned (F9) |
| **Simulation** | | | | | |
| Interactive simulation (choose a binding) | ✅ | 🟡 in a terminal | ❌ mock only | ✅ | |
| Automatic simulation with a seed | 🟡 random, no seed | 🟡 `jump N`, no seed | ❌ | ✅ | The same trace on every platform |
| Headless / CLI | 🟡 via Access/CPN | ✅ | ❌ | ✅ | `cpn simulate` |
| Timed nets | ✅ | ❌ | 🟡 `time` text field | ✅ | Integer time: availability, transition and arc delays, `time()`, clock shown in the app |
| **Analysis** | | | | | |
| State space | ✅ | ✅ | ❌ | ✅ | `cpn statespace` and the app's Analyse mode: full or partial, limits on nodes, arcs and time; timed nets too. Same sizes as CPN Tools on Garvik's 11 models |
| Performance reports (monitors, replications, confidence intervals) | ✅ | ❌ | ❌ | ✅ | `cpn perf` and the app's Analyse mode, checked against queueing theory ([guide](performance-reports.md)) |
| Standard state-space report | ✅ | 🟡 size only | ❌ | ✅ | `cpn statespace --report`: bounds, home, liveness, fairness ([examples](reports/)) |
| **Files** | | | | | |
| Reads `.cpn` | ✅ | 🟡 with F# inscriptions | ❌ (future work §7.3) | ✅ | 49/49 corpus models import, 35/49 run ([results](corpus-results.md)) |
| Writes `.cpn` | ✅ | ❌ | ❌ | ✅ | Unchanged models byte for byte; models made in the app as complete files (not yet opened in CPN Tools) |
| Own JSON format | ❌ | ❌ | ✅ GoJS model JSON | ✅ | Documented in json-format.md |
| Stable under repeated save/open | ✅ | n/a | ❌ known bugs: sizes and arc ports lost on reload (§7.3.1–7.3.2) | ✅ | Tested in CI: 49 corpus models and 500 random nets, 5-cycle fixed point |
| **Engineering** | | | | | |
| Automated tests | ? | 🟡 7 multiset unit tests | ❌ none described | ✅ ~240 tests × 4 runtimes | |
| CI on several OSes | ❌ | ❌ | ❌ | ✅ Linux, macOS, Windows | |
| Runs in a browser | ❌ | ❌ | ❌ (Electron) | ✅ Wasm | |

## Language features: CPN Tools, Garvik and ours

The feature matrix above compares the tools. This one compares the **inscription language**, line by
line. CPN Tools' column is the language as Jensen & Kristensen (2009) and the CPN Tools help define
it. Garvik's column comes from his source code (`smartoceanplatform/cpn-simulator`, received
2026-11-23): `Loader/Load.fs` decides which colour sets are read, and inscriptions are F# checked by
the F# compiler (`Parser/Parse.fs`). Ours is checked by the tests in `cpn-lang` and by the 49 corpus
models.

| Feature | CPN Tools (CPN ML) | Garvik (F#) | **Ours (CPN ML)** |
|---|---|---|---|
| **Inscription language** | Standard ML + CPN ML | F# (models must be rewritten by hand) | Standard ML + CPN ML, so CPN Tools models run unchanged |
| **Colour sets** | | | |
| `unit`, `bool`, `int`, `string` | ✅ | ✅ | ✅ |
| `int with a..b` (ranges) | ✅ | ❌ the range is dropped | ✅ checked when tokens are made |
| `intinf`, `real`, `time` | ✅ | ❌ | ✅ (`intinf` as 64-bit, decision D8) |
| `string with "a".."z"` | ✅ | ❌ | ✅ |
| Enumerations (`with a \| b`) | ✅ | ✅ | ✅ |
| `index` | ✅ | ✅ | ✅ |
| Products | ✅ any size | 🟡 two components only | ✅ any size |
| Records | ✅ | ❌ | ✅ |
| Unions | ✅ | ❌ | ✅ |
| Lists (`list X`, `with 0..n` lengths) | ✅ | 🟡 no length bounds | ✅ |
| Subsets (`subset X by f` / `with [...]`) | ✅ | ❌ | ✅ |
| Aliases (`colset A = B`) | ✅ | ❌ | ✅ |
| Timed colour sets (`timed`) | ✅ | ❌ | ✅ |
| Colour set functions (`all`, `size`, `ord`, `col`, `ran`, `mkstr`, `legal`) | ✅ | 🟡 `all()` on index sets | ✅ |
| **Declarations** | | | |
| `var`, `val`, `fun` (recursive, clausal) | ✅ | ✅ as F# `let` | ✅ |
| `datatype`, `exception`, `local`, `open`, `globref` | ✅ | 🟡 F# equivalents | ✅ |
| SML structures and signatures | ✅ | n/a | ❌ out of scope |
| Loading `.sml` files (`use`), `TextIO` | ✅ | ❌ | ❌ reported as unsupported (7 corpus models) |
| **Expressions** | | | |
| Multisets: `` ` ``, `++`, `--`, `<<=`, `empty`, `size`, `cf` | ✅ | 🟡 `^`, `++`, `--`, `<<=` | ✅ |
| Multisets as lists (`ms_to_list`, `list_to_ms`, `sort_ms`, `mult`) | ✅ | ❌ | ✅ |
| Basis library (`List`, `String`, `Int`, `Real`, `Char`, `Math`, `Option`) | ✅ | ✅ .NET library instead | ✅ the parts models use |
| Random distributions (`uniform`, `normal`, `exponential`, `poisson`, `bernoulli`, `discrete`) | ✅ | ❌ | ✅ seeded: the same run on every platform |
| Equality on `real` | 🟡 accepted by SML/NJ, not SML'97 | n/a | ❌ rejected (1 corpus model) |
| **Inscriptions** | | | |
| Guards (a boolean or a list) | ✅ | ✅ | ✅ |
| Patterns on input arcs bind variables | ✅ | ✅ | ✅ including `x::xs` and sums of patterns |
| Variables bound by enumerating small colour sets | ✅ | ✅ enums and `bool` | ✅ |
| Double arcs | ✅ | ✅ | ✅ |
| Timestamps on tokens (`@+d`), transition delays, `time()` | ✅ | ❌ | ✅ |
| Absolute timestamps in patterns (`x@t`) | ✅ | ❌ | ❌ (3 corpus models) |
| Code segments | ✅ | ❌ | ❌ reported |
| Transition priorities | ✅ | ❌ | ❌ read and kept, not used |
| Inhibitor and reset arcs | ✅ | ❌ | ❌ (2 corpus models) |
| **Model structure** | | | |
| Several pages, substitution transitions, ports and sockets | ✅ | ❌ one page | ✅ |
| Fusion places | ✅ | ❌ | ✅ |
| **Errors** | | | |
| Type errors located at the element and the text | ✅ | 🟡 the F# message, per element | ✅ |
| **External functions in another language** | ❌ SML only (Comms/CPN for sockets) | 🟡 any .NET code, compiled in | ✅ Python, with an SML fallback so the file still runs in CPN Tools ([guide](python-functions.md)) |

**Summary.** Garvik's tool reads a subset of CPN Tools' colour sets and asks for inscriptions in
F#; his thesis translates every model by hand. Ours reads CPN Tools models as they are: 49 of 49
corpus models import and 35 run, and the 14 that do not are listed in
[corpus-results.md](corpus-results.md). What both lack compared with CPN Tools is the same short list:
code segments, priorities, inhibitor and reset arcs, and SML file I/O.

## Performance compared with Garvik

See [performance.md](performance.md): his experiments repeated with both tools on the same machine.
In short (2026-11-25): ours simulates 1.6–4× faster than his compiled F#, and explores state
spaces up to several hundred times faster, because his exploration is quadratic. On 2026-11-23 his
was still faster on small models; profiling and caching pure inscriptions turned that around.

## Gjesdal's requirements applied to our tool

Gjesdal graded their editor against requirements R1–R9 (thesis Table 2.1, graded in Table 6.1).
Here are the same requirements graded for our tool with the same scale, to make the two directly
comparable.

| ID | Requirement | Gjesdal (2023) | Ours (2026-11-09) | Why |
|---|---|---|---|---|
| R1 | Places | High | High | Names, colour sets, initial and current marking |
| R2 | Transitions | High | High | Guards; enabled transitions are highlighted while simulating |
| R3 | Markings | High | High | Token count and marking shown live from the real engine, not a mock |
| R4 | Arcs | High | High | Inscriptions are type-checked; arcs can go both ways |
| R5 | Substitution transitions | Medium | High | Pages, port/socket assignment, one tab per page instance |
| R6 | Runtime environment | Medium | High | Desktop and web from one code base; a real simulator |
| R7 | Saving and loading | Medium | Medium | Files with dialogs work; `.cpn` is still missing |
| R8 | Modularity (submodules in separate files) | High | Low | We keep CPN Tools' one-file model on purpose, for `.cpn` compatibility |
| R9 | Common editing operations | High | Medium | Undo and redo work; copy and paste do not yet |

## What the earlier work tells us to avoid

- **No connection between editor and simulator.** Gjesdal's editor never ran a real simulation,
  because its JSON format and the simulator's `.cpn` and F# formats were never connected. Our app
  and CLI share one engine and one document format, and a test checks that they give the same
  trace.
- **Losing data on reload.** Gjesdal's sections 7.3.1 and 7.3.2 report that node sizes and arc
  connections were lost on reload. That is why repeated import/export (F6) is a property checked
  in CI, not a manual check.
- **No `.cpn` support in the editor.** Without it, models cannot move between tools. F4 and F5
  make `.cpn` the format we exchange with other tools.
