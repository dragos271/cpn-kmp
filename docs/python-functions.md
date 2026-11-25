# Python functions in models (F13)

Inscriptions stay Standard ML, as in CPN Tools. A function whose values come from data, such as how
long a lab worker takes to section a specimen, can be written in Python instead, by whoever has
the data, and called from the model like any other function.

## Writing one

Put an annotation before an ordinary CPN ML function:

```sml
(* @python patholab.py delay_section *)
fun delaySection (w : INT, skill : INT) : INT = 300 div skill;
```

```python
# patholab.py, next to the model file
import random

def delay_section(worker, skill):
    return max(1, round(random.lognormvariate(3.5, 0.4)))
```

- **Our tool** calls `delay_section` in `patholab.py` whenever the model calls `delaySection`, e.g.
  in a transition's delay `@+ delaySection (w, skill)`.
- **CPN Tools** sees an ordinary comment and runs the CPN ML body, so the file still opens and runs
  there. The body is also what runs where Python cannot (in the browser), with a note saying so.
- The **result type is required** (`: INT` above): the result is checked against it, so a Python
  function that returns the wrong thing gives an error that names it, instead of a wrong model.
- The file name is relative to the model file's folder, or absolute.

## How values are passed

| CPN ML | Python |
|---|---|
| `int`, `intinf`, `real` | `int`, `float` |
| `string`, `bool` | `str`, `bool` |
| `unit` | `None` (and `fun f () ...` is called as `f()`) |
| Enumeration (`with a \| b`) | the constant's name, `"a"` |
| Index (`ph(3)`), union constructors | `{"tag": "ph", "value": 3}` |
| Products, lists, multisets | lists |
| Records | dicts |

`fun f (a, b)` is called as `f(a, b)`; curried functions `fun f a b` as `f(a, b)` too. A Python
function may return a tuple or a NumPy value where a list or a number is expected.

## Randomness and reproducibility

Every simulation run tells Python its seed: `random` and, if the functions use it, NumPy are
seeded with it. The same seed gives the same run, and replications in a performance report
(`cpn perf`) each get their own seed. Use `random` or `numpy.random`, not other generators.

## Practicalities

- **Python** is found as `python3` (`python` on Windows), or set `CPN_PYTHON` to its path, e.g. a
  virtual environment with pandas and SciPy installed.
- One Python process serves all models in a run of the program; it starts when a simulation first
  needs it. A changed `.py` file is loaded again at the start of the next simulation, so the
  functions can be edited while the app is open.
- `print` in Python goes to standard error, so it can be used for debugging.
- Mistakes are reported before a command-line run starts: a missing file, a missing function, or
  an exception while loading the file. Errors during the run name the function and its
  declaration, e.g. `declarations:12:5: external function delaySection: delay_section in
  patholab.py raised ZeroDivisionError: division by zero`.
- **Cost:** each call takes about 50–70 µs (a round trip to the Python process). Calls to Python
  are never cached, since they may be random. The pathology lab example calls Python once per
  sectioned case; two weeks of the lab, 10 times over, take about 4 seconds.

## Where it lives in the code

- `cpn-lang`: `ExternalFunctions` (the interpreter asks it when a `fun` is declared) and the value
  conversion (`External.kt`).
- `cpn-io`: `PythonFunctions` (annotations, the JSON protocol over standard input and output, the
  Python side of it), and the process launcher, which exists on the JVM only (command line and
  desktop app).
- Tests: `ExternalFunctionTest` (conversion and errors, every platform), `PythonFunctionsTest`
  (with a real Python, JVM).
