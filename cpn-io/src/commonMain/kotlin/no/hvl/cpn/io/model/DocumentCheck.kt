package no.hvl.cpn.io.model

import no.hvl.cpn.lang.ast.ColsetDecl
import no.hvl.cpn.lang.ast.Declarations
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span
import no.hvl.cpn.lang.colour.ColourSetEnvironment
import no.hvl.cpn.lang.colour.ColsetDeclarationException
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.parse.ParseException
import no.hvl.cpn.lang.types.TypeChecker
import no.hvl.cpn.lang.types.TypeError

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
 * Checks a model the way CPN Tools' syntax check does, fast enough to run after every edit:
 *
 * - the declarations parse and type-check (T2.2, T2.4), and their colour sets can be built (T2.3);
 * - every place has a declared colour set;
 * - every inscription parses and has the right type: initial markings and arc inscriptions denote
 *   tokens of their place's colour set (a colour or a multiset), guards are booleans;
 * - every arc of an ordinary transition has an inscription.
 */
public fun CpnDocument.check(): List<Problem> {
    val problems = mutableListOf<Problem>()
    val parser = CpnMlParser()
    val checker = TypeChecker()

    // Declarations first: they declare the colour sets, variables and functions inscriptions use,
    // and their infix declarations change how inscriptions parse.
    val declarationsSource = SourceText("declarations", declarations)
    val parsed: Declarations? =
        try {
            parser.parseDeclarations(declarationsSource)
        } catch (e: ParseException) {
            problems += Problem(null, ProblemField.DECLARATIONS, located(e.source, e.span, e.reason))
            null
        }
    if (parsed != null) {
        val typeErrors = checker.checkDeclarations(parsed, declarationsSource)
        for (e in typeErrors) problems += Problem(null, ProblemField.DECLARATIONS, located(declarationsSource, e.span, e.reason))
        // Building the colour sets finds what types cannot: empty ranges, repeated constants.
        val colourSets = ColourSetEnvironment.standard(source = declarationsSource)
        for (decl in parsed.declarations.filterIsInstance<ColsetDecl>()) {
            if (typeErrors.any { it.span.start in decl.span.start until decl.span.end }) continue
            try {
                colourSets.declare(decl)
            } catch (e: ColsetDeclarationException) {
                problems += Problem(null, ProblemField.DECLARATIONS, located(declarationsSource, decl.span, e.reason))
            } catch (e: IllegalArgumentException) {
                // A colour set this one builds on was not built; that problem is already reported.
            }
        }
    }
    // With broken declarations every inscription would look wrong; check only their syntax then.
    val typeCheck = parsed != null

    fun expression(
        text: String,
        elementId: String,
        field: ProblemField,
    ): Pair<Expr, SourceText>? {
        val source = SourceText(elementId, text)
        return try {
            parser.parseExpression(source) to source
        } catch (e: ParseException) {
            problems += Problem(elementId, field, located(e.source, e.span, e.reason))
            null
        }
    }

    fun report(
        errors: List<TypeError>,
        elementId: String,
        field: ProblemField,
        source: SourceText,
    ) {
        for (e in errors) problems += Problem(elementId, field, located(source, e.span, e.reason))
    }

    for (page in pages) {
        for (place in page.places) {
            val colourKnown = place.colourSet.isNotBlank() && checker.environment.colourSet(place.colourSet) != null
            when {
                place.colourSet.isBlank() -> problems += Problem(place.id, ProblemField.COLOUR_SET, "A place needs a colour set")
                typeCheck && !colourKnown -> problems += Problem(place.id, ProblemField.COLOUR_SET, "Unknown colour set ${place.colourSet}")
            }
            if (place.initialMarking.isBlank()) continue
            val (expr, source) = expression(place.initialMarking, place.id, ProblemField.INITIAL_MARKING) ?: continue
            if (typeCheck &&
                colourKnown
            ) {
                report(checker.checkInitialMarking(expr, place.colourSet, source), place.id, ProblemField.INITIAL_MARKING, source)
            }
        }
        for (transition in page.transitions) {
            if (transition.guard.isBlank()) continue
            val (expr, source) = expression(transition.guard, transition.id, ProblemField.GUARD) ?: continue
            if (typeCheck) report(checker.checkGuard(expr, source), transition.id, ProblemField.GUARD, source)
        }
        for (arc in page.arcs) {
            // Socket arcs of substitution transitions carry no inscription.
            if (page.transition(arc.transitionId)?.subpageId != null) continue
            if (arc.inscription.isBlank()) {
                problems += Problem(arc.id, ProblemField.INSCRIPTION, "An arc needs an inscription")
                continue
            }
            val (expr, source) = expression(arc.inscription, arc.id, ProblemField.INSCRIPTION) ?: continue
            val colourSet = page.place(arc.placeId)?.colourSet
            if (typeCheck && colourSet != null && checker.environment.colourSet(colourSet) != null) {
                report(checker.checkArcInscription(expr, colourSet, source), arc.id, ProblemField.INSCRIPTION, source)
            }
        }
    }
    return problems
}

/** `line:column: reason`, the position within the field's own text. */
private fun located(
    source: SourceText,
    span: Span,
    reason: String,
): String = if (span.isNone) reason else "${source.position(span.start)}: $reason"
