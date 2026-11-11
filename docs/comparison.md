# Comparison with CPN Tools and the earlier HVL tools

Last updated 2026-11-09. This file is regenerated as features land; the status of each of our
features is in [feature-spec.md](feature-spec.md).

## The tools

| | CPN Tools 4.x | Garvik (2022) | Gjesdal (2023) | This thesis |
|---|---|---|---|---|
| What it is | Editor, simulator, state space | Compiler and runtime, run from a terminal | Graphical editor | Headless engine + CLI + app |
| Language / stack | SML simulator, BETA GUI | F# / .NET | JavaScript, Electron, GoJS | Kotlin Multiplatform, Compose |
| Runs on | Windows (others via Wine) | .NET (presumably Windows, macOS, Linux) | Electron desktop | JVM, JS, Wasm, native; app on desktop + web |
| Source | Open source (cpntools.org) | **Not found publicly** | **Private**: `smartoceanplatform/cpn-editor` | Public: `dragos271/cpn-kmp` |
| Ran by us? | Not yet (needs Windows or Wine) | **No**: no source | **No**: repository is private | Yes, in CI on 3 OSes |

**Source of the facts.** For Garvik's tool, everything comes from Gjesdal's thesis, sections 1.3
and 4.3.1. That thesis says the tool takes `.cpn` input, generates F# (`.fs`) code, simulates, and
writes a state-space report. For Gjesdal's editor, everything comes from that thesis itself
(chapters 4, 6 and 7).

Cells marked **?** need the tool to be run. To fill them in, ask the supervisor for access to both
repositories.

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
| Live syntax and type errors | ✅ | ? | ❌ | ✅ | |
| Substitution transitions and hierarchy | ✅ | ? | 🟡 one file per submodule, linked by hand | ✅ | Pages and port/socket assignment |
| Fusion places | ✅ | ? | ❌ | ✅ (engine) | No editing UI for fusion sets yet |
| Inhibitor and reset arcs | ✅ | ? | 🟡 arrowhead style only | ❌ | Open question |
| Transition priorities | ✅ | ? | 🟡 text field only | ❌ | Open question |
| Styling (colours, line width) | ✅ | n/a | 🟡 colour only | ❌ | Out of scope |
| **Language** | | | | | |
| CPN ML colour sets | ✅ | ? | ❌ text only | ✅ | Every kind in T2.3 |
| CPN ML type checking | ✅ | ? (F# compiler) | ❌ | ✅ | Hindley–Milner |
| CPN ML evaluation | ✅ | ✅ (compiled to F#) | ❌ | ✅ | Interpreted |
| Python-like inscriptions | ❌ | ❌ | ❌ | ❌ | Planned (F9) |
| **Simulation** | | | | | |
| Interactive simulation (choose a binding) | ✅ | ? | ❌ mock only | ✅ | |
| Automatic simulation with a seed | 🟡 random, no seed | ? | ❌ | ✅ | The same trace on every platform |
| Headless / CLI | 🟡 via Access/CPN | ✅ | ❌ | ✅ | `cpn simulate` |
| Timed nets | ✅ | ? | 🟡 `time` text field | ✅ | Integer time: availability, transition and arc delays, `time()`, clock shown in the app |
| **Analysis** | | | | | |
| State space | ✅ | ✅ | ❌ | ✅ | `cpn statespace`: full or partial, limits on nodes, arcs and time; timed nets too |
| Standard state-space report | ✅ | ✅ | ❌ | 🟡 | Size and dead markings only; the full report is T3.4 |
| **Files** | | | | | |
| Reads `.cpn` | ✅ | ✅ | ❌ (future work §7.3) | ✅ | 49/49 corpus models import, 26/49 run ([results](corpus-results.md)) |
| Writes `.cpn` | ✅ | ❌ | ❌ | ✅ | Unchanged models byte for byte; models made in the app as complete files (not yet opened in CPN Tools) |
| Own JSON format | ❌ | ❌ | ✅ GoJS model JSON | ✅ | Documented in json-format.md |
| Stable under repeated save/open | ✅ | n/a | ❌ known bugs: sizes and arc ports lost on reload (§7.3.1–7.3.2) | ✅ | Tested in CI: 49 corpus models and 500 random nets, 5-cycle fixed point |
| **Engineering** | | | | | |
| Automated tests | ? | ? | ❌ none described | ✅ ~240 tests × 4 runtimes | |
| CI on several OSes | ❌ | ? | ❌ | ✅ Linux, macOS, Windows | |
| Runs in a browser | ❌ | ❌ | ❌ (Electron) | ✅ Wasm | |

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
