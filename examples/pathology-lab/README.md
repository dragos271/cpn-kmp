# Pathology lab example (F15)

The supervisor's whiteboard sketch (meeting 2026-11-23) of how a CPN could describe a pathology lab,
made runnable. It is a **use case** that shows the tool's general features on a realistic process:
timed transitions, a shared worker pool, a delay computed by an external Python function, and a
performance report with what-if scenarios. It is not a model of a particular lab; anyone can adapt
it with their own process and data, in the app or in CPN Tools.

| File | What it is |
|---|---|
| `patholab.cpnd` | The model, for this tool (open it in the app, or use the `cpn` command) |
| `patholab.cpn` | The same model for CPN Tools (`cpn convert patholab.cpnd --to cpn -o patholab.cpn`) |
| `patholab.py` | `delay_section`, the external function from the sketch |
| `patholab-monitors.json` | What a performance report measures |

## The model, as sketched

Arrival → Registration → Grossing → Block → Processing (`@+24hrs`) → Embedding → Sectioning →
Slide → Staining → Scanned → Cases → Microscopy → Answered, with an **Order more** loop from
Microscopy back to Sectioning when more slides are needed (one case in ten).

- A case is a record `{id, arrived}`; time is in minutes.
- The **worker pool** holds workers as `(id, skill)`, `1`(23,5) ++ 1`(42,10)` as on the board.
  **Assign** takes a worker from the pool for a case at grossing or sectioning; the worker goes back
  to the pool when the step is done, after the step's delay.
- **Sectioning** takes `@+ delaySection (w, skill)`. In CPN Tools that is a standard ML function;
  here it is computed by `delay_section(worker, skill)` in `patholab.py` (the sketch's "external
  tool calling, e.g. Python"):

  ```sml
  (* @python patholab.py delay_section *)
  fun delaySection (w : INT, skill : INT) : INT = 300 div skill;
  ```

One interpretation of the sketch: there, workers are assigned to a station and stay there. With
two workers that can leave one station without anyone, so here a worker is assigned per case and
returns to the shared pool.

## A what-if question

How do turnaround and queues change if cases arrive every 40 minutes instead of every 30?

```bash
cpn perf examples/pathology-lab/patholab.cpnd -m examples/pathology-lab/patholab-monitors.json \
    -r 10 -t 20160 --warmup 2880 --set MEAN_GAP=30.0,40.0
```

Two weeks, the first two days not measured, 10 runs per scenario, about 4 seconds:

| Case every | Turnaround (95 % CI) | Waiting for grossing | Waiting for sectioning | Idle workers |
|---|---|---|---|---|
| 30 minutes | 2 160 min (1 928 – 2 392) | 12.2 | 10.7 | 0.04 |
| 40 minutes | 1 639 min (1 627 – 1 652) | 0.85 | 1.1 | 0.47 |

At one case every 30 minutes the two workers are almost never idle and the queues keep growing;
at 40 minutes the lab keeps up. "Idle workers" counts only workers whose time stamp has passed
(`"available": true` in the monitor file): a worker returned `@+60` is in the pool but busy.

The same study runs in the app: open `patholab.cpnd`, switch to **Analyse**, tab **Performance**,
load `patholab-monitors.json`, and enter `MEAN_GAP=30.0,40.0` as the scenario.

## Using it with real data

Replace `delay_section` with a function fitted to measured sectioning times (it can read files and
use pandas, SciPy or NumPy; set `CPN_PYTHON` to that Python), change the worker pool and the other
delays, or add Python functions for other steps the same way. See
[docs/python-functions.md](../../docs/python-functions.md) and
[docs/performance-reports.md](../../docs/performance-reports.md).
