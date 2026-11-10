"""Builds corpus/manifest.json and the table in corpus/README.md from the .cpn files in corpus/ (T3.5).

For every model: where it came from (repository, commit, path, URL), its SHA-256, the CPN Tools
version that saved it, its size (pages, places, transitions, arcs) and which CPN features it uses
(hierarchy, fusion, time, inhibitor and reset arcs, priorities, monitors, code segments). The
features say which parts of the importer, the simulator and the timed semantics each model needs.

    python3 scripts/corpus-manifest.py           # rebuild manifest.json and README table
    python3 scripts/corpus-manifest.py --check   # verify every file against its recorded SHA-256

Sources are recorded in corpus/sources.json (written when the models were downloaded); none of the
source repositories declares a licence, so each entry says so.
"""
import hashlib
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORPUS = ROOT / "corpus"


def text(element):
    t = element.find("text") if element is not None else None
    return (t.text or "").strip() if t is not None else ""


def features(path):
    root = ET.parse(path).getroot()
    generator = root.find("generator")
    pages = root.findall(".//page")
    places = root.findall(".//page/place")
    transitions = root.findall(".//page/trans")
    arcs = root.findall(".//page/arc")
    colours = root.findall(".//globbox//color")
    orientation = [a.get("orientation") for a in arcs]
    timed_colsets = [c for c in colours if c.find("timed") is not None]
    transition_delays = [t for t in transitions if text(t.find("time"))]
    arc_delays = [a for a in arcs if "@+" in text(a.find("annot"))]
    used = {
        "hierarchy": sum(1 for t in transitions if t.find("subst") is not None),
        "fusion": len(root.findall(".//page/place/fusioninfo")),
        "timedColourSets": len(timed_colsets),
        "transitionDelays": len(transition_delays),
        "arcDelays": len(arc_delays),
        "bothDirectionArcs": orientation.count("BOTHDIR"),
        "inhibitorArcs": orientation.count("Inhibitor"),
        "resetArcs": orientation.count("Reset"),
        "priorities": sum(1 for t in transitions if text(t.find("priority"))),
        "codeSegments": sum(1 for t in transitions if text(t.find("code"))),
        "channels": sum(1 for t in transitions if text(t.find("channel"))),
        "monitors": len(root.findall(".//monitorblock//monitor")),
    }
    return {
        "cpnTools": generator.get("version") if generator is not None else None,
        "pages": len(pages),
        "places": len(places),
        "transitions": len(transitions),
        "arcs": len(arcs),
        "declarations": len(colours) + len(root.findall(".//globbox//var")) + len(root.findall(".//globbox//ml")),
        "timed": bool(timed_colsets or transition_delays or arc_delays),
        "uses": {k: v for k, v in used.items() if v},
    }


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    sources = json.loads((CORPUS / "sources.json").read_text())
    if "--check" in sys.argv:
        bad = [s["file"] for s in sources if sha256(CORPUS / s["file"]) != s["sha256"]]
        print(f"{len(sources) - len(bad)}/{len(sources)} files match their SHA-256")
        for f in bad:
            print("  changed:", f)
        sys.exit(1 if bad else 0)

    models = []
    for s in sources:
        models.append({**s, **features(CORPUS / s["file"])})
    (CORPUS / "manifest.json").write_text(json.dumps({"models": models}, indent=2) + "\n")

    rows = [
        "| Model | CPN Tools | Pages | P / T / A | Timed | Uses |",
        "|---|---|---|---|---|---|",
    ]
    for m in models:
        uses = ", ".join(f"{k} {v}" for k, v in m["uses"].items()) or "—"
        rows.append(
            f"| `{m['file']}` | {m['cpnTools']} | {m['pages']} | {m['places']} / {m['transitions']} / {m['arcs']} "
            f"| {'yes' if m['timed'] else ''} | {uses} |"
        )
    totals = {
        "models": len(models),
        "timed": sum(m["timed"] for m in models),
        "hierarchical": sum("hierarchy" in m["uses"] for m in models),
        "inhibitor or reset arcs": sum("inhibitorArcs" in m["uses"] or "resetArcs" in m["uses"] for m in models),
        "priorities": sum("priorities" in m["uses"] for m in models),
        "monitors": sum("monitors" in m["uses"] for m in models),
    }
    summary = ", ".join(f"{k}: {v}" for k, v in totals.items())
    readme = CORPUS / "README.md"
    head = readme.read_text().split("<!-- table -->")[0]
    readme.write_text(head + "<!-- table -->\n\n" + summary + "\n\n" + "\n".join(rows) + "\n")
    print(summary)


if __name__ == "__main__":
    main()
