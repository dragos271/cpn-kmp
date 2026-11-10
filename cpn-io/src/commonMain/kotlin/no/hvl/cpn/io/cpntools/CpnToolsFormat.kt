package no.hvl.cpn.io.cpntools

import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.PortType
import no.hvl.cpn.io.ModelFormatException
import no.hvl.cpn.io.model.ArcEdge
import no.hvl.cpn.io.model.ArcKind
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.PlaceNode
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.TransitionNode
import no.hvl.cpn.io.xml.Xml
import no.hvl.cpn.io.xml.XmlDocument
import no.hvl.cpn.io.xml.XmlElement
import no.hvl.cpn.io.xml.XmlException

/**
 * A `.cpn` file read into a [document]. [notes] say, by name and count, what the file has that the
 * engine ignores or does not support, so nothing is dropped silently (F4). [source] is the parsed
 * file, kept for writing it back (T3.1b).
 */
public class ImportedModel(
    public val document: CpnDocument,
    public val notes: List<String>,
    public val source: XmlDocument,
)

/**
 * Reads CPN Tools `.cpn` files (CPN Tools 1.5 to 4.x, DTD format 5 and 6) into the editable
 * [CpnDocument] (T3.1):
 *
 * - pages, places, transitions and arcs with their ids and positions. CPN Tools' y axis points up,
 *   ours down, so y is negated;
 * - names, colour sets, initial markings, guards, arc inscriptions, and the time, priority and code
 *   regions of transitions, all as the CPN ML text CPN Tools shows;
 * - declarations from the `globbox`, in order, with blocks as comments. Each declaration's text is
 *   its `<layout>` when the file has one, and is rebuilt from the XML structure otherwise (older
 *   CPN Tools versions write no layout for simple declarations);
 * - hierarchy: substitution transitions with their port/socket assignments, port places, fusion sets.
 */
public object CpnToolsFormat {
    /** True if [text] looks like a CPN Tools file rather than JSON. */
    public fun recognises(text: String): Boolean = text.trimStart().let { it.startsWith("<?xml") || it.startsWith("<workspaceElements") }

    /**
     * @param name the model's name, usually the file name without `.cpn`; CPN Tools files hold none.
     * @throws ModelFormatException if [text] is not a CPN Tools file.
     */
    public fun read(
        text: String,
        name: String,
    ): ImportedModel {
        val xml =
            try {
                Xml.parse(text)
            } catch (e: XmlException) {
                throw ModelFormatException("", "not valid XML: ${e.message}")
            }
        return fromXml(xml, name)
    }

    /** [read] for a file already parsed. */
    public fun fromXml(
        xml: XmlDocument,
        name: String,
    ): ImportedModel {
        if (xml.root.name != "workspaceElements") {
            throw ModelFormatException("", "expected a CPN Tools file (<workspaceElements>), found <${xml.root.name}>")
        }
        val net = xml.root.child("cpnet") ?: throw ModelFormatException("workspaceElements", "no <cpnet> element")
        return CpnToolsReader(net, name).read(xml)
    }

    /**
     * [document] as a CPN Tools 4 file (T3.1b). With [source], the file it was imported from, only
     * what the document changed is written and everything else (graphics, monitors, GUI state) is
     * kept; an unchanged document gives back the same bytes. Without it, a new file is generated.
     *
     * @throws ModelFormatException if the declarations do not parse: they must be split into CPN Tools' declaration elements.
     */
    public fun write(
        document: CpnDocument,
        source: XmlDocument? = null,
    ): String = CpnToolsWriter(document, source).write()
}

/** An ML comment `(* ... *)`, across lines; `[\s\S]` because `.` does not match newlines on every platform. */
private val COMMENT = Regex("""\(\*[\s\S]*?\*\)""")

