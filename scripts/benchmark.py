#!/usr/bin/env python3
"""Repeats the performance evaluation of Garvik (2022), chapter 6, with our tool.

Two experiments, as in his thesis:
  1. Simulation (his Figures 6.2 and 6.4): time to execute 0.5 to 2.5 million random steps of the
     two-phase commit protocol (2, 3, 4 workers) and the dining philosophers (5, 10, 15).
  2. State space exploration (his Table 6.1): nodes, arcs and time for his 11 models.

Each measurement runs inside one JVM after a warm-up (`cpn bench`), and the median of --repeat runs
is reported. Garvik's numbers are included for comparison: read from his plots (simulation) or
copied from Table 6.1 and Appendix B (state space). His machine: MacBook Pro 16", M1 Max, 64 GB.

Usage:
  ./gradlew :cpn-cli:installJvmDist
  python3 scripts/benchmark.py [--repeat 3] [--quick] [--out benchmarks/results/NAME.json]

Writes the raw results as JSON and prints Markdown tables (pasted into docs/performance.md).
"""

import argparse
import datetime
import json
import platform
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CPN = ROOT / "cpn-cli/build/install/cpn-jvm/bin/cpn"
TPC = "reference/garvik-2022/two-phase-commit.cpnd"
DP = "corpus/cpn-js/sample-cpn-models/DiningPhilosophers/DiningPhilosophers.cpn"
DDB = "corpus/cpn-js/sample-cpn-models/DistributedDataBase/DistributedDataBase.cpn"
SP = "reference/garvik-2022/simple-protocol.cpnd"
RA = "reference/garvik-2022/resource-allocation.cpnd"

STEPS = [500_000, 1_000_000, 1_500_000, 2_000_000, 2_500_000]

# Seconds for 0.5M ... 2.5M steps, read from Garvik's Figure 6.2 (two-phase commit) and 6.4 (dining
# philosophers). Read off a plot, so accurate to about +-0.3 s.
GARVIK_SIMULATION = {
    ("Two-phase commit", 2): [4.0, 8.3, 11.7, 15.5, 19.5],
    ("Two-phase commit", 3): [5.3, 10.5, 15.8, 21.0, 26.3],
    ("Two-phase commit", 4): [6.7, 13.3, 19.9, 26.4, 33.1],
    ("Dining philosophers", 5): [3.1, 6.1, 9.2, 12.3, 15.4],
    ("Dining philosophers", 10): [6.2, 12.4, 18.6, 24.8, 31.3],
    ("Dining philosophers", 15): [10.2, 20.4, 30.6, 40.8, 51.0],
}

# (model, size label, file, --set, Garvik's nodes, arcs, his CPU ms, CPN Tools seconds from Appendix B)
STATE_SPACES = [
    ("Two-phase commit", "2 workers", TPC, ["W=2"], 43, 64, 31, 0),
    ("Two-phase commit", "3 workers", TPC, ["W=3"], 281, 512, 38, 0),
    ("Two-phase commit", "4 workers", TPC, ["W=4"], 2323, 4774, 99, 0),
    ("Dining philosophers", "5", DP, ["n=5"], 11, 30, 22, 0),
    ("Dining philosophers", "10", DP, ["n=10"], 123, 680, 28, 0),
    ("Dining philosophers", "15", DP, ["n=15"], 1364, 11310, 155, 1),
    ("Simple protocol", "", SP, [], 428, 1130, 44, 0),
    ("Resource allocation", "", RA, [], 13, 20, 26, 0),
    ("Distributed database", "3", DDB, ["n=3"], 28, 42, 27, 0),
    ("Distributed database", "6", DDB, ["n=6"], 1459, 4872, 114, 1),
    ("Distributed database", "9", DDB, ["n=9"], 59050, 314946, 12685, 1272),
]


