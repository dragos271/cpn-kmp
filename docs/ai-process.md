# How this tool is built with an AI assistant, and how the work is checked

The code in this repository is written with Claude Code (Anthropic), working in the repository under
my direction. This file records:

1. how the work is planned (spec-driven development);
2. how prompts were used to direct the work;
3. how I check that what the AI produced is right;
4. when I accept a piece of work.

It is a living document. Where something goes wrong in a task, it is added to the list of defects caught.

## 1. Spec-driven development

The AI does not decide what to build. Three Markdown files do, and the AI is asked to work against
them:

| File | Answers | Changes when |
|---|---|---|
| [vision.md](vision.md) | Why the tool exists, the research questions, what success looks like | Rarely (supervisor meetings) |
| [feature-spec.md](feature-spec.md) | For each feature: scope (in/out), **definition of done**, status | When a feature is planned or finished |
| [TASKS.md](../TASKS.md) | The order of work: decisions D1–D9, tasks T1.1–T4.7 and A1–A8, each with a "Done:" line | After every task |

The loop for every task:

1. **Prompt:** I name one task, for example "Start T2.5 (evaluator)."
2. **Plan:** the AI reads the spec and the code, and shows a task list.
3. **Build:** the AI writes code and tests, and runs the build, the tests and the linter.
4. **Report:** the AI says what is done, what is simplified, and what is not verified.
5. **Check:** I check the work with the steps in section 3, against the DoD in feature-spec.md.
6. **Commit:** only when I ask ("Commit and push T2.5."). CI then runs on 3 OSes.
7. **Record:** the task's status in TASKS.md is updated.

Rules the AI follows:

- one task per prompt;
- it never commits or pushes unless asked;
- it writes every simplification into TASKS.md;
- it never claims something is verified unless it ran the check.

## 2. How prompts were used

The prompts were short and followed a few fixed patterns. The detail lives in TASKS.md and the spec
files, so a prompt only has to say *which* task to do, not *how* to do it.

| Kind of prompt | Purpose | Example |
|---|---|---|
| **Planning** | Turn the thesis proposal into a plan before writing any code | Give the proposal PDF and ask for the work to be organised into tasks, to be done one at a time |
| **Deciding** | Accept or change the proposed decisions (D1–D9) | "Defaults for D1–D8 are fine" |
| **Starting a task** | Name one task from TASKS.md | "Start T2.5 (evaluator)." |
| **Committing** | Commit and push a finished task, only after checking it | "Commit and push T2.5." |
| **Changing direction** | Correct the plan when it doesn't match the thesis requirements | Requiring a Kotlin Multiplatform app; asking for a draw.io-style editor |
| **Understanding** | Ask how to test, explain or demo the work, with no code changes | Asking what works and what doesn't before a supervisor meeting |
| **Supervisor feedback** | Pass on meeting notes, which become new tasks and spec entries | The 2026-11-09 meeting notes → this file, feature-spec.md, comparison.md |

What this means in practice:

- **One task per prompt.** The result of each prompt is checked (section 3) before the next task starts.
- **The AI does not decide the scope.** It proposes, and I accept, change or reject the proposal.
  Two examples: the app track (A1–A8) was added because I required it, and the meeting notes became
  new tasks.
- **Nothing is committed without an explicit prompt**, so every commit marks a point where I
  accepted the work.
- **Questions are prompts too.** Asking the AI to explain how to test or demo its work was part of
  checking it.

## 3. How I check work produced by the AI

"The tests are green" is not enough on its own: the AI wrote both the code and the tests, so a test
can be wrong in the same way as the code. So the checks below go from cheap to strong, and a task
must pass every level its DoD asks for.

### Level 1: it builds and the tests pass everywhere

```bash
./gradlew allTests ktlintCheck
```

After a push, CI must be green on Linux, macOS and Windows. This catches platform differences the
AI cannot see on my Mac. One example: on JavaScript, `2.0` and `2` are the same number, which
broke real-number printing. CI caught it.

### Level 2: read the tests, not only the code

For each new test file I check:

- **Does each test say what it expects?** A test with no assertion, or one that only checks that
  nothing crashed, proves little.
- **Are the expected values right?** I work out a few by hand or look them up in the textbook. The
  AI did get expectations wrong: it expected `sq x - 7 + x` with `x = 4` to be something other
  than 13. Here the test failed and was corrected, but a wrong expectation can just as easily make
  a wrong result pass.
- **Does it test the spec, or just what the code happens to do?** Expected values should come from
  the textbook, CPN Tools or the SML definition, not from running the code.

