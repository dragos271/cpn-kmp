# Headless CPN Tool

A headless, cross-platform Coloured Petri Net engine and CLI in Kotlin Multiplatform.
See [TASKS.md](TASKS.md) for the plan.

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
```

Models are in a temporary JSON format until `.cpn` import lands: see [docs/json-format.md](docs/json-format.md).
Exit code 1 means the model could not be read, loaded or simulated.

Requires JDK 17+ (CI uses 21). Native tests run only for the host OS; CI covers Linux, macOS and Windows.
