# Model corpus (T3.5)

These are real CPN Tools models collected from the web, to test the `.cpn` importer (T3.1), export
(T3.1b), round-trip stability (T3.1c), timed nets (T3.2) and the state space (T3.3, T3.4) against.

| Collection | Source | Models |
|---|---|---|
| `cpnbook/` | [lmkr/cpnbook](https://github.com/lmkr/cpnbook): models from Jensen & Kristensen, *Coloured Petri Nets* (2009) | 18 |
| `cpncourse/` | [lmkr/cpncourse](https://github.com/lmkr/cpncourse): Lars M. Kristensen's CPN Tools course | 11 |
| `cpn-js/` | [cpn-io/cpn-js](https://github.com/cpn-io/cpn-js): sample models shipped with CPN IDE | 10 |
| `cpnmcdctesting/` | [selabhvl/cpnmcdctesting](https://github.com/selabhvl/cpnmcdctesting): larger HVL case studies (MQTT, Paxos, …) | 10 |

## Files

- **`sources.json`** records, for every file:
  - the repository, the exact commit and the path;
  - the download URL and the date it was retrieved;
  - its SHA-256 checksum.

  The files are unchanged copies.
- **Licences:** none of the four source repositories declares one. The models are kept here, with
  their source, for research use in this thesis.
- **`manifest.json`** is generated from the files by `python3 scripts/corpus-manifest.py`. It adds
  the size of each model and the CPN features it uses.
- **Checking the files:** `python3 scripts/corpus-manifest.py --check` verifies that every file
  still matches its checksum.

## What the models use

<!-- table -->

models: 49, timed: 11, hierarchical: 20, inhibitor or reset arcs: 2, priorities: 8, monitors: 6

| Model | CPN Tools | Pages | P / T / A | Timed | Uses |
|---|---|---|---|---|---|
| `cpnbook/models/chapter10/10-19TimedStateSpaces.cpn` | 2.3.5 | 1 | 11 / 5 / 24 | yes | timedColourSets 3, transitionDelays 5, arcDelays 1, bothDirectionArcs 2 |
| `cpnbook/models/chapter10/10-1TimedProtocol.cpn` | 2.3.5 | 1 | 8 / 5 / 17 | yes | timedColourSets 4, transitionDelays 5, arcDelays 1, bothDirectionArcs 1 |
| `cpnbook/models/chapter12/12-1PerformanceProtocol.cpn` | 2.3.5 | 3 | 13 / 9 / 26 | yes | hierarchy 2, timedColourSets 3, transitionDelays 5, arcDelays 3, bothDirectionArcs 1, monitors 8 |
| `cpnbook/models/chapter12/12-2-6PerformanceProtocol.cpn` | 2.3.5 | 4 | 13 / 9 / 26 | yes | hierarchy 2, timedColourSets 3, transitionDelays 5, arcDelays 3, bothDirectionArcs 1, monitors 8 |
| `cpnbook/models/chapter12/12-7PerformanceProtocol.cpn` | 2.3.5 | 4 | 13 / 9 / 26 | yes | hierarchy 2, timedColourSets 3, transitionDelays 5, arcDelays 3, bothDirectionArcs 1, monitors 8 |
| `cpnbook/models/chapter2/2-10NondeterministicProtocol.cpn` | 2.3.5 | 1 | 8 / 5 / 16 |  | bothDirectionArcs 2 |
| `cpnbook/models/chapter2/2-1DeterministicProtocol.cpn` | 1.5.29 | 1 | 7 / 5 / 12 |  | — |
| `cpnbook/models/chapter3/3-18Functions.cpn` | 2.3.5 | 1 | 8 / 5 / 16 |  | bothDirectionArcs 2 |
| `cpnbook/models/chapter3/3-19Polymorphic.cpn` | 2.3.5 | 1 | 8 / 5 / 16 |  | bothDirectionArcs 2 |
| `cpnbook/models/chapter3/3-1UnionRecord.cpn` | 2.3.5 | 1 | 8 / 5 / 16 |  | bothDirectionArcs 2 |
| `cpnbook/models/chapter3/3-20Recursion.cpn` | 2.3.5 | 1 | 8 / 5 / 16 |  | bothDirectionArcs 2 |
| `cpnbook/models/chapter3/3-7Queues.cpn` | 2.3.5 | 1 | 8 / 5 / 24 |  | bothDirectionArcs 2 |
| `cpnbook/models/chapter5/5-19TwoReceivers.cpn` | 2.3.5 | 5 | 31 / 12 / 45 |  | hierarchy 8, bothDirectionArcs 3 |
| `cpnbook/models/chapter5/5-1HierarhicalProtocol.cpn` | 2.3.5 | 4 | 18 / 8 / 26 |  | hierarchy 3, bothDirectionArcs 4 |
| `cpnbook/models/chapter5/5-24TwoReceivers.cpn` | 2.3.5 | 5 | 21 / 10 / 33 |  | hierarchy 6, bothDirectionArcs 3 |
| `cpnbook/models/chapter5/5-30MultipleReceivers.cpn` | 2.3.5 | 5 | 21 / 9 / 29 |  | hierarchy 5, bothDirectionArcs 5 |
| `cpnbook/models/chapter5/5-8Instances.cpn` | 2.3.5 | 5 | 20 / 9 / 29 |  | hierarchy 5, bothDirectionArcs 3 |
| `cpnbook/models/chapter7/7-2LimitProtocol.cpn` | 2.3.5 | 1 | 9 / 5 / 20 |  | bothDirectionArcs 2 |
| `cpncourse/handson/CPNController.cpn` | 4.0.1 | 1 | 4 / 5 / 13 |  | bothDirectionArcs 1 |
| `cpncourse/handson/PTController.cpn` | 4.0.1 | 1 | 10 / 10 / 28 |  | bothDirectionArcs 2 |
| `cpncourse/models/lecture1-introduction.cpn` | 4.0.1 | 6 | 24 / 11 / 40 |  | hierarchy 3, fusion 2, bothDirectionArcs 1 |
| `cpncourse/models/lecture2-ptnets-short.cpn` | 4.0.1 | 1 | 0 / 0 / 0 |  | — |
| `cpncourse/models/lecture2-ptnets.cpn` | 4.0.1 | 12 | 71 / 31 / 81 |  | bothDirectionArcs 1, inhibitorArcs 1, resetArcs 1, priorities 1 |
| `cpncourse/models/lecture3-cpns.cpn` | 4.0.1 | 4 | 18 / 8 / 32 |  | bothDirectionArcs 1 |
| `cpncourse/models/lecture4-cpnmodules.cpn` | 4.0.1 | 6 | 24 / 11 / 40 |  | hierarchy 3, fusion 2, bothDirectionArcs 1 |
| `cpncourse/models/lecture6-statespaces-cyclic.cpn` | 4.0.1 | 4 | 10 / 6 / 24 |  | bothDirectionArcs 1 |
| `cpncourse/models/lecture6-statespaces-error.cpn` | 4.0.1 | 4 | 14 / 6 / 26 |  | bothDirectionArcs 1 |
| `cpncourse/models/lecture6-statespaces.cpn` | 4.0.1 | 4 | 14 / 6 / 26 |  | bothDirectionArcs 1 |
| `cpncourse/models/lecture7-erdp.cpn` | 4.0.0 | 14 | 60 / 28 / 92 |  | hierarchy 12, fusion 13, bothDirectionArcs 38 |
| `cpn-js/sample-cpn-models/DiningPhilosophers/DiningPhilosophers.cpn` | 2.0.0 | 1 | 3 / 2 / 6 |  | — |
| `cpn-js/sample-cpn-models/DistributedDataBase/DistributedDataBase.cpn` | 1.5.28 | 1 | 9 / 4 / 20 |  | — |
| `cpn-js/sample-cpn-models/HierarchicalProtocol/HierarchicalProtocol.cpn` | 2.0.0 | 4 | 25 / 10 / 37 |  | hierarchy 4, bothDirectionArcs 6 |
| `cpn-js/sample-cpn-models/ProtocolWithTimer/ProtocolWithTimer.cpn` | 1.5.33 | 1 | 12 / 7 / 26 |  | bothDirectionArcs 5 |
| `cpn-js/sample-cpn-models/QueueSystem/QueueSystem.cpn` | 2.3.12 | 4 | 9 / 6 / 16 | yes | hierarchy 2, timedColourSets 3, arcDelays 3, bothDirectionArcs 2, codeSegments 2, monitors 58 |
| `cpn-js/sample-cpn-models/ResourceAllocation/ResourceAllocation.cpn` | 1.5.33 | 1 | 8 / 5 / 19 |  | — |
| `cpn-js/sample-cpn-models/RingProtocol/RingProtocol.cpn` | 1.5.33 | 2 | 16 / 7 / 25 |  | hierarchy 4, bothDirectionArcs 7 |
| `cpn-js/sample-cpn-models/SimpleProtocol/SimpleProtocol.cpn` | 1.5.33 | 1 | 10 / 5 / 19 |  | bothDirectionArcs 3 |
| `cpn-js/sample-cpn-models/TEST/emptynet.cpn` | 4.0.1 | 1 | 0 / 0 / 0 |  | — |
| `cpn-js/sample-cpn-models/TEST/fuelstation-cpn-ide.cpn` | 4.0.1 | 3 | 14 / 10 / 28 | yes | hierarchy 2, timedColourSets 3, arcDelays 2, monitors 6 |
| `cpnmcdctesting/CPNmodels/CPNABS/OriginalModel/imperative_layerRefactor.cpn` | 4.0.1 | 18 | 131 / 42 / 266 |  | hierarchy 17, bothDirectionArcs 106 |
| `cpnmcdctesting/CPNmodels/DisCSPCPN/OriginalModel/DisCSP C 2.00.06 - ABT in-progress.cpn` | 4.0.1 | 5 | 15 / 17 / 59 |  | hierarchy 4, bothDirectionArcs 17, priorities 10, codeSegments 6, monitors 2 |
| `cpnmcdctesting/CPNmodels/MQTT/OriginalModel/MQTT.cpn` | 4.0.1 | 31 | 89 / 57 / 252 |  | hierarchy 24, bothDirectionArcs 49, priorities 2 |
| `cpnmcdctesting/CPNmodels/NatCPN/OriginalModel/NPP.cpn` | 4.0.1 | 14 | 67 / 14 / 116 | yes | fusion 66, timedColourSets 5, arcDelays 1, bothDirectionArcs 16, priorities 2, codeSegments 1 |
| `cpnmcdctesting/CPNmodels/NatCPN/OriginalModel/PC.cpn` | 4.0.1 | 11 | 34 / 11 / 55 | yes | fusion 33, timedColourSets 3, arcDelays 1, bothDirectionArcs 11, priorities 2, codeSegments 1 |
| `cpnmcdctesting/CPNmodels/NatCPN/OriginalModel/TIS.cpn` | 4.0.1 | 20 | 97 / 20 / 172 | yes | fusion 96, timedColourSets 3, arcDelays 1, bothDirectionArcs 20, priorities 2, codeSegments 1 |
| `cpnmcdctesting/CPNmodels/NatCPN/OriginalModel/VM.cpn` | 4.0.1 | 8 | 37 / 8 / 63 | yes | fusion 36, timedColourSets 5, arcDelays 1, bothDirectionArcs 9, priorities 2, codeSegments 1 |
| `cpnmcdctesting/CPNmodels/NikiT34-CPN-Model/Original-model/T34PIMwithZ (1).cpn` | 4.0.1 | 19 | 91 / 58 / 145 |  | fusion 91, bothDirectionArcs 5 |
| `cpnmcdctesting/CPNmodels/Paxos/OriginalModel/singlePaxos.cpn` | 4.0.1 | 27 | 132 / 52 / 204 |  | hierarchy 24, bothDirectionArcs 18, inhibitorArcs 1, priorities 5 |
| `cpnmcdctesting/example/tpc/twophasecommit.cpn` | 4.0.1 | 6 | 23 / 9 / 36 |  | hierarchy 3, bothDirectionArcs 1 |
