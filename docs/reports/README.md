# Example state-space reports

Written by `cpn statespace <model> --report` (T3.4) for textbook models from the corpus. Each follows
the sections of CPN Tools' standard report.

| Report | Model | What to look for |
|---|---|---|
| [7-2LimitProtocol.txt](7-2LimitProtocol.txt) | Jensen & Kristensen, ch. 7: the protocol with a limit of 3 | 13,215 nodes, 52,784 arcs, 5,013 SCCs. The one dead marking is also the home marking (all data received). Send and Transmit Packet are impartial |
| [DiningPhilosophers.txt](DiningPhilosophers.txt) | CPN IDE's dining philosophers (5) | 11 nodes and 1 SCC: every marking is a home marking; both transitions are live and impartial |
| [2-1DeterministicProtocol.txt](2-1DeterministicProtocol.txt) | Jensen & Kristensen, fig. 2.1 | A chain of 31 nodes with no infinite occurrence sequences |

Regenerate them with:

```bash
cpn statespace corpus/cpnbook/models/chapter7/7-2LimitProtocol.cpn --report > docs/reports/7-2LimitProtocol.txt
```

`Secs` changes from run to run. Everything else should only change if the engine does.
