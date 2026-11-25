# Performance reports (`cpn perf`)

A performance report answers planning questions about a timed model, such as "how long does a
specimen spend in the lab?" or "what changes with one more worker?", by running the model many times
and measuring it (F14). This is what CPN Tools does with monitors and its performance reports
(Jensen & Kristensen 2009, chapter 12).

```bash
cpn perf examples/perf/mm1.cpnd --monitors examples/perf/mm1-monitors.json \
    --replications 20 --until-time 100000 --warmup 5000
```

```
Performance report for MM1

20 replications per scenario, until time 100000, warm-up 5000, seeds 1..20.

  Monitor                                  Mean          95 % CI  Std dev (runs)  Min run  Max run
  Customers in system (L)                 1.022   0.9632 – 1.080          0.1253   0.7995    1.354
  Server utilisation                     0.5011  0.4891 – 0.5132          0.0257   0.4443   0.5489
  Throughput (departures per time unit)  0.0100  0.0099 – 0.0102          0.0003   0.0095   0.0107
  Time in system (W)                      101.7    96.53 – 106.9           11.06    81.85    130.7
```

This model is an M/M/1 queue, chosen because queueing theory gives the exact answers: L = 1,
utilisation 0.5, throughput 0.01 and W = 100. All four lie inside their 95 % intervals. The same
holds with arrival rates 0.005 and 0.015 (`--set lambda=0.005,0.01,0.015`), where theory gives
L = 0.333 and 3, W = 66.7 and 200. The middle case is an automated test (`PerformanceTest`); the
other two were checked by hand. The run above takes about one second.

## How it works

- Each **replication** is an independent simulation run with its own seed (`--seed`, `--seed + 1`,
  ...). The seed drives both the choice of binding and every random function in the inscriptions
  (`exponential`, `discrete`, `CS.ran()`), so a report can be reproduced exactly.
- A run stops at `--until-time` (model time) or after `--steps`. The first `--warmup` time units are
  not measured, so the empty start of the model does not bias the averages.
- Each **monitor** gives one value per run (its mean). The report shows the mean over the runs, a
  95 % confidence interval (Student's t), the standard deviation across runs, and the lowest and
  highest run.
- In a timed net, a marking size is averaged over time: a queue of 3 for 10 time units weighs
  10 times more than a queue of 5 for 1 time unit. In an untimed net, every step counts once.

## Monitors

A JSON file with a list of monitors:

```json
{
  "monitors": [
    { "name": "Customers in system", "type": "marking-size", "places": ["Queue", "Busy"] },
    { "name": "Throughput", "type": "occurrences", "transition": "Depart" },
    { "name": "Time in system", "type": "observe", "transition": "Depart", "expression": "intTime() - c" }
  ]
}
```

| Type | Measures | Value per run |
|---|---|---|
| `marking-size` | Tokens on one or more places together: a queue, the busy workers | Mean over time (min and max in the JSON output) |
| `occurrences` | How often a transition occurs | Rate per time unit (per step if untimed); the count is in the JSON output |
| `observe` | A CPN ML expression evaluated at each occurrence of a transition, with that occurrence's binding and the model time, e.g. how long the token being removed waited | Mean of the observations |

Places and transitions are named as in the standard report (`Page'Name 1`); `Page'Name` or just
`Name` work when they are unambiguous. Without `--monitors`, every place's size and every
transition's rate are measured.

## Comparing scenarios

`--set` with several values runs one scenario per value; several `--set` options give every
combination:

```bash
cpn perf lab.cpn --monitors lab-monitors.json --set WORKERS=2,3,4 --until-time 50000 -r 30
```

Each scenario gets its own table, so the effect of a change can be read off directly, with its
confidence interval.

## Output

`--format text` (default), `markdown` (for reports and the thesis), or `json` (every run's
statistics, for further analysis in Python or a spreadsheet). `-o FILE` writes to a file.