### Level 3: check that the tests can fail

A test that cannot fail is worth nothing. To check, I break the engine on purpose, one plausible bug
at a time, and see whether any test turns red. This is mutation testing, done by hand:

```bash
python3 scripts/mutation-check.py          # all mutations; each restores the code afterwards
```

Results on 2026-11-09:

| # | Deliberate bug | Result |
|---|---|---|
| M1 | `mod` truncates (like `%`) instead of rounding down like SML | Caught |
| M2 | Multiset `--` removes an element when 1 copy is left | Caught |
| M3 | The enabling rule ignores guards | Caught |
| M4 | The simulator always fires the first binding instead of a random one | Caught |
| M5 | Integer tokens are not converted for `intinf` and `time` places | **Survived**, so a test was added |
| M6 | A guard list `[a, b]` passes if *any* condition holds instead of *all* | **Survived**, so a test was added |
| M7 | The `.cpn` importer does not flip the y axis (added with T3.1) | Caught |
| M8 | The `.cpn` importer reads `portsock` pairs the wrong way round (added with T3.1) | Caught |
| M9 | The `.cpn` exporter does not write moved positions (added with T3.1b) | Caught |
| M10 | The `.cpn` exporter writes `portsock` pairs the wrong way round (added with T3.1b) | Caught |

M5 and M6 showed real gaps: the whole suite was green with a wrong guard rule. Both now have tests
in `cpn-lang/.../eval/InscriptionsTest.kt`, and re-running the script reports both as caught. Every
new feature adds its own mutations to the script.

### Level 4: compare with an independent oracle

Tests the AI wrote can share the AI's misunderstanding. An independent reference cannot:

- **Our own two implementations.** The JSON nets (built with Kotlin lambdas) and the same nets
  rebuilt from CPN ML text must give identical traces.
- **The textbook.** The expected enabled bindings for the Jensen & Kristensen examples come from
  the book.
- **CPN Tools.** This is the strongest check. Golden outputs from CPN Tools (T4.1) for the corpus
  (T3.5) will be compared automatically (T4.2), and `.cpn` files we export are opened in CPN Tools
  by hand, with each check recorded (F5).

### Level 5: use it myself

Each task ends with a two-minute demo that I run myself, from the CLI or in the app. If I can't
show it working, it isn't done.

## 4. When I accept a piece of work

I accept a task when all of these hold:

- [ ] Every point of its DoD in [feature-spec.md](feature-spec.md) is met, and I checked them myself.
- [ ] CI is green on all three OSes.
- [ ] I read the tests (level 2), and at least one deliberate bug in the new code is caught (level 3).
- [ ] Every simplification is written in TASKS.md, and I agree with it.
- [ ] I can explain the design in my own words, and I know where the code is.
- [ ] I ran the demo myself (level 5).

If one of these fails, the next prompt says which point and why, instead of starting a new task.

## 5. Defects found in AI-written code

This list is kept for the thesis's discussion of threats to validity.

| Found by | Defect | Fixed in |
|---|---|---|
| Unit test | Recursive `fun` declarations could not see themselves | T2.5 |
| Unit test | Error locations pointed at the wrong source text | T2.5 |
| CI (JS) | Real numbers printed as integers on JavaScript | T2.5 (`MlReal`) |
| Golden trace comparison | Enum constants written as strings by the JSON reader | T2.5 |
| Test review | Wrong expected values in tests | T2.5 |
| Mutation check | Guard lists accepted *any* instead of *all*: no test covered it (the code was right) | 2026-11-09 |
| Mutation check | No test for `intinf` token conversion | 2026-11-09 |
| Real models (corpus) | The importer and engine were too strict for real CPN Tools files: 9 kinds of gap (docs/corpus-results.md). None was visible with the hand-written test models | T3.1 |
| JS/Wasm build | The importer used JVM-only APIs (`RegexOption.DOT_MATCHES_ALL`, `IntArray.binarySearch`): the JVM tests passed | T3.1 |
| Test review | Wrong expected values in new tests (a line number; the S-expression form) | T3.1 |
| Round-trip test (corpus) | The parser read `with a \| b \| timed` as a constant called `timed`, so the colour set was untimed | T3.1b |
| CLI test (compact XML) | The writer re-indented parsed elements that had no whitespace, so the file changed | T3.1b |
| Round-trip test (corpus) | Untouched exports changed 17 files: CPN Tools 1.5 ids (`id65`), creation-ordered instances, fusion elements that disagree with place labels | T3.1b |
