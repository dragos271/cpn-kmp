# CPN Tools `.cpn` files: how they are read

The importer is `cpn-io/.../cpntools/CpnToolsFormat.kt` (T3.1). It reads the XML that CPN Tools
1.5 to 4.x writes (DTD formats 5 and 6) into the editable model (`CpnDocument`), the same model the
app edits and `cpn simulate` runs. It is plain common Kotlin with its own small XML reader
(decision D7), so it works on JVM, JS, Wasm and native.

## What is read

| `.cpn` element | Becomes | Notes |
|---|---|---|
| `<page id>` + `<pageattr name>` | A page | |
| `<place>`: `text`, `type`, `initmark`, `posattr` | A place: name, colour set, initial marking, position | **y is negated**: CPN Tools' y axis points up, ours points down |
| `<port type="In|Out|I/O">` | The place's port type | |
| `<fusioninfo name>` | The place's fusion set | |
| `<trans>`: `text`, `cond`, `time`, `priority`, `code` | A transition with guard, time, priority and code | Time is ignored until T3.2; priority is ignored; code segments are reported as unsupported |
| `<subst subpage portsock="(port,socket)…">` | A substitution transition and its port/socket assignments | The pairs are (port place on the subpage, socket place on this page) |
| `<arc orientation>`: `PtoT`, `TtoP`, `BOTHDIR`, `Inhibitor`, `Reset` | An arc with direction and kind | Inhibitor and reset arcs are reported as unsupported |
| `<annot>`, `<bendpoint serial>` | The arc inscription; bend points in serial order | |
| `<globbox>`: `block`, `color`, `var`, `ml`, `globref`, `use` | The declarations text | See below |
| `monitorblock`, `Aux`, channels | Not read | Counted and reported in the import notes |

## Declarations

Our model holds the declarations as one CPN ML text; CPN Tools holds them as separate XML
elements. The importer builds the text as follows:

1. **Blocks** become comments: `(* Standard declarations *)`.
2. **`color` and `var`:** CPN Tools treats the XML structure as the declaration and `<layout>` as
   the text it shows. The importer uses the layout up to its first top-level `;`, keeping comments
   after it. If the layout does not start with `colset`/`var`, or there is none, the text is built
   from the structure, for example `<enum><id>a</id><id>b</id></enum>` becomes `with a | b`.
   Anything dropped is reported in the notes.
3. **`ml`:** the element's own code; a missing final `;` is added.
4. **`use "file.sml"`** becomes a comment, and is reported as unsupported.
5. **Order:** CPN Tools resolves declarations by dependency, so a file may declare a variable
   before its colour set. The importer moves declarations after the ones they use, keeping the
   file's order wherever it already works, and notes that it did.

## Normalisations

These are the places where an imported model differs from the file. They are listed here so the
round-trip tests (T3.1c) know what may change on the first import, and nothing else.

| What | Change |
|---|---|
| Coordinates | y negated |
| Declarations | Reordered by dependency; layout leftovers after the first `;` dropped; missing `;` added |
| Names with line breaks (`Packets\nTo Send`) | Kept in the model; the engine shows them on one line (`Packets To Send`) in traces and reports |
| Text encoding | Read as the declared ISO-8859-1 (or UTF-8), held as Unicode |
| Line endings | CRLF inside text is kept as read (3 corpus files use CRLF) |
| Graphics (colours, line widths, label positions, sizes, `Aux`) | Not in our model; kept in the parsed XML (`ImportedModel.source`) for export (T3.1b) |

## Import notes

`ModelFiles.open` returns `notes`: each thing in the file that the engine ignores or does not
support, by name and count. For example: "3 transition priorities are ignored" or "22 monitors
are ignored". `cpn simulate` prints them to stderr as `note: …`, and the app shows them after
opening the file. Nothing is dropped silently.
