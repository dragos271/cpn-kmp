# Vision

*A Headless, Cross-Platform Coloured Petri Net Tool* — master thesis, Dragos Slovineanu, HVL/UiB.
Supervisor: Patrick Stünkel.

This file says **why** the tool exists and **what success looks like**. What each feature must do,
and when it counts as done, is in [feature-spec.md](feature-spec.md). The order of work is in
[TASKS.md](../TASKS.md).

## Problem

CPN Tools is the reference tool for Coloured Petri Nets, but it:

- is a Windows desktop program (macOS and Linux only through Wine);
- uses an SML simulator that can't be embedded in other programs, scripts or CI pipelines;
- uses inscriptions in CPN ML (Standard ML), which most programmers today don't know;
- has had no active development for several years.

Two earlier HVL theses tried to modernise it:

- **Garvik (2022)**, *A Compiler and Runtime Environment for Execution of CPN Models*: an F#/.NET
  simulator that runs `.cpn` models from a terminal.
- **Gjesdal (2023)**, *A Modular IDE for CPN Models*: an Electron + GoJS editor with only a mock
  simulator. It saves to its own JSON format and does not read or write `.cpn`.

So the simulator and the editor were never connected, and neither of them was compared against CPN
Tools on real models. See [comparison.md](comparison.md).

## What we build

One engine, written once in Kotlin Multiplatform, that:

1. **runs headless**: as a library on the JVM, JavaScript, WebAssembly and native code, and as a
   `cpn` command for scripts and CI;
2. **runs existing models**: it reads and writes CPN Tools `.cpn` files, and gives the same
   behaviour as CPN Tools on them (untimed and timed nets);
3. **analyses models**: state space and the CPN Tools standard report;
4. **offers a second inscription syntax**: a Python-like syntax with the same meaning as CPN ML;
5. **has an app on top**: a desktop and web editor and simulator (in the style of draw.io) that uses
   only the public engine, so it never behaves differently from the command line.

## Research questions

- **RQ1, portability:** can one Kotlin Multiplatform code base give the same results on every
  platform? Measured by the same tests and traces on JVM, JS, Wasm and native, on Linux, macOS and
  Windows.
- **RQ2, compatibility:** how much of the existing CPN Tools world runs unchanged? Measured by the
  share of a corpus of `.cpn` models that import, simulate and give the same state space as CPN
  Tools; by `.cpn` files that survive repeated import/export; and by `.cpn` files written by us that
  open in CPN Tools.
  A second part: is the Python-like syntax easier to use? Measured in a small user study.
- **RQ3, performance:** how fast is it compared to CPN Tools and across platforms?

## Success looks like

- A model downloaded from the web opens in our tool, and simulates with the same enabled bindings
  as in CPN Tools.
- Exporting it and importing it again, any number of times, changes nothing that matters.
- A model drawn in our app opens and runs in CPN Tools.
- `cpn statespace model.cpn` prints a report whose numbers match the CPN Tools standard report.
- All of the above is checked by automated tests in CI on three operating systems, not only by
  hand.

## Out of scope

- Being a drop-in replacement for every CPN Tools feature, for example monitors, performance
  reports and Access/CPN extensions. What we leave out is listed in
  [feature-spec.md](feature-spec.md#out-of-scope).
- Full Standard ML. We support the subset that models actually use (decision D4).
- A hosted multi-user service.
