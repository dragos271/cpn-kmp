# Thesis outline

Working outline for *A Headless, Cross-Platform Coloured Petri Net Tool*. Each chapter lists the
material that already exists in this repository, so writing is mostly moving text into the
template. To be moved into the official template when it is added to `thesis/`.

The thesis delivers three things (from the supervisor meeting, 2026-11):
1. **the tool**: more features and better performance than the earlier HVL tools;
2. **a reflection on using AI** to build it;
3. **a real-world use case**: a pathology lab.

| # | Chapter | What goes in | Material that exists |
|---|---|---|---|
| 1 | Introduction | Problem, motivation, research questions, contributions, structure | [vision.md](../docs/vision.md) |
| 2 | Background | Coloured Petri Nets (definitions, timed nets, state spaces), CPN ML, CPN Tools and its `.cpn` format, Kotlin Multiplatform, AI coding assistants | [cpn-format.md](../docs/cpn-format.md); DAT300 material (to add) |
| 3 | Related work | Garvik (2022), Gjesdal (2023), other CPN tools (CPN IDE, Access/CPN, cpn-js), Petri net tools in general | [comparison.md](../docs/comparison.md) |
| 4 | Method | Spec-driven development with an AI assistant: vision, feature spec with definitions of done, task loop, how work is checked (tests, mutations, oracles) | [ai-process.md](../docs/ai-process.md) §1–4, [feature-spec.md](../docs/feature-spec.md) |
| 5 | Design and implementation | Architecture (modules), CPN ML front end (parser, type checker, interpreter), simulator, timed semantics, state space and report, `.cpn` import/export and round-trip stability, app, CLI | Source code and its KDoc, [cpn-format.md](../docs/cpn-format.md), [json-format.md](../docs/json-format.md), TASKS.md |
| 6 | Python external functions and performance reports | F13 and F14: design, data exchange, reproducibility, monitors, replications | [feature-spec.md](../docs/feature-spec.md) F13–F14 (to build) |
| 7 | Evaluation | **Compatibility**: corpus results, round trip, models opened in CPN Tools. **Correctness**: state spaces equal to CPN Tools (Garvik's 11 models), textbook models. **Performance**: Garvik's experiments repeated. **Features**: language matrix | [corpus-results.md](../docs/corpus-results.md), [reference/garvik-2022](../reference/garvik-2022/README.md), [performance.md](../docs/performance.md), [comparison.md](../docs/comparison.md), [interop-log.md](../docs/interop-log.md) |
| 8 | Case study: pathology lab | The process, the model, delays from data, scenarios, the report, validation with the lab | F15 (to build) |
| 9 | Reflection on AI-assisted development | How prompts were used, what was checked and how, defects found in AI-written code, when work was accepted, what it cost, threats to validity | [ai-process.md](../docs/ai-process.md) §2, §5; git history |
| 10 | Discussion and conclusion | Answers to RQ1–RQ4, limitations, future work | |
| A | Appendices | Standard reports, benchmark tables, language matrix, CLI reference | [reports/](../docs/reports/), [benchmarks/results](../benchmarks/results/) |

## Still needed

- The official thesis template (HVL/UiB master's thesis, LaTeX or Word) in `thesis/`.
- DAT300 material: which parts go into Background (chapter 2) and Method (chapter 4).
- Supervisor decisions listed in [feature-spec.md](../docs/feature-spec.md#open-questions-for-the-supervisor).
