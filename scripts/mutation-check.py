"""Deliberate-bug check ("mutation testing" by hand): does the test suite notice when the engine is broken?

Each mutation replaces one line of engine code with a plausible bug, runs the tests that should
catch it, and restores the file. CAUGHT means a test failed; SURVIVED means every test stayed green,
so a test is missing. See docs/ai-process.md, section 3.

    python3 scripts/mutation-check.py                 # all mutations
    python3 scripts/mutation-check.py M5,M6           # only these
"""
import subprocess, sys, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
only = sys.argv[1].split(',') if len(sys.argv) > 1 else None
L = "cpn-lang/src/commonMain/kotlin/no/hvl/cpn/lang/eval/"
M_ALL = [
 ("M1 `mod` uses truncating % instead of SML floor mod", L+"Library.kt",
  "(a as Number).toLong().mod((b as Number).toLong())", "((a as Number).toLong() % (b as Number).toLong())", [":cpn-lang:jvmTest"]),
 ("M2 Multiset `--` drops an element when 1 copy is left", "cpn-core/src/commonMain/kotlin/no/hvl/cpn/core/Multiset.kt",
  "if (remaining == 0) result.remove(element)", "if (remaining <= 1) result.remove(element)", [":cpn-core:jvmTest"]),
 ("M3 enabling rule ignores guards", "cpn-sim/src/commonMain/kotlin/no/hvl/cpn/sim/Semantics.kt",
  "if (!step.all { it.transition.guard.evaluate(it.binding) }) return false", "if (false && !step.all { it.transition.guard.evaluate(it.binding) }) return false", [":cpn-sim:jvmTest"]),
 ("M4 simulator always picks the first binding of the chosen transition", "cpn-sim/src/commonMain/kotlin/no/hvl/cpn/sim/Simulator.kt",
  "val (_, bindings) = candidates[random.nextInt(candidates.size)]\n        return record(bindings[random.nextInt(bindings.size)])", "val (_, bindings) = candidates[random.nextInt(candidates.size)]\n        return record(bindings[0])", [":cpn-sim:jvmTest", ":cpn-io:jvmTest", ":cpn-cli:jvmTest"]),
 ("M5 int tokens are not converted for intinf/time places", L+"Inscriptions.kt",
  "is LongColourSet -> if (value is Int) value.toLong() else value", "is LongColourSet -> value", [":cpn-lang:jvmTest", ":cpn-io:jvmTest"]),
 ("M6 guard given as a list passes if ANY condition holds", L+"Inscriptions.kt",
  "else -> (value as List<*>).all { it as Boolean }", "else -> (value as List<*>).any { it as Boolean }", [":cpn-lang:jvmTest", ":cpn-io:jvmTest", ":cpn-app:desktopTest"]),
 ("M7 .cpn import does not flip the y axis", "cpn-io/src/commonMain/kotlin/no/hvl/cpn/io/cpntools/CpnToolsFormat.kt",
  "-(p[\"y\"]?.toDoubleOrNull() ?: 0.0)", "(p[\"y\"]?.toDoubleOrNull() ?: 0.0)", [":cpn-io:jvmTest"]),
 ("M8 .cpn import reads portsock pairs as (socket, port)", "cpn-io/src/commonMain/kotlin/no/hvl/cpn/io/cpntools/CpnToolsFormat.kt",
  "?.associate { it.groupValues[2] to it.groupValues[1] }", "?.associate { it.groupValues[1] to it.groupValues[2] }", [":cpn-io:jvmTest"]),
 ("M9 .cpn export does not write moved positions", "cpn-io/src/commonMain/kotlin/no/hvl/cpn/io/cpntools/CpnToolsWriter.kt",
  "if (old != null && positionOf(old) == p) return this", "if (old != null) return this", [":cpn-io:jvmTest"]),
 ("M10 .cpn export writes portsock pairs as (socket, port)", "cpn-io/src/commonMain/kotlin/no/hvl/cpn/io/cpntools/CpnToolsWriter.kt",
  "port?.let { xmlId(it) to xmlId(socket) }", "port?.let { xmlId(socket) to xmlId(it) }", [":cpn-io:jvmTest"]),
 ("M11 timed tokens count as available before their timestamp", "cpn-sim/src/commonMain/kotlin/no/hvl/cpn/sim/Semantics.kt",
  "if (place.timed) time = maxOf(time, marking.timed(place).earliest(tokens) ?: return null)", "if (place.timed) marking.timed(place).earliest(tokens) ?: return null", [":cpn-io:jvmTest"]),
 ("M12 transition delays (time regions) are ignored", "cpn-sim/src/commonMain/kotlin/no/hvl/cpn/sim/Semantics.kt",
  "val delay = transition.delay.evaluate(binding)", "val delay = 0L", [":cpn-io:jvmTest"]),
 ("M13 the state space never recognises a marking it has seen", "cpn-statespace/src/commonMain/kotlin/no/hvl/cpn/statespace/StateSpace.kt",
  "index.getOrPut(marking) {", "index.getOrPut(marking.at(1_000_000L + markings.size)) {", [":cpn-statespace:jvmTest"]),
 ("M14 the state space records only the first enabled binding of a node", "cpn-statespace/src/commonMain/kotlin/no/hvl/cpn/statespace/StateSpace.kt",
  "for (element in enabled) {", "for (element in enabled.take(1)) {", [":cpn-statespace:jvmTest", ":cpn-io:jvmTest"]),
 ("M15 home markings are reported even with several terminal SCCs", "cpn-statespace/src/commonMain/kotlin/no/hvl/cpn/statespace/StandardReport.kt",
  "val home = if (terminal.size == 1) nodes.filter { scc.component[it - 1] == terminal.single() } else emptyList()",
  "val home = nodes.filter { scc.component[it - 1] in terminal }", [":cpn-statespace:jvmTest"]),
 ("M16 fairness: a transition counts as impartial if any cycle uses it", "cpn-statespace/src/commonMain/kotlin/no/hvl/cpn/statespace/StandardReport.kt",
  "if (!cycles.hasCycle) return Fairness.IMPARTIAL", "if (!cycles.hasCycle || enabled.any { it }) return Fairness.IMPARTIAL", [":cpn-statespace:jvmTest"]),
]
M = [m for m in M_ALL if only is None or m[0].split()[0] in only]
results = []
for name, f, old, new, tasks in M:
    p = root / f
    src = p.read_text()
    if src.count(old) != 1:
        results.append((name, "SKIPPED (pattern not found once)")); print(results[-1]); continue
    p.write_text(src.replace(old, new))
    try:
        r = subprocess.run(["./gradlew", "--offline", "-q", "--continue"] + tasks, cwd=root, capture_output=True, text=True)
        results.append((name, ("CAUGHT" if r.returncode != 0 else "SURVIVED (all tests still green)"), " ".join(tasks)))
    finally:
        p.write_text(src)
    print(results[-1], flush=True)
