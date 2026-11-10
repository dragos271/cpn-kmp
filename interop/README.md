# Models for checking our `.cpn` files in other CPN tools

- `models/`: models made with this tool, in the app's editable format (`.cpnd`).
- `cpn/`: the same models written by `cpn convert --to cpn` (plus the textbook simple protocol from
  `examples/`). These are the files to open in CPN Tools.

How to check them, and where to record the results: [docs/interop-log.md](../docs/interop-log.md).
`InteropModelsTest` fails if a file in `cpn/` is not what the converter writes for its source.