private class CpnToolsReader(
    private val net: XmlElement,
    private val name: String,
) {
    private val notes = mutableListOf<String>()

    fun read(xml: XmlDocument): ImportedModel {
        val items = mutableListOf<Declaration>()
        net.child("globbox")?.let { declarationsOf(it, items) }
        val declarations = StringBuilder()
        for (item in dependencyOrder(items)) declarations.append(item.text).append('\n')
        val pages = net.children("page").map(::page)
        if (pages.isEmpty()) throw ModelFormatException("cpnet", "a model needs at least one page")
        noteIgnored(pages)
        return ImportedModel(CpnDocument(name, declarations.toString().trimEnd() + "\n", pages), notes, xml)
    }

    // ---------------------------------------------------------------------------------------------
    // Declarations

    /** One declaration of the `globbox`, with the names it declares and the names it uses. */
    private class Declaration(
        val text: String,
        val declares: Set<String>,
        val uses: Set<String>,
    )

    private fun declarationsOf(
        container: XmlElement,
        out: MutableList<Declaration>,
    ) {
        for (e in container.elements) {
            when (e.name) {
                "block" -> {
                    out += Declaration("(* ${comment(e.childText("id")?.trim().orEmpty())} *)", emptySet(), emptySet())
                    declarationsOf(e, out)
                }
                "color" -> {
                    val name = e.childText("id")?.trim().orEmpty()
                    val text = layoutOr(e, name, "colset") { colourSet(e) }
                    // Enumeration constants, index and union constructors are values other declarations use.
                    val constructors =
                        e.elements
                            .flatMap { kind ->
                                when (kind.name) {
                                    "enum", "index" -> kind.children("id")
                                    "union" -> kind.children("unionfield").mapNotNull { it.child("id") }
                                    "bool", "unit" -> kind.child("with")?.children("id").orEmpty()
                                    else -> emptyList()
                                }
                            }.map { it.text.trim() }
                    out += Declaration(text, setOf(name) + constructors, namesIn(text) - name)
                }
                // Variables are never used by other declarations; they only need their colour set.
                "var" -> {
                    val names = e.children("id").joinToString(", ") { it.text.trim() }
                    out +=
                        Declaration(
                            layoutOr(e, names, "var") { variable(e) },
                            emptySet(),
                            namesIn(e.child("type")?.childText("id").orEmpty()),
                        )
                }
                "ml" -> {
                    val code = e.text.trim().ifEmpty { e.childText("layout")?.trim().orEmpty() }
                    // Each <ml> is checked on its own in CPN Tools, so the final `;` may be missing.
                    val text = if (code.isEmpty() || withoutComments(code).trimEnd().endsWith(";")) code else "$code;"
                    out += Declaration(text, declaredBy(text), namesIn(text))
                }
                "globref" -> {
                    val text = layoutOr(e, "globref", "globref") { "globref ${e.childText("id")?.trim()} = ${e.childText("ml")?.trim()}" }
                    out += Declaration(text, declaredBy(text), namesIn(text))
                }
                "use" -> {
                    val file = e.childText("ml")?.trim().orEmpty()
                    out += Declaration("(* use ${comment(file)}; -- loading SML files is not supported *)", emptySet(), emptySet())
                    notes += "Declaration 'use $file' is not supported: the file it loads is not read"
                }
                "id", "layout" -> {}
                else -> notes += "Unknown declaration <${e.name}> ignored"
            }
        }
    }

    /**
     * The declaration as the user wrote it, if CPN Tools stored it, otherwise [fallback]. CPN Tools
     * treats the XML structure as the declaration and the layout as display text, which can hold
     * leftovers after the declaration's `;`; those are dropped (comments are kept) and noted.
     */
    private fun layoutOr(
        e: XmlElement,
        name: String,
        keyword: String,
        fallback: () -> String,
    ): String {
        val layout = e.childText("layout")?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback() + ";"
        if (!withoutComments(layout).trimStart().startsWith(keyword)) {
            notes += "The text shown for $name does not start with '$keyword'; it was rebuilt from the file's structure"
            return fallback() + ";"
        }
        val end = endOfFirstDeclaration(layout) ?: return "$layout;"
        val rest = layout.substring(end)
        val restComments = COMMENT.findAll(rest).joinToString(" ") { it.value }
        val stray = COMMENT.replace(rest, "").trim()
        // Only comments after the `;`: kept exactly as written.
        if (stray.isEmpty()) return layout
        notes += "Text '${stray.take(40)}' after the declaration of $name is ignored, as in CPN Tools"
        return (layout.substring(0, end) + if (restComments.isEmpty()) "" else " $restComments").trimEnd()
    }

    private fun withoutComments(text: String) = COMMENT.replace(text, " ")

    /** Index just after the first `;` outside comments and strings, or null if there is none. */
    private fun endOfFirstDeclaration(text: String): Int? {
        var depth = 0
        var k = 0
        while (k < text.length) {
            when {
                text.startsWith("(*", k) -> {
                    depth++
                    k += 2
                    continue
                }
                depth > 0 && text.startsWith("*)", k) -> {
                    depth--
                    k += 2
                    continue
                }
                depth == 0 && text[k] == '"' -> {
                    k++
                    while (k < text.length && text[k] != '"') k += if (text[k] == '\\') 2 else 1
                }
                depth == 0 && text[k] == ';' -> return k + 1
            }
            k++
        }
        return null
    }

    /** Identifiers in [text] outside comments and strings; for `PR.mult` both `PR.mult` and `PR`. */
    private fun namesIn(text: String): Set<String> {
        val code = Regex(""""(\\.|[^"\\])*"""").replace(COMMENT.replace(text, " "), " ")
        return Regex("""[A-Za-z][A-Za-z0-9_'.]*""")
            .findAll(code)
            .flatMap { m ->
                val name = m.value.trimEnd('.')
                if ('.' in name) sequenceOf(name, name.substringBefore('.')) else sequenceOf(name)
            }.toSet()
    }

    /** Names declared by SML declarations (`val`, `fun`, `datatype`, ...), found by keyword. */
    private fun declaredBy(text: String): Set<String> {
        val names = mutableSetOf<String>()
        val code = COMMENT.replace(text, " ")
        for (m in Regex(
            """\b(val|fun|and|datatype|type|exception|globref|structure|infix|infixr)\s+(rec\s+)?(op\s+)?([A-Za-z][\w']*|[!%&$#+\-/:<=>?@\\~`^|*]+)""",
        ).findAll(code)) {
            names += m.groupValues[4]
        }
        // Constructors of datatypes: `datatype t = A | B of int`.
        for (m in Regex("""(?:=|\|)\s*([A-Z][\w']*)""").findAll(code)) if ("datatype" in code) names += m.groupValues[1]
        return names
    }

    /**
     * [items] in an order where each declaration comes after the ones it uses. CPN Tools checks the
     * globbox by dependency, so files can declare a variable before its colour set; our evaluator
     * reads declarations in order. The file's order is kept wherever it already works.
     */
    private fun dependencyOrder(items: List<Declaration>): List<Declaration> {
        val declaredAt = HashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, item -> item.declares.forEach { declaredAt.getOrPut(it) { mutableListOf() } += i } }
        val after =
            items.mapIndexed { i, item ->
                item.uses
                    .mapNotNull { name ->
                        val at = declaredAt[name] ?: return@mapNotNull null
                        // The nearest declaration before this one, as SML would see it, else the first after it.
                        at.lastOrNull { it < i } ?: at.firstOrNull { it > i }
                    }.filter { it != i }
                    .toSet()
            }
        // Declarations that use each other (or seem to: names are found by text) form a group kept in
        // the file's order. If every other dependency already points backwards the order is kept, so
        // sorting declarations that are already sorted changes nothing (the round trips need that).
        val group = stronglyConnected(after)
        if (items.indices.all { i -> after[i].all { d -> d < i || group[d] == group[i] } }) return items
        val members = items.indices.groupBy { group[it] }
        val dependsOn = members.mapValues { (_, m) -> m.flatMap { after[it] }.map { group[it] }.toSet() - group[m.first()] }
        val placed = mutableSetOf<Int>()
        val order = mutableListOf<Int>()
        while (order.size < items.size) {
            // The group that can go next and comes first in the file.
            val next =
                members.keys
                    .filter {
                        it !in placed &&
                            dependsOn.getValue(it).all { d ->
                                d in placed
                            }
                    }.minBy { members.getValue(it).min() }
            placed += next
            order += members.getValue(next).sorted()
        }
        if (order !=
            items.indices.toList()
        ) {
            notes += "Declarations were reordered so each comes after the ones it uses, as CPN Tools allows"
        }
        return order.map { items[it] }
    }

    /** Tarjan's algorithm: for each node of the graph [edges] (node to the nodes it uses), the number of its component. */
    private fun stronglyConnected(edges: List<Set<Int>>): IntArray {
        val index = IntArray(edges.size) { -1 }
        val low = IntArray(edges.size)
        val component = IntArray(edges.size) { -1 }
        val stack = ArrayDeque<Int>()
        val onStack = BooleanArray(edges.size)
        var counter = 0
        var components = 0

        fun visit(v: Int) {
            index[v] = counter
            low[v] = counter++
            stack.addLast(v)
            onStack[v] = true
            for (w in edges[v]) {
                if (index[w] < 0) {
                    visit(w)
                    low[v] = minOf(low[v], low[w])
                } else if (onStack[w]) {
                    low[v] = minOf(low[v], index[w])
                }
            }
            if (low[v] == index[v]) {
                while (true) {
                    val w = stack.removeLast()
                    onStack[w] = false
                    component[w] = components
                    if (w == v) break
                }
                components++
            }
        }
        for (v in edges.indices) if (index[v] < 0) visit(v)
        return component
    }

    private fun colourSet(e: XmlElement): String {
        val id = e.childText("id")?.trim() ?: throw ModelFormatException("color", "colour set without a name (line ${e.line})")
        val timed = if (e.child("timed") != null) " timed" else ""
        val body = e.elements.firstOrNull { it.name !in setOf("id", "layout", "timed", "declare") } ?: return "colset $id = unit$timed"

        fun ids(x: XmlElement) = x.children("id").map { it.text.trim() }

        fun mls(x: XmlElement) = x.children("ml").map { it.text.trim() }

        fun range(x: XmlElement): String =
            x.child("with")?.let { w ->
                val bounds = mls(w)
                if (bounds.size == 2) " with ${bounds[0]}..${bounds[1]}" else ""
            } ?: ""
        val definition =
            when (body.name) {
                "unit", "bool" -> {
                    val with = body.child("with")?.let { ids(it) }.orEmpty()
                    when {
                        with.isEmpty() -> body.name
                        body.name == "bool" -> "bool with (${with.joinToString(", ")})"
                        else -> "unit with ${with.single()}"
                    }
                }
                "int", "intinf", "real", "string", "time" -> body.name + range(body)
                "enum" -> "with " + ids(body).joinToString(" | ")
                "index" -> {
                    val bounds = mls(body)
                    "index ${ids(body).single()} with ${bounds[0]}..${bounds[1]}"
                }
                "product" -> "product " + ids(body).joinToString(" * ")
                "record" ->
                    "record " +
                        body.children("recordfield").joinToString(" * ") {
                            val (label, type) = ids(it)
                            "$label : $type"
                        }
                "union" ->
                    "union " +
                        body.children("unionfield").joinToString(" + ") { f ->
                            val label = f.childText("id")?.trim()
                            val type = f.child("type")?.childText("id")?.trim()
                            if (type == null) "$label" else "$label : $type"
                        }
                "list" -> "list ${ids(body).single()}" + range(body)
                "alias" -> ids(body).single()
                "subset" -> {
                    val by = body.child("by")?.let { mls(it).single() }
                    val with = body.child("with")?.let { mls(it).single() }
                    "subset ${ids(body).single()}" + (by?.let { " by $it" } ?: with?.let { " with $it" } ?: "")
                }
                else -> throw ModelFormatException("color", "colour set $id has an unknown kind <${body.name}> (line ${body.line})")
            }
        return "colset $id = $definition$timed"
    }

    private fun variable(e: XmlElement): String {
        val type = e.child("type")?.childText("id")?.trim() ?: throw ModelFormatException("var", "variable without a type (line ${e.line})")
        return "var ${e.children("id").joinToString(", ") { it.text.trim() }} : $type"
    }

    /** CPN ML comments cannot contain `*)`. */
    private fun comment(text: String) = text.replace("*)", "* )")

    // ---------------------------------------------------------------------------------------------
    // Pages

    private fun page(e: XmlElement): PageDocument {
        val id = e["id"] ?: throw ModelFormatException("page", "page without an id (line ${e.line})")
        val name = e.child("pageattr")?.get("name") ?: id
        val places = e.children("place").map(::place)
        val transitions = e.children("trans").map(::transition)
        val nodes = (places.map { it.id } + transitions.map { it.id }).toSet()
        val arcs = e.children("arc").map { arc(it, nodes, name) }
        return PageDocument(id, name, places, transitions, arcs)
    }

    private fun place(e: XmlElement): PlaceNode =
        PlaceNode(
            id = e.requireId(),
            name = e.childText("text").orEmpty(),
            colourSet = region(e, "type"),
            initialMarking = region(e, "initmark"),
            position = position(e),
            port =
                e.child("port")?.let {
                    when (it["type"]) {
                        "In" -> PortType.IN
                        "Out" -> PortType.OUT
                        "I/O" -> PortType.IN_OUT
                        else -> throw ModelFormatException("port", "unknown port type '${it["type"]}' (line ${it.line})")
                    }
                },
            fusionSet = e.child("fusioninfo")?.get("name"),
        )

    private fun transition(e: XmlElement): TransitionNode {
        val subst = e.child("subst")
        // portsock="(port,socket)(port,socket)": port places on the subpage, sockets on this page.
        val assignments =
            subst
                ?.get("portsock")
                ?.let { Regex("""\(\s*([^,()\s]+)\s*,\s*([^,()\s]+)\s*\)""").findAll(it) }
                ?.associate { it.groupValues[2] to it.groupValues[1] }
                .orEmpty()
        return TransitionNode(
            id = e.requireId(),
            name = e.childText("text").orEmpty(),
            guard = region(e, "cond"),
            position = position(e),
            subpageId = subst?.get("subpage"),
            portAssignments = assignments,
            time = region(e, "time"),
            priority = region(e, "priority"),
            code = region(e, "code"),
        )
    }

    private fun arc(
        e: XmlElement,
        nodes: Set<String>,
        page: String,
    ): ArcEdge {
        val id = e.requireId()
        val place = e.child("placeend")?.get("idref") ?: throw ModelFormatException("arc", "arc $id has no place end")
        val transition = e.child("transend")?.get("idref") ?: throw ModelFormatException("arc", "arc $id has no transition end")
        for (end in listOf(place, transition)) {
            if (end !in nodes) throw ModelFormatException("arc", "arc $id on page $page refers to $end, which is not on that page")
        }
        val (direction, kind) =
            when (val o = e["orientation"]) {
                "PtoT" -> ArcDirection.PLACE_TO_TRANSITION to ArcKind.NORMAL
                "TtoP" -> ArcDirection.TRANSITION_TO_PLACE to ArcKind.NORMAL
                "BOTHDIR" -> ArcDirection.BIDIRECTIONAL to ArcKind.NORMAL
                "Inhibitor" -> ArcDirection.PLACE_TO_TRANSITION to ArcKind.INHIBITOR
                "Reset" -> ArcDirection.PLACE_TO_TRANSITION to ArcKind.RESET
                else -> throw ModelFormatException("arc", "arc $id has unknown orientation '$o'")
            }
        val bends =
            e
                .children("bendpoint")
                .sortedBy { it["serial"]?.toIntOrNull() ?: 0 }
                .map(::position)
        return ArcEdge(id, place, transition, direction, region(e, "annot"), bends, kind)
    }

    /** The text of an inscription region such as `<cond><text>n > 0</text></cond>`; empty if absent. */
    private fun region(
        e: XmlElement,
        name: String,
    ): String =
        e
            .child(name)
            ?.childText("text")
            ?.trim()
            .orEmpty()

    private fun position(e: XmlElement): Point {
        val p = e.child("posattr") ?: return Point(0.0, 0.0)
        // 0.0 - y, not -y: y = 0 must give 0.0, not -0.0, which Point equality tells apart.
        return Point(p["x"]?.toDoubleOrNull() ?: 0.0, 0.0 - (p["y"]?.toDoubleOrNull() ?: 0.0))
    }

    private fun XmlElement.requireId(): String = this["id"] ?: throw ModelFormatException(name, "<$name> without an id (line $line)")

    // ---------------------------------------------------------------------------------------------
    // What is kept but not used

    private fun noteIgnored(pages: List<PageDocument>) {
        val transitions = pages.flatMap { it.transitions }

        fun count(
            n: Int,
            what: String,
        ) {
            if (n > 0) notes += "$n $what"
        }
        count(transitions.count { it.priority.isNotEmpty() && it.priority != "P_NORMAL" }, "transition priorities are ignored")
        count(net.descendants("monitor").size, "monitors are ignored")
        count(net.children("page").sumOf { it.children("Aux").size }, "auxiliary graphics (text, shapes) are ignored")
        if (net.children("page").any { it.descendants("channel").any { c -> c.childText("text")?.isNotBlank() == true } }) {
            notes += "Synchronous channels are ignored"
        }
    }
}
