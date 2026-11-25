package no.hvl.cpn.io.python

import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.lang.eval.ExternalFunctionException
import no.hvl.cpn.sim.Simulator
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Python functions in models (F13), with a real Python. JVM only: other platforms cannot start
 * processes and use the CPN ML bodies. Needs `python3` (or `CPN_PYTHON`); CI runners have it.
 */
class PythonFunctionsTest {
    private val directory: File = createTempDirectory("cpn-python").toFile()

    /** A one-place model: Draw puts the external function's results on Out, Count runs out after 5 draws. */
    private fun model(
        declarations: String,
        draw: String,
    ): String =
        """
        {
          "format": "cpn-document", "version": 1, "name": "Draws",
          "declarations": ${jsonString("colset INT = int; colset N = int with 0..5; var n : N;\n$declarations")},
          "pages": [{
            "id": "Page", "name": "Page",
            "places": [
              { "id": "Left", "name": "Left", "colset": "N", "init": "1`5", "x": 0, "y": 0 },
              { "id": "Out", "name": "Out", "colset": "INT", "init": "", "x": 200, "y": 0 }
            ],
            "transitions": [{ "id": "Draw", "name": "Draw", "guard": "n > 0", "x": 100, "y": 0 }],
            "arcs": [
              { "id": "a1", "place": "Left", "transition": "Draw", "direction": "in", "inscription": "n" },
              { "id": "a2", "place": "Left", "transition": "Draw", "direction": "out", "inscription": "n - 1" },
              { "id": "a3", "place": "Out", "transition": "Draw", "direction": "out", "inscription": ${jsonString(draw)} }
            ]
          }]
        }
        """.trimIndent()

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun open(
        declarations: String,
        draw: String,
    ) = ModelFiles.open(model(declarations, draw), "Draws", directory.path)

    private fun outAfterRun(
        declarations: String,
        draw: String,
        seed: Long,
    ): String {
        val model = open(declarations, draw)
        val simulator = Simulator(assertNotNull(model.net), seed)
        val result = simulator.run(10)
        val out =
            simulator.flatNet.net.pages
                .single()
                .places
                .single { it.name.contains("Out") }
        return result.marking.tokensOf(out)
    }

    @Test
    fun annotationsAreFoundBeforeFunctions() {
        val text =
            """
            (* @python lab.py delay_section *)
            fun delaySection (s : INT) : int = 30;
            (* an ordinary comment *) fun other x = x;
            (*@python   /abs/path/x.py   f2*)fun g' () : int = 1;
            """.trimIndent()
        assertEquals(
            listOf(PythonBinding("delaySection", "lab.py", "delay_section"), PythonBinding("g'", "/abs/path/x.py", "f2")),
            PythonFunctions.annotations(text),
        )
    }

    @Test
    fun filesAreFoundNextToTheModel() {
        assertEquals("/models/lab.py", PythonFunctions.resolve("lab.py", "/models"))
        assertEquals("/data/lab.py", PythonFunctions.resolve("/data/lab.py", "/models"))
        assertEquals("C:\\data\\lab.py", PythonFunctions.resolve("C:\\data\\lab.py", "C:\\models"))
        assertEquals("lab.py", PythonFunctions.resolve("lab.py", null))
        assertEquals("/models", ModelFiles.directoryOf("/models/lab.cpn"))
        assertEquals(null, ModelFiles.directoryOf("lab.cpn"))
    }

    @Test
    fun pythonComputesTheValuesAndRepeatsThemForTheSameSeed() {
        File(directory, "draws.py").writeText(
            """
            import random
            def draw(n, scale):
                return n * scale + random.randint(0, 1000)
            """.trimIndent(),
        )
        val declarations = "(* @python draws.py draw *)\nfun draw (n : INT, scale : INT) : int = 0;"
        val a = outAfterRun(declarations, "draw (n, 10000)", seed = 1)
        assertEquals(a, outAfterRun(declarations, "draw (n, 10000)", seed = 1), "same seed, same draws")
        assertNotEquals(a, outAfterRun(declarations, "draw (n, 10000)", seed = 2), "another seed, other draws")
        // n * 10000 shows Python got both arguments: one draw for each n from 1 to 5.
        val values = a.split("++").map { it.substringAfter('`').trim().toInt() }
        assertEquals((1..5).toList(), values.map { it / 10000 }.sorted(), a)
    }

    @Test
    fun aChangedFileIsLoadedAgain() {
        val file = File(directory, "value.py")
        file.writeText("def value():\n    return 1\n")
        val declarations = "(* @python value.py value *)\nfun value () : int = 0;"
        assertEquals("5`1", outAfterRun(declarations, "value ()", 1))
        file.writeText("def value():\n    return 2\n")
        file.setLastModified(file.lastModified() + 2000)
        assertEquals("5`2", outAfterRun(declarations, "value ()", 1))
    }

    @Test
    fun mistakesAreReportedBeforeTheRun() {
        val missingFile = open("(* @python nowhere.py f *)\nfun f () : int = 0;", "f ()")
        val e1 = assertFailsWith<ExternalFunctionException> { missingFile.python!!.check() }
        assertTrue("FileNotFoundError" in e1.message!! && "nowhere.py" in e1.message!!, e1.message)

        File(directory, "empty.py").writeText("x = 1\n")
        val missingFunction = open("(* @python empty.py f *)\nfun f () : int = 0;", "f ()")
        val e2 = assertFailsWith<ExternalFunctionException> { missingFunction.python!!.check() }
        assertTrue("defines no function f" in e2.message!!, e2.message)
    }

    @Test
    fun printingInPythonDoesNotDisturbTheModel() {
        File(directory, "chatty.py").writeText("def chatty():\n    print('hello from Python')\n    return 3\n")
        assertEquals("5`3", outAfterRun("(* @python chatty.py chatty *)\nfun chatty () : int = 0;", "chatty ()", 1))
    }
}