def bench(file, sets, args):
    command = [str(CPN), "bench", str(ROOT / file), "--format", "json"] + args
    for s in sets:
        command += ["--set", s]
    out = subprocess.run(command, check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def machine():
    def sysctl(key):
        try:
            return subprocess.run(["sysctl", "-n", key], capture_output=True, text=True).stdout.strip()
        except OSError:
            return ""

    java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()
    memory = sysctl("hw.memsize")
    return {
        "cpu": sysctl("machdep.cpu.brand_string") or platform.processor(),
        "memoryGB": round(int(memory) / 2**30) if memory.isdigit() else None,
        "os": f"{platform.system()} {platform.release()}",
        "java": java[0] if java else "",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--quick", action="store_true", help="0.1M steps only, 1 run: checks the script works")
    parser.add_argument("--out", type=Path)
    a = parser.parse_args()
    if not CPN.exists():
        sys.exit(f"{CPN} not found: run ./gradlew :cpn-cli:installJvmDist first")

    steps = [100_000] if a.quick else STEPS
    repeat = 1 if a.quick else a.repeat
    results = {"date": datetime.date.today().isoformat(), "machine": machine(), "repeat": repeat, "simulation": [], "stateSpace": []}

    for (model, size), garvik in GARVIK_SIMULATION.items():
        file, key = (TPC, "W") if model == "Two-phase commit" else (DP, "n")
        print(f"simulation: {model} {size}", file=sys.stderr)
        r = bench(file, [f"{key}={size}"], ["--steps", ",".join(map(str, steps)), "--repeat", str(repeat)])
        results["simulation"].append({"model": model, "size": size, "garvikSeconds": garvik, "ours": r["simulation"]})

    for model, size, file, sets, nodes, arcs, garvik_ms, cpn_tools_s in STATE_SPACES:
        print(f"state space: {model} {size}", file=sys.stderr)
        r = bench(file, sets, ["--steps", "1000", "--warmup", "1000", "--repeat", str(max(repeat, 5)), "--state-space"])
        ss = r["stateSpace"]
        results["stateSpace"].append(
            {
                "model": model,
                "size": size,
                "nodes": ss["nodes"],
                "arcs": ss["arcs"],
                "matchesCpnTools": (ss["nodes"], ss["arcs"]) == (nodes, arcs),
                "oursMs": round(ss["medianSeconds"] * 1000, 1),
                "garvikMs": garvik_ms,
                "cpnToolsSeconds": cpn_tools_s,
            }
        )

    if a.out:
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(results, indent=1) + "\n")
        print(f"wrote {a.out}", file=sys.stderr)

    m = results["machine"]
    print(f"Machine: {m['cpu']}, {m['memoryGB']} GB, {m['os']}, {m['java']}; median of {repeat} runs.\n")
    print("| Model | Size | " + " | ".join(f"{s / 1e6:g}M steps" for s in steps) + " |")
    print("|---|---|" + "---|" * len(steps))
    for row in results["simulation"]:
        cells = []
        for i, run in enumerate(row["ours"]):
            garvik = row["garvikSeconds"][STEPS.index(run["steps"])] if run["steps"] in STEPS else None
            ours = run["medianSeconds"]
            cells.append(f"{ours:.1f} s vs {garvik:.1f} s (×{garvik / ours:.1f})" if garvik else f"{ours:.2f} s")
        print(f"| {row['model']} | {row['size']} | " + " | ".join(cells) + " |")
    print()
    print("| Model | Size | Nodes | Arcs | Same as CPN Tools | Ours (ms) | Garvik (ms) | CPN Tools (s) |")
    print("|---|---|---|---|---|---|---|---|")
    for r in results["stateSpace"]:
        same = "yes" if r["matchesCpnTools"] else "**no**"
        print(
            f"| {r['model']} | {r['size']} | {r['nodes']} | {r['arcs']} | {same} | {r['oursMs']} | {r['garvikMs']} | {r['cpnToolsSeconds']} |"
        )


if __name__ == "__main__":
    main()
