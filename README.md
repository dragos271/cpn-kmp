# Headless CPN Tool

A headless, cross-platform Coloured Petri Net engine and CLI in Kotlin Multiplatform.
See [TASKS.md](TASKS.md) for the plan, [docs/vision.md](docs/vision.md) and
[docs/feature-spec.md](docs/feature-spec.md) for what is built and when it is done, [docs/comparison.md](docs/comparison.md)
for how it compares to CPN Tools and earlier tools, and [docs/ai-process.md](docs/ai-process.md) for how AI-written
work is checked.

## Modules

| Module | Role |
|--------|------|
| `cpn-core` | Net model: places, transitions, arcs, colour sets, markings |
| `cpn-lang` | CPN ML and Python-like inscriptions: parser, types, evaluator |
| `cpn-sim` | Binding search, firing, simulation |
| `cpn-statespace` | Reachability graph, SCCs, reports |
| `cpn-io` | `.cpn` import, JSON format, report writers |
| `cpn-api` | Public facade for JVM / JS / Wasm / native (only place for platform code besides the CLI) |
| `cpn-cli` | `cpn` command: JVM launcher + native executables |
| `cpn-app` | Kotlin Multiplatform app (Compose): desktop + web, on top of the same engine |

Targets: JVM 17+, JS (Node), Wasm (Node), macOS arm64/x64, Linux x64, Windows x64 (mingw).

## Build

```bash
./gradlew allTests          # tests on all targets this host can run
./gradlew ktlintCheck       # lint
./gradlew ktlintFormat      # auto-fix lint
./gradlew :cpn-cli:linkReleaseExecutableMacosArm64   # native CLI → cpn-cli/build/bin/macosArm64/releaseExecutable/cpn.kexe
./gradlew :cpn-cli:installJvmDist                    # JVM CLI → cpn-cli/build/install/cpn-jvm/bin/cpn
```

## Run

```bash
./gradlew :cpn-cli:installJvmDist
cpn-cli/build/install/cpn-jvm/bin/cpn simulate examples/simple-protocol.json --seed 2026 -n 25
cpn-cli/build/install/cpn-jvm/bin/cpn simulate examples/simple-protocol.json --format json --no-trace
cpn-cli/build/install/cpn-jvm/bin/cpn simulate corpus/cpnbook/models/chapter2/2-1DeterministicProtocol.cpn --seed 1
cpn-cli/build/install/cpn-jvm/bin/cpn convert interop/models/hierarchical.cpnd --to cpn -o hierarchical.cpn   # for CPN Tools
cpn-cli/build/install/cpn-jvm/bin/cpn statespace corpus/cpnbook/models/chapter7/7-2LimitProtocol.cpn   # 13215 nodes
```

### The app (desktop and web)

```bash
./gradlew :cpn-app:run                          # desktop window (macOS, Windows, Linux)
./gradlew :cpn-app:wasmJsBrowserDevelopmentRun  # web version at http://localhost:8080
```

It opens the textbook simple protocol: fire enabled bindings, step, run with a seed, reset. For the same
seed it shows exactly the trace `cpn simulate` prints.

`cpn simulate` and the app open CPN Tools `.cpn` files (see [docs/cpn-format.md](docs/cpn-format.md) and
[docs/corpus-results.md](docs/corpus-results.md)), the editable document format and the JSON format of
[docs/json-format.md](docs/json-format.md).
Exit code 1 means the model could not be read, loaded or simulated.

Requires JDK 17+ (CI uses 21). Native tests run only for the host OS; CI covers Linux, macOS and Windows.
