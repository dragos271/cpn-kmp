# State space sizes from CPN Tools, via Garvik (2022)

Garvik's thesis, *A Compiler and Runtime Environment for Execution of CPN Models* (HVL, 2022),
evaluates his F# simulator on 11 models. Its Table 6.1 lists the state space size of each, and
Appendix B has the CPN Tools state space report for every one of them. Those numbers come from CPN
Tools itself, so they are an independent reference for our state space (F7, Level 4 in
[ai-process.md](../../docs/ai-process.md)).

Our engine gives the same number of nodes and arcs for all 11. The check runs in CI:
`cpn-io/src/jvmTest/.../CpnToolsReferenceTest.kt`.

| Model (Garvik's name) | CPN Tools nodes / arcs | Ours | Where our model comes from |
|---|---|---|---|
| Two-phase commit, 2 workers | 43 / 64 | 43 / 64 | [two-phase-commit.cpnd](two-phase-commit.cpnd), `W = 2` |
| Two-phase commit, 3 workers | 281 / 512 | 281 / 512 | the same, `W = 3` |
| Two-phase commit, 4 workers | 2323 / 4774 | 2323 / 4774 | the same, `W = 4` |
| Dining philosophers, 5 | 11 / 30 | 11 / 30 | corpus `cpn-js/.../DiningPhilosophers.cpn`, `n = 5` (as published) |
| Dining philosophers, 10 | 123 / 680 | 123 / 680 | the same, `n = 10` |
| Dining philosophers, 15 | 1364 / 11310 | 1364 / 11310 | the same, `n = 15` |
| Simple protocol | 428 / 1130 | 428 / 1130 | [simple-protocol.cpnd](simple-protocol.cpnd) |
| Resource allocation | 13 / 20 | 13 / 20 | [resource-allocation.cpnd](resource-allocation.cpnd) |
| Distributed database, 3 | 28 / 42 | 28 / 42 | corpus `cpn-js/.../DistributedDataBase.cpn`, `n = 3` |
| Distributed database, 6 | 1459 / 4872 | 1459 / 4872 | the same, `n = 6` |
| Distributed database, 9 | 59050 / 314946 | 59050 / 314946 | the same, `n = 9` |

CPN Tools took 1272 seconds for the last one, according to Appendix B; we take about 3 seconds on
the JVM (laptop, not a controlled benchmark).

## How the models were obtained

Garvik's `.cpn` files are not public (his repository, `smartoceanplatform/cpn-simulator`, is
private), so the models were rebuilt from the figures and listings in his thesis:

- **Two-phase commit**: rebuilt from Figure 2.2 and Listing 2.1 (one page, coordinator and
  workers). The corpus has a different two-phase commit model (`cpnmcdctesting`, hierarchical, runs
  once and stops), which has 45 nodes and 64 arcs.
- **Simple protocol**: Figure 6.5 is the textbook protocol with a `Limit` place (Jensen &
  Kristensen, Fig. 7.2) with 2 tokens instead of 3, four packets, and a `stop` packet that is not
  appended. Derived from the book's `7-2LimitProtocol.cpn` with those three changes. The corpus's
  `SimpleProtocol.cpn` has no limit, so its state space is infinite.
- **Resource allocation**: Figure 6.6 is Jensen's resource allocation example with
  `colset U = with P | Q` and no cycle counters. Derived from the corpus's `ResourceAllocation.cpn`
  by dropping the counter (`P = product U * I` becomes `U`). With the counter the state space is
  infinite.
- **Dining philosophers** and **distributed database**: the corpus models, with `val n` changed.

## What this found

The first run gave the right number of nodes for the two-phase commit but 5, 16 and 65 arcs too
few: exactly one per dead marking. `Receive Acknowledgements` takes the list `workers` from one
place and the same `workers`, as a multiset of workers, from `Acknowledge`. Our engine treated the
second arc as a pattern that binds `workers` to one token of `Acknowledge`, so the transition was
never enabled. Fixed in `CpnMlInscriptions`: a list variable on a place that does not hold lists
is evaluated, not matched. A unit test covers it (`InscriptionsTest`).
