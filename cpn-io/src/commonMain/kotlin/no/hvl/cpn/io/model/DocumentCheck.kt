package no.hvl.cpn.io.model

import no.hvl.cpn.lang.ast.ColsetDecl
import no.hvl.cpn.lang.ast.Declarations
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.VarDecl
import no.hvl.cpn.lang.colour.ColourSetEnvironment
import no.hvl.cpn.lang.colour.ColsetDeclarationException
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.parse.ParseException

/** Which part of an element a problem is about, so an editor can show it next to the right field. */
public enum class ProblemField { DECLARATIONS, COLOUR_SET, INITIAL_MARKING, GUARD, INSCRIPTION }

/**
 * A problem in a model, e.g. a syntax error in a guard. [elementId] is null for the declarations.
 * [message] starts with `line:column` when it has a position inside the field's text.
 */
public data class Problem(
    val elementId: String?,
    val field: ProblemField,
    val message: String,
)

/**
 * Checks what can be checked before the type checker (T2.4) exists: the declarations and every
 * inscription must parse as CPN ML, every place needs a declared colour set, and every arc of an
 * ordinary transition needs an inscription. Fast enough to run after every edit.
 */
public fun CpnDocument.check(): List<Problem> {
    val problems = mutableListOf<Problem>()
    val parser = CpnMlParser()

    // Declarations first: their infix declarations change how inscriptions parse.
    val declarationsSource = SourceText("declarations", declarations)
    val parsedDeclarations: Declarations? =
        try {
            parser.parseDeclarations(declarationsSource)
        } catch (e: ParseException) {
            problems += Problem(null, ProblemField.DECLARATIONS, located(e))
            null
        }

    val colourSets = ColourSetEnvironment.standard(source = declarationsSource)
    for (decl in parsedDeclarations?.declarations.orEmpty()) {
        when (decl) {
            is ColsetDecl ->
                try {
                    colourSets.declare(decl)
                } catch (e: ColsetDeclarationException) {
                    problems += Problem(null, ProblemField.DECLARATIONS, "${declarationsSource.position(decl.span.start)}: ${e.reason}")
                }
            is VarDecl ->
                if (colourSets[decl.colset.text] == null) {
                    problems +=
                        Problem(
                            null,
                            ProblemField.DECLARATIONS,
                            "${declarationsSource.position(decl.span.start)}: unknown colour set ${decl.colset.text}",
                        )
                }
            else -> Unit
        }
    }
    // If the declarations do not parse, do not also report every colour set as unknown.
    val coloursKnown = parsedDeclarations != null

    fun parse(
        text: String,
        elementId: String,
        field: ProblemField,
    ) {
        if (text.isBlank()) return
        try {
            parser.parseExpression(SourceText(elementId, text))
        } catch (e: ParseException) {
            problems += Problem(elementId, field, located(e))
        }
    }

    for (page in pages) {
        for (place in page.places) {
            when {
                place.colourSet.isBlank() -> problems += Problem(place.id, ProblemField.COLOUR_SET, "A place needs a colour set")
                coloursKnown && colourSets[place.colourSet] == null ->
                    problems += Problem(place.id, ProblemField.COLOUR_SET, "Unknown colour set ${place.colourSet}")
            }
            parse(place.initialMarking, place.id, ProblemField.INITIAL_MARKING)
        }
        for (transition in page.transitions) parse(transition.guard, transition.id, ProblemField.GUARD)
        for (arc in page.arcs) {
            // Socket arcs of substitution transitions carry no inscription.
            if (page.transition(arc.transitionId)?.subpageId != null) continue
            if (arc.inscription.isBlank()) {
                problems += Problem(arc.id, ProblemField.INSCRIPTION, "An arc needs an inscription")
            } else {
                parse(arc.inscription, arc.id, ProblemField.INSCRIPTION)
            }
        }
    }
    return problems
}

private fun located(e: ParseException): String = "${e.source.position(e.span.start)}: ${e.reason}"
