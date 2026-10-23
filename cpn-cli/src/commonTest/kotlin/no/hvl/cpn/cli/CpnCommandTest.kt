package no.hvl.cpn.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpnCommandTest {
    private val model =
        """
        {
          "name": "Countdown",
          "colsets": [{ "name": "N", "type": "int", "range": [0, 3] }],
          "variables": { "n": "N" },
          "pages": [{
            "name": "Main",
            "places": [{ "name": "P", "colset": "N", "init": [3] }],
            "transitions": [{
              "name": "Dec",
              "guard": { "op": ">", "args": [{ "var": "n" }, 0] },
              "in": [{ "place": "P", "inscription": "n" }],
              "out": [{ "place": "P", "inscription": { "expr": { "op": "-", "args": [{ "var": "n" }, 1] } } }]
            }]
          }]
        }
        """.trimIndent()

    private val files = mapOf("countdown.json" to model, "broken.json" to """{"name": "X", "pages": []}""")

    private fun cpn() = CpnCommand(readFile = { files[it] ?: throw NoSuchElementException("no such file") })

    @Test
    fun printsBannerWithoutSubcommand() {
        val result = cpn().test("")
        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.startsWith("cpn "))
    }

    @Test
    fun printsVersion() {
        assertTrue(cpn().test("--version").stdout.contains("0.1.0-SNAPSHOT"))
    }

    @Test
    fun simulatesToADeadMarking() {
        val result = cpn().test("simulate countdown.json --seed 7")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            """
            Seed: 7
            1 Main'Dec 1 <n=3>
            2 Main'Dec 1 <n=2>
            3 Main'Dec 1 <n=1>
            Stopped after 3 steps: dead marking
            Final marking:
              Main'P 1: 1`0
            """.trimIndent() + "\n",
            result.stdout,
        )
    }

    @Test
    fun stepLimitAndNoTrace() {
        val result = cpn().test("simulate countdown.json -n 2 --seed 1 --no-trace")
        assertEquals("Seed: 1\nStopped after 2 steps: step limit reached\nFinal marking:\n  Main'P 1: 1`1\n", result.stdout)
    }

    @Test
    fun jsonOutputForPipelines() {
        val result = cpn().test("simulate countdown.json --seed 7 --format json")
        val json = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("DEAD", json.getValue("stopReason").jsonPrimitive.content)
        assertEquals(3, json.getValue("stepsTaken").jsonPrimitive.int)
        val first =
            json
                .getValue("trace")
                .jsonArray
                .first()
                .jsonObject
        assertEquals("Main'Dec 1", first.getValue("transition").jsonPrimitive.content)
        assertEquals(
            "3",
            first
                .getValue("binding")
                .jsonObject
                .getValue("n")
                .jsonPrimitive.content,
        )
        assertEquals(
            "1`0",
            json
                .getValue("marking")
                .jsonObject
                .getValue("Main'P 1")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun randomSeedIsReportedSoTheRunCanBeRepeated() {
        val first = cpn().test("simulate countdown.json -n 1")
        val seed =
            first.stdout
                .lines()
                .first()
                .removePrefix("Seed: ")
        val again = cpn().test("simulate countdown.json -n 1 --seed $seed")
        assertEquals(first.stdout, again.stdout)
    }

    @Test
    fun modelErrorsExitWithCodeOne() {
        val missing = cpn().test("simulate nope.json")
        assertEquals(1, missing.statusCode)
        assertTrue("Cannot read 'nope.json'" in missing.stderr, missing.stderr)

        val broken = cpn().test("simulate broken.json")
        assertEquals(1, broken.statusCode)
        assertTrue("broken.json: pages: a net needs at least one page" in broken.stderr, broken.stderr)
    }

    @Test
    fun usageErrorsFail() {
        val negative = cpn().test("simulate countdown.json --steps -1")
        assertTrue(negative.statusCode != 0)
        assertTrue("--steps" in negative.stderr, negative.stderr)
        assertTrue(cpn().test("simulate").statusCode != 0)
    }
}
