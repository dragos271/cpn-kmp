package no.hvl.cpn.io.model

import no.hvl.cpn.core.Arc
import no.hvl.cpn.core.ArcInscription
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.Page
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.Substitution
import no.hvl.cpn.core.Transition
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.colour.ColsetDeclarationException
import no.hvl.cpn.lang.colour.CpnColourSet
import no.hvl.cpn.lang.eval.CpnMlInscriptions
import no.hvl.cpn.lang.eval.EvaluationException
import no.hvl.cpn.lang.eval.Interpreter
import no.hvl.cpn.lang.eval.RandomSource
import no.hvl.cpn.lang.parse.CpnMlParser

/** The result of [compile]: a runnable net, or the problems that prevent one. */
public class CompiledModel(
    public val net: Net?,
    public val problems: List<Problem>,
)

/**
 * Compiles the document into an executable [Net] (T2.5): checks it ([check]), runs its declarations
 * in the CPN ML evaluator, evaluates the initial markings, and turns guards and arc inscriptions into
 * engine inscriptions evaluated on demand. Element ids are kept, so the net's places, transitions
 * and pages have the document's ids.
 *
 * Random functions in inscriptions draw from [random].
 */
public fun CpnDocument.compile(random: RandomSource = RandomSource.seeded(0)): CompiledModel {
    val problems = check()
    if (problems.isNotEmpty()) return CompiledModel(null, problems)
    return try {
        val net = DocumentCompiler(this, random).compile()
        val structural = net.validate()
        if (structural.isEmpty()) {
            CompiledModel(net, emptyList())
        } else {
            CompiledModel(
                null,
                structural.map {
                    Problem(null, ProblemField.STRUCTURE, it)
                },
            )
        }
    } catch (e: CompileProblem) {
        CompiledModel(null, listOf(e.problem))
    }
}

private class CompileProblem(
    val problem: Problem,
) : Exception(problem.message)

private class DocumentCompiler(
    private val document: CpnDocument,
    random: RandomSource,
) {
    private val parser = CpnMlParser()
    private val interpreter = Interpreter(random)
    private lateinit var inscriptions: CpnMlInscriptions
    private val pages = LinkedHashMap<String, Page>()
    private val building = mutableSetOf<String>()

    fun compile(): Net {
        val source = SourceText("declarations", document.declarations)
        attempt(null, ProblemField.DECLARATIONS) { interpreter.declare(parser.parseDeclarations(source), source) }
        inscriptions = CpnMlInscriptions(interpreter)
        for (page in document.pages) page(page.id, null)
        // Document order, so prime pages and listings come out as the user arranged them.
        return Net(document.name, document.pages.map { pages.getValue(it.id) }, inscriptions.variables.values.toList())
    }

    private fun page(
        id: String,
        usedBy: TransitionNode?,
    ): Page {
        pages[id]?.let { return it }
        val doc =
            document.page(id)
                ?: fail(usedBy?.id, ProblemField.STRUCTURE, "${usedBy?.name ?: "A substitution transition"} refers to a missing page '$id'")
        if (!building.add(id)) fail(usedBy?.id, ProblemField.STRUCTURE, "page ${doc.name} contains itself through ${usedBy?.name}")

        val places = doc.places.associate { it.id to place(it) }
        val transitions = doc.transitions.associate { it.id to transition(it, doc, places) }
        val arcs =
            doc.arcs.map { arc ->
                @Suppress("UNCHECKED_CAST")
                val place = places.getValue(arc.placeId) as Place<Any?>
                val transition = transitions.getValue(arc.transitionId)
                Arc(arc.id, place, transition, arc.direction, if (transition.isSubstitution) null else inscription(arc, place))
            }
        building -= id
        return Page(doc.id, doc.name, places.values.toList(), transitions.values.toList(), arcs).also { pages[id] = it }
    }

    private fun place(node: PlaceNode): Place<Any?> {
        val colourSet = interpreter.colourSets[node.colourSet]!!
        val marking =
            if (node.initialMarking.isBlank()) {
                Multiset.empty()
            } else {
                val (expr, source) = expression(node.initialMarking, node.id)
                attempt(node.id, ProblemField.INITIAL_MARKING) { inscriptions.multiset(expr, colourSet, source) }
            }
        return Place(node.id, node.name, colourSet, marking, node.port, node.fusionSet)
    }

    private fun transition(
        node: TransitionNode,
        page: PageDocument,
        places: Map<String, Place<*>>,
    ): Transition {
        val subpageId = node.subpageId
        if (subpageId != null) {
            val subpage = page(subpageId, node)
            val assignments =
                page.arcs.filter { it.transitionId == node.id }.associate { arc ->
                    val socket = places.getValue(arc.placeId)
                    val portId = node.portAssignments[arc.placeId]
                    val port =
                        subpage.places.firstOrNull { if (portId != null) it.id == portId else it.isPort && it.name == socket.name }
                            ?: fail(
                                node.id,
                                ProblemField.STRUCTURE,
                                "socket ${socket.name} of ${node.name} is not assigned to a port of ${subpage.name}",
                            )
                    socket to port
                }
            return Transition(node.id, node.name, substitution = Substitution(subpage, assignments))
        }
        val guard =
            if (node.guard.isBlank()) {
                Guard.TRUE
            } else {
                val (expr, source) = expression(node.guard, node.id)
                inscriptions.guard(expr, source)
            }
        return Transition(node.id, node.name, guard)
    }

    private fun inscription(
        arc: ArcEdge,
        place: Place<Any?>,
    ): ArcInscription<Any?> {
        val (expr, source) = expression(arc.inscription, arc.id)
        return inscriptions.arc(expr, place.colourSet as CpnColourSet, source)
    }

    /** Already parsed successfully by [check], with the same fixities. */
    private fun expression(
        text: String,
        elementId: String,
    ): Pair<Expr, SourceText> {
        val source = SourceText(elementId, text)
        return parser.parseExpression(source) to source
    }

    private fun <T> attempt(
        elementId: String?,
        field: ProblemField,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: EvaluationException) {
            fail(elementId, field, e.message ?: e.reason)
        } catch (e: ColsetDeclarationException) {
            fail(elementId, field, e.message ?: e.reason)
        }

    private fun fail(
        elementId: String?,
        field: ProblemField,
        message: String,
    ): Nothing = throw CompileProblem(Problem(elementId, field, message))
}
