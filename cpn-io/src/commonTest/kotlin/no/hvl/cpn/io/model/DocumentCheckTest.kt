package no.hvl.cpn.io.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentCheckTest {
    private fun document(declarations: String) =
        CpnDocument(
            "M",
            declarations,
            listOf(
                PageDocument("P", "P", listOf(PlaceNode("P.A", "A", "PR", "", Point(0.0, 0.0))), emptyList(), emptyList()),
            ),
        )

    /**
     * As in the book's 5-30MultipleReceivers before T2.10: one mistake (here a type error in f) must
     * not also report the colour sets built from values (`1..n`), the colour sets built on those, the
     * places that use them, or the later uses of f.
     */
    @Test
    fun oneMistakeGivesOneProblem() {
        val problems =
            document(
                """
                val n = 3;
                colset R = index r with 1..n;
                fun f x = x + "a";
                colset PR = product R * R;
                val g = f 1;
                """.trimIndent(),
            ).check()
        assertEquals(1, problems.size, problems.toString())
        assertEquals(ProblemField.DECLARATIONS, problems.single().field)
        assertEquals("3:11", problems.single().message.substringBefore(": "), "at the + that cannot take a string")
    }

    /** The empty range and the type error are reported; PR, built on the broken E, is not. */
    @Test
    fun aRealColourSetProblemIsStillReported() {
        val problems = document("colset E = int with 5..1;\nfun f x = x + \"a\";\ncolset PR = product E * E;").check()
        assertEquals(2, problems.size, problems.toString())
    }
}
