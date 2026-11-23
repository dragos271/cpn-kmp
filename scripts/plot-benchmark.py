#!/usr/bin/env python3
"""Draws the simulation results of scripts/benchmark.py as SVG line charts, in the layout of
Garvik's Figures 6.2 and 6.4: seconds against millions of steps, one line per model size. Ours are
solid lines, Garvik's dashed. Plain Python, no plotting library needed.

Usage: python3 scripts/plot-benchmark.py benchmarks/results/NAME.json docs/figures [GARVIK.json]

With GARVIK.json (his tool measured on the same machine), his dashed lines are those measurements;
without it, the numbers read from his thesis.
"""

import json
import sys
from pathlib import Path

COLOURS = ["#2a6fdb", "#2e9e5b", "#d6453d"]  # blue, green, red, as in Garvik's figures
W, H = 640, 380
LEFT, RIGHT, TOP, BOTTOM = 60, 210, 40, 50


def chart(title, rows, path):
    plot_w, plot_h = W - LEFT - RIGHT, H - TOP - BOTTOM
    ymax = max(max(r["garvikSeconds"]) for r in rows)
    ymax = (int(ymax / 10) + 1) * 10
    xmax = 3.0

    def x(steps_m):
        return LEFT + steps_m / xmax * plot_w

    def y(seconds):
        return TOP + plot_h - seconds / ymax * plot_h

    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" font-family="Helvetica, Arial, sans-serif" font-size="12">',
        '<rect width="100%" height="100%" fill="white"/>',
        f'<text x="{LEFT}" y="22" font-size="14" font-weight="bold">{title}</text>',
    ]
    for i in range(0, ymax + 1, 10):
        out.append(f'<line x1="{LEFT}" x2="{LEFT + plot_w}" y1="{y(i):.1f}" y2="{y(i):.1f}" stroke="#ddd"/>')
        out.append(f'<text x="{LEFT - 8}" y="{y(i) + 4:.1f}" text-anchor="end">{i}</text>')
    for i in range(0, 7):
        v = i * 0.5
        out.append(f'<text x="{x(v):.1f}" y="{TOP + plot_h + 18}" text-anchor="middle">{v:g}</text>')
    out.append(f'<rect x="{LEFT}" y="{TOP}" width="{plot_w}" height="{plot_h}" fill="none" stroke="#333"/>')
    out.append(f'<text x="{LEFT + plot_w / 2}" y="{H - 12}" text-anchor="middle">Number of steps (millions)</text>')
    out.append(f'<text transform="translate(16 {TOP + plot_h / 2}) rotate(-90)" text-anchor="middle">Seconds</text>')

    legend_y = TOP + 10
    for colour, row in zip(COLOURS, rows):
        steps = [r["steps"] / 1e6 for r in row["ours"]]
        ours = [r["medianSeconds"] for r in row["ours"]]
        garvik = row["garvikSeconds"]
        for values, dash in ((ours, ""), (garvik, ' stroke-dasharray="5 4"')):
            points = " ".join(f"{x(s):.1f},{y(v):.1f}" for s, v in zip(steps, values))
            out.append(f'<polyline points="{points}" fill="none" stroke="{colour}" stroke-width="2"{dash}/>')
            for s, v in zip(steps, values):
                out.append(f'<circle cx="{x(s):.1f}" cy="{y(v):.1f}" r="3" fill="{colour if not dash else "white"}" stroke="{colour}"/>')
        lx = LEFT + plot_w + 14
        for label, dash in ((f"{row['size']}: ours", ""), (f"{row['size']}: Garvik", ' stroke-dasharray="5 4"')):
            out.append(f'<line x1="{lx}" x2="{lx + 24}" y1="{legend_y}" y2="{legend_y}" stroke="{colour}" stroke-width="2"{dash}/>')
            out.append(f'<text x="{lx + 30}" y="{legend_y + 4}">{label}</text>')
            legend_y += 18
        legend_y += 6
    out.append("</svg>")
    path.write_text("\n".join(out) + "\n")


def main():
    results = json.loads(Path(sys.argv[1]).read_text())
    folder = Path(sys.argv[2])
    if len(sys.argv) > 3:
        names = {"TPC": ("Two-phase commit", 2), "TPC3": ("Two-phase commit", 3), "TPC4": ("Two-phase commit", 4),
                 "DP": ("Dining philosophers", 5), "DP10": ("Dining philosophers", 10), "DP15": ("Dining philosophers", 15)}
        measured = {}
        for r in json.loads(Path(sys.argv[3]).read_text())["simulation"]:
            measured.setdefault(names[r["model"]], []).append(r["medianSeconds"])
        for row in results["simulation"]:
            row["garvikSeconds"] = measured[(row["model"], row["size"])]
    folder.mkdir(parents=True, exist_ok=True)
    for model, name, unit in (("Two-phase commit", "simulation-two-phase-commit.svg", "workers"), ("Dining philosophers", "simulation-dining-philosophers.svg", "philosophers")):
        rows = [dict(r, size=f"{r['size']} {unit}") for r in results["simulation"] if r["model"] == model]
        chart(f"{model}: time for N random steps", rows, folder / name)
        print(f"wrote {folder / name}")


if __name__ == "__main__":
    main()
