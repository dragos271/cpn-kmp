# Interoperability log: our `.cpn` files in other CPN tools

The supervisor meeting on 2026-11-09 asked for models made from scratch with this tool, exported to
`.cpn`, and run in another CPN tool. This file is where those checks are recorded. It is the manual
half of F5's definition of done; the automated half is in the tests, listed at the end.

## The models

All five were made with this tool. The sources are in the app's editable format (or the JSON format)
and the `.cpn` files were written by `cpn convert`:

```bash
cpn convert interop/models/hierarchical.cpnd --to cpn -o interop/cpn/hierarchical.cpn
```

| `.cpn` file | Source | What it exercises | Our result (any seed) |
|---|---|---|---|
| [countdown.cpn](../interop/cpn/countdown.cpn) | [countdown.cpnd](../interop/models/countdown.cpnd) | Integer range colour set, a guard, arcs with bend points | Dead after 5 steps; `Counter` = 1\`0 |
| [producer-consumer.cpn](../interop/cpn/producer-consumer.cpn) | [producer-consumer.cpnd](../interop/models/producer-consumer.cpnd) | Enumeration and `unit` colour sets, a variable bound from its colour set only, a guard with `<>` | Dead with 2\`plum left in `Buffer` |
| [hierarchical.cpn](../interop/cpn/hierarchical.cpn) | [hierarchical.cpnd](../interop/models/hierarchical.cpnd) | A substitution transition, In/Out ports, a fusion set across pages, a function | `Output` = 1\`2++1\`4++1\`6, `Log` = 1\`1++1\`2++1\`3 |
| [records-and-unions.cpn](../interop/cpn/records-and-unions.cpn) | [records-and-unions.cpnd](../interop/models/records-and-unions.cpnd) | Index, record and union colour sets, `ID.all()`, record selectors, union patterns | `Done` = 1\`id(1)++1\`id(2) |
| [simple-protocol.cpn](../interop/cpn/simple-protocol.cpn) | [examples/simple-protocol.json](../examples/simple-protocol.json) | The textbook simple protocol: products, a bidirectional arc, `if` in inscriptions | Dead; `DataReceived` = 1\`"COLOURED PETRI NET" |

## How to check a file in CPN Tools

You need CPN Tools 4.x: Windows, or Wine on macOS and Linux. For each file:

1. Open it with *Load net* in CPN Tools.
2. **Syntax check:** wait for CPN Tools to check the net. Note any red or orange elements and their
   error messages.
3. **Look:** do places, transitions, arcs and inscriptions appear where expected? Note anything
   misplaced.
4. **Simulate:** run to the end (*Play* → *Fast forward* with enough steps). Compare the final
   marking with "Our result" above.
5. **Round trip back:** save the net in CPN Tools as a new file, then run
   `cpn simulate <that file>`. It should still run here.
6. Record the result below with the date, the CPN Tools version and a screenshot (put it in
   `docs/interop/`).

Repeat with CPN IDE (cpnide.org) if it is available. It uses Access/CPN, a different loader, so it
is a second, independent check.

## Results

| Date | Tool and version | File | Opens | Syntax check | Same final marking | Saved file runs here | Notes |
|---|---|---|---|---|---|---|---|
| | CPN Tools 4.0.1 | countdown.cpn | | | | | not yet checked |
| | CPN Tools 4.0.1 | producer-consumer.cpn | | | | | not yet checked |
| | CPN Tools 4.0.1 | hierarchical.cpn | | | | | not yet checked |
| | CPN Tools 4.0.1 | records-and-unions.cpn | | | | | not yet checked |
| | CPN Tools 4.0.1 | simple-protocol.cpn | | | | | not yet checked |

None of these files has been opened in CPN Tools yet, because it does not run on the development
Mac. Until this table is filled in, F5 is only "done as far as our own tests can tell".

## What a failure would most likely be

These are the parts of a generated file most likely to trip CPN Tools, so check them first if a
file does not open:

- **GUI state.** We write CPN Tools 4.0.1's default options, one binder showing the top page, and
  no `IndexNode` (the index tree's open/closed state). If CPN Tools rejects the file, the
  `IndexNode` is the first suspect.
- **Declarations.** Each declaration is written twice, as XML structure (`<int><with>…`) and as
  layout text, as CPN Tools does. A colour set kind written slightly differently from CPN Tools
  would show up in the syntax check.
- **Label positions.** Inscriptions are placed at fixed offsets from their place or transition,
  and labels may overlap. This is cosmetic.

## Automated checks (run in CI)

- `InteropModelsTest`: each committed `.cpn` file is exactly what `cpn convert` writes for its
  source, and it runs with the same trace as its source for seeds 1, 2 and 3.
- `CpnToolsWriterTest.aModelMadeFromScratchBecomesACompleteCpnToolsFile` checks a model made from
  scratch:
  - it has every top-level element CPN Tools writes;
  - all ids are unique;
  - every reference (arc ends, fusion members, port/socket pairs, instances, binder sheets) points
    at an element.
