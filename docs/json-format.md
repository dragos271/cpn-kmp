# Temporary JSON net format (I1)

A stop-gap so the CLI can run models before CPN ML inscriptions (I2) and the `.cpn` importer (I3)
exist. It will be kept for tests, but `.cpn` files are the real input format.
See [`examples/simple-protocol.json`](../examples/simple-protocol.json).

```jsonc
{
  "name": "Net name",
  "colsets": [ ... ],            // optional; UNIT, BOOL, INT, STRING are predefined
  "variables": { "n": "NO" },    // variable name -> colour set name
  "pages": [ ... ]               // in any order; subpages are resolved by name
}
```

## Colour sets

| JSON | CPN ML | Colours in JSON |
|------|--------|-----------------|
| `{"name": "U", "type": "unit"}` | `colset U = unit` | `null` |
| `{"name": "B", "type": "bool"}` | `colset B = bool` | `true`, `false` |
| `{"name": "N", "type": "int"}` | `colset N = int` | numbers |
| `{"name": "N", "type": "int", "range": [1, 10]}` | `colset N = int with 1..10` | numbers |
| `{"name": "S", "type": "string"}` | `colset S = string` | strings |
| `{"name": "E", "type": "enum", "values": ["a", "b"]}` | `colset E = with a \| b` | strings |
| `{"name": "P", "type": "product", "of": ["A", "B"]}` | `colset P = product A * B` | `[a, b]` |

Only pairs are supported for products.

## Pages

```jsonc
{
  "name": "Page",
  "places": [
    { "name": "P", "colset": "NO", "init": [1, 2, {"value": 3, "count": 5}] },
    { "name": "In", "colset": "NO", "port": "in" },          // port: in | out | inout
    { "name": "F", "colset": "NO", "fusion": "Shared" }      // fusion set name
  ],
  "transitions": [
    { "name": "T", "guard": <expr>, "in": [<arc>], "out": [<arc>], "both": [<arc>] }
  ],
  "substitutions": [
    { "name": "S", "subpage": "Sub", "assign": { "socketPlace": "portPlace" } }
  ]
}
```

An `<arc>` is `{"place": "P", "inscription": <inscription>}`. `both` is a CPN Tools double arc.

Places, transitions and substitutions may have `"x"` and `"y"`: their centre on the canvas, in pixels,
with y growing downwards. Elements without a position are placed automatically by the app.

## Arc inscriptions

| JSON | CPN ML | Binds variables? |
|------|--------|------------------|
| `"n"` or `{"var": "n", "count": 2}` | `` n `` / `` 2`n `` | yes |
| `{"pattern": ["n", "d"], "count": 1}` | `` 1`(n,d) `` | yes |
| `{"tokens": [colour, ...]}` (same form as `init`) | constant multiset; `[]` is `empty` | – |
| `{"expr": <expr>, "count": 1}` | `` 1`expr `` | no |
| `{"if": <expr>, "then": <inscription>, "else": <inscription>}` | `if ... then ... else ...` | no |
| `{"sum": [<inscription>, ...]}` | `a ++ b ++ ...` | no |

Variables that are not bound by `var` or `pattern` on an input arc must have a finite colour set
with at most 100 colours; the binding search enumerates them.

## Expressions (guards and `expr`)

- Literals: numbers, strings, `true`/`false`, `null` for `()`.
- `{"var": "n"}`
- `{"op": "+", "args": [a, b]}` with `+ - * div mod` (int), `^` (string concatenation),
  `= <> < <= > >=`, `andalso orelse`, and `{"op": "not", "args": [a]}`.
- `{"if": c, "then": a, "else": b}`
- `{"tuple": [a, b]}`

## Editable documents

The app saves models whose structure you changed in a second format, marked with
`"format": "cpn-document"`. It stores the layout and every inscription as CPN ML text, exactly what the
editor shows. Opening one compiles its CPN ML with the evaluator, so documents run in the app and with
`cpn simulate` like models in the format above. Models whose layout is all that changed are saved back in
the runnable format above.

Substitution transitions list their port assignments as `"ports": {"<socket place id>": "<port place id>"}`.
A socket without an entry is glued to the port of the same name.
