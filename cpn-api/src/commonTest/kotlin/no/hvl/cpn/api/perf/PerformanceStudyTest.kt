package no.hvl.cpn.api.perf

import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.sim.perf.RunLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The study code shared by `cpn perf` and the app's Performance tab. */
class PerformanceStudyTest {
    /** Arrivals every 10; service SERVICE. */
    private val queue =
        """
        {
          "format": "cpn-document", "version": 1, "name": "Queue",
          "declarations": "colset UNIT = unit timed; colset CUST = int timed; var c : CUST;\nval SERVICE = 6;",
          "pages": [{
            "id": "Q", "name": "Q",
            "places": [
              { "id": "Gen", "name": "Gen", "colset": "UNIT", "init": "1`()", "x": 0, "y": 0 },
              { "id": "Waiting", "name": "Waiting", "colset": "CUST", "init": "", "x": 100, "y": 0 },
              { "id": "Idle", "name": "Idle", "colset": "UNIT", "init": "1`()", "x": 200, "y": -100 },
              { "id": "Busy", "name": "Busy", "colset": "CUST", "init": "", "x": 300, "y": 0 }
            ],
            "transitions": [
              { "id": "Arrive", "name": "Arrive", "guard": "", "x": 50, "y": 0 },
              { "id": "Start", "name": "Start", "guard": "", "x": 200, "y": 0 },
              { "id": "Depart", "name": "Depart", "guard": "", "x": 400, "y": 0 }
            ],
            "arcs": [
              { "id": "a1", "place": "Gen", "transition": "Arrive", "direction": "in", "inscription": "()" },
              { "id": "a2", "place": "Gen", "transition": "Arrive", "direction": "out", "inscription": "() @+ 10" },
              { "id": "a3", "place": "Waiting", "transition": "Arrive", "direction": "out", "inscription": "intTime()" },
              { "id": "a4", "place": "Waiting", "transition": "Start", "direction": "in", "inscription": "c" },
              { "id": "a5", "place": "Idle", "transition": "Start", "direction": "in", "inscription": "()" },
              { "id": "a6", "place": "Busy", "transition": "Start", "direction": "out", "inscription": "c @+ SERVICE" },
              { "id": "a7", "place": "Busy", "transition": "Depart", "direction": "in", "inscription": "c" },
              { "id": "a8", "place": "Idle", "transition": "Depart", "direction": "out", "inscription": "()" }
            ]
          }]
        }
        """.trimIndent()

    private val monitors =
        """
        { "monitors": [
          { "name": "Utilisation", "type": "marking-size", "places": ["Busy"] },
          { "name": "W", "type": "observe", "transition": "Depart", "expression": "intTime () - c" },
          { "name": "Early W", "type": "observe", "transition": "Depart", "expression": "intTime () - c", "when": "c < 500" }
        ] }
        """.trimIndent()

    private val document = ModelFiles.open(queue, "Queue").document

    @Test
    fun scenariosSetDeclaredValuesAndReportProgress() {
        val progress = mutableListOf<Pair<Int, Int>>()
        val scenarios =
            document.study(
                MonitorFile.parse(monitors),
                RunLimits(untilTime = 1000),
                replications = 2,
                sweeps = listOf(ModelValues.parseSweep("SERVICE=4,6")),
            ) { done, total -> progress += done to total }
        assertEquals(listOf("SERVICE=4", "SERVICE=6"), scenarios.map { it.label })
        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)
        val sixes = scenarios[1].report.summaries.associateBy { it.monitor }
        assertEquals(0.6, sixes.getValue("Utilisation").mean, 1e-9)
        assertEquals(6.0, sixes.getValue("W").mean)
        // Only customers that arrived before 500: 50 of the 100 departures, all with W = 6.
        assertEquals(
            50,
            scenarios[1]
                .report.runs[0]
                .monitors[2]
                .count,
        )
        assertTrue("| W | 6.000 | 6.000 – 6.000 |" in formatReport(scenarios, 1, markdown = true))
    }

    @Test
    fun withoutMonitorsEveryPlaceAndTransitionIsMeasured() {
        val scenario = document.study(null, RunLimits(untilTime = 100), replications = 1).single()
        assertEquals(4 + 3, scenario.report.summaries.size)
        val defaults = MonitorFile.defaults(ModelFiles.open(queue).net!!)
        assertEquals(defaults, MonitorFile.parse(MonitorFile.format(defaults)), "the defaults round-trip through the file format")
    }

    @Test
    fun mistakesAreReportedForTheUser() {
        assertTrue("unknown type" in assertFailsWith<StudyException> { MonitorFile.parse("""{"monitors":[{"type":"x"}]}""") }.message!!)
        assertTrue("not a monitor file" in assertFailsWith<StudyException> { MonitorFile.parse("nonsense") }.message!!)
        val missing =
            assertFailsWith<StudyException> {
                document.study(null, RunLimits(untilTime = 10), 1, sweeps = listOf(ModelValues.parseSweep("WORKERS=2")))
            }
        assertTrue("no 'val WORKERS" in missing.message!!, missing.message)
        assertTrue("NAME=VALUE" in assertFailsWith<StudyException> { ModelValues.parseAssignment("W") }.message!!)
    }

    @Test
    fun valuesAreSetInEveryFormat() {
        assertEquals("val W = 3;", ModelValues.set("val W = 2;", "W", "3"))
        assertEquals(
            "colset X = int;\\nval W = 3;",
            ModelValues.set("colset X = int;\\nval W = 2;", "W", "3"),
            "after an escaped line break",
        )
        assertEquals(
            "<ml>val W = 3;<layout>val W = 3;</layout></ml>",
            ModelValues.set("<ml>val W = 2;<layout>val W = 2;</layout></ml>", "W", "3"),
        )
        assertFailsWith<StudyException>("NW is not W") { ModelValues.set("val NW = 2;", "W", "3") }
        assertEquals(
            listOf(
                listOf("A" to "1", "B" to "x"),
                listOf("A" to "1", "B" to "y"),
                listOf("A" to "2", "B" to "x"),
                listOf(
                    "A" to "2",
                    "B" to "y",
                ),
            ),
            ModelValues.scenarios(listOf("A" to listOf("1", "2"), "B" to listOf("x", "y"))),
        )
        assertFormatted()
    }

    private fun assertFormatted() {
        assertEquals("0", formatStatistic(0.0))
        assertEquals("1417", formatStatistic(1417.2))
        assertEquals("101.7", formatStatistic(101.73))
        assertEquals("0.0506", formatStatistic(0.05061))
        assertEquals("-2.500", formatStatistic(-2.5))
    }
}
