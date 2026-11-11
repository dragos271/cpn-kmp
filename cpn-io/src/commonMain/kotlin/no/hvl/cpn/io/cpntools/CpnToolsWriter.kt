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
import no.hvl.cpn.io.model.Size
import no.hvl.cpn.io.model.TransitionNode
import no.hvl.cpn.io.xml.Xml
import no.hvl.cpn.io.xml.XmlDocument
import no.hvl.cpn.io.xml.XmlElement
import no.hvl.cpn.io.xml.XmlNode
import no.hvl.cpn.io.xml.XmlText
import no.hvl.cpn.lang.ast.AliasColset
import no.hvl.cpn.lang.ast.BoolColset
import no.hvl.cpn.lang.ast.ColsetDecl
import no.hvl.cpn.lang.ast.ColsetSpec
import no.hvl.cpn.lang.ast.EnumColset
import no.hvl.cpn.lang.ast.GlobrefDecl
import no.hvl.cpn.lang.ast.IndexColset
import no.hvl.cpn.lang.ast.IntColset
import no.hvl.cpn.lang.ast.IntInfColset
import no.hvl.cpn.lang.ast.ListColset
import no.hvl.cpn.lang.ast.ProductColset
import no.hvl.cpn.lang.ast.RangeSpec
import no.hvl.cpn.lang.ast.RealColset
import no.hvl.cpn.lang.ast.RecordColset
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.StringColset
import no.hvl.cpn.lang.ast.SubsetColset
import no.hvl.cpn.lang.ast.TimeColset
import no.hvl.cpn.lang.ast.UnionColset
import no.hvl.cpn.lang.ast.UnitColset
import no.hvl.cpn.lang.ast.UseDecl
import no.hvl.cpn.lang.ast.VarDecl
import no.hvl.cpn.lang.parse.CpnMlParser
import no.hvl.cpn.lang.parse.ParseException
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Writing CpnDocuments as CPN Tools 4 `.cpn` files (T3.1b).
 *
 * With the file a model was imported from, every element the document still has is written onto its
 * original XML element, changing only what the document changed; graphics, monitors, options,
 * binders and other GUI state pass through. An unchanged model is written back byte for byte. Without
 * one (a model made in the app), a complete file is generated in the shape CPN Tools 4.0.1 writes.
 */

private const val TOOL_VERSION = "4.0.1"

internal class CpnToolsWriter(
    private val document: CpnDocument,
    private val source: XmlDocument?,
) {
    private val usedIds = mutableSetOf<String>()
    private var nextId = 1_000_000

    /** Document id to XML id: ids from the file and ids in CPN Tools' style (`ID42`, `id42`) are kept; others (`Top`, `Page1.place3` from the app) get fresh ones. */
    private val xmlIds = HashMap<String, String>()

    init {
        source?.root?.let { collectIds(it) }
        val all = document.pages.flatMap { p -> listOf(p.id) + p.places.map { it.id } + p.transitions.map { it.id } + p.arcs.map { it.id } }
        for (id in all) {
            if ((id in usedIds || Regex("""[Ii][Dd][A-Za-z0-9_]*""").matches(id)) && id !in xmlIds.values) {
                xmlIds[id] = id
                usedIds += id
            }
        }
        for (id in all) if (id !in xmlIds) xmlIds[id] = fresh()
    }

    private fun collectIds(e: XmlElement) {
        e["id"]?.let { id ->
            usedIds += id
            id.removePrefix("ID").toIntOrNull()?.let { if (it >= nextId) nextId = it + 1 }
        }
        e.elements.forEach(::collectIds)
    }

    private fun fresh(): String {
        while ("ID$nextId" in usedIds) nextId++
        return "ID${nextId++}".also { usedIds += it }
    }

    private fun xmlId(id: String) = xmlIds.getValue(id)

    /** The model as imported from [source]: parts of the document still equal to it keep their XML as it was. */
    private val imported: CpnDocument? by lazy { source?.let { CpnToolsFormat.fromXml(it, document.name).document } }

    private fun fusionMembership(d: CpnDocument) = d.pages.flatMap { p -> p.places.map { it.id to it.fusionSet } }

    private fun hierarchy(d: CpnDocument) = d.pages.map { p -> p.id to p.transitions.map { it.id to it.subpageId } }

    fun write(): String {
        val base = source ?: skeleton()
        val cpnet = base.root.child("cpnet") ?: throw ModelFormatException("cpnet", "the source file has no <cpnet>")
        val oldPages = cpnet.children("page").associateBy { it["id"] }
        val pages = document.pages.map { page(it, oldPages[xmlId(it.id)]) }

        var net = cpnet
        net = net.withChild("globbox", globbox(cpnet.child("globbox")), before = setOf("page"))
        net = net.replaceAll("page", pages, before = setOf("fusion", "instances", "options", "binders", "monitorblock", "IndexNode"))
        net =
            net.replaceAll(
                "fusion",
                fusions(cpnet.children("fusion")),
                before = setOf("instances", "options", "binders", "monitorblock", "IndexNode"),
            )
        val oldInstances = cpnet.child("instances")
        // Unchanged hierarchy: the file keeps its instances, even none (files from other tools may lack them).
        val unchanged = source != null && imported?.let(::hierarchy) == hierarchy(document)
        val instances = if (unchanged) oldInstances else instances(oldInstances)
        if (instances !=
            null
        ) {
            net = net.withChild("instances", instances, before = setOf("options", "binders", "monitorblock", "IndexNode"))
        }
        if (instances != null && instances !== oldInstances) {
            // The binders show page instances by id; fresh instances need fresh binders.
            net = net.withChild("binders", binders(instances), before = setOf("monitorblock", "IndexNode"))
            net = net.remove("IndexNode")
        }
        if (source == null) {
            // A new file gets CPN Tools' defaults; a file that had none keeps having none.
            net = net.withChild("options", defaultOptions(), before = setOf("binders", "monitorblock", "IndexNode"))
            net = net.withChild("monitorblock", element("monitorblock", "name" to "Monitors"), before = setOf("IndexNode"))
        }
        val root = base.root.withChild("cpnet", net, before = emptySet())
        return Xml.write(base.copy(root = root))
    }

    // ---------------------------------------------------------------------------------------------
    // Declarations

    private fun globbox(old: XmlElement?): XmlElement {
        if (old != null && imported?.declarations == document.declarations) {
            return old
        }
        val text = SourceText("declarations", document.declarations)
        val parsed =
            try {
                CpnMlParser().parseDeclarations(text)
            } catch (e: ParseException) {
                throw ModelFormatException("declarations", "cannot be written to a .cpn file: ${e.message}")
            }
        val children = mutableListOf<XmlElement>()
        var segmentStart = 0
        val decls = parsed.declarations
        decls.forEachIndexed { i, decl ->
            var end = decl.span.end
            // Take the `;` after the declaration, and for the last one any comments after it.
            // ... and a comment on the same line after it, which belongs to it.
            val semicolon = Regex("""^\s*;([ \t]*\(\*[^\n]*?\*\))*""").find(document.declarations.substring(end))
            if (semicolon != null) end += semicolon.value.length
            if (i == decls.lastIndex) end = document.declarations.length
            val layout =
                document.declarations.substring(segmentStart, end).trim().let {
                    if (it.endsWith(";") ||
                        it.endsWith(")")
                    ) {
                        it
                    } else {
                        "$it;"
                    }
                }
            segmentStart = end

            fun slice(span: no.hvl.cpn.lang.ast.Span) = text.slice(span).trim()
            children +=
                when (decl) {
                    is ColsetDecl ->
                        element(
                            "color",
                            "id" to fresh(),
                            children =
                                listOfNotNull(
                                    textElement("id", decl.name.name),
                                    colsetStructure(decl.spec, ::slice),
                                    if (decl.timed) element("timed") else null,
                                    textElement("layout", layout),
                                ),
                        )
                    is VarDecl ->
                        element(
                            "var",
                            "id" to fresh(),
                            children =
                                listOf(element("type", children = listOf(textElement("id", decl.colset.text)))) +
                                    decl.names.map { textElement("id", it.name) } + textElement("layout", layout),
                        )
                    is GlobrefDecl ->
                        element(
                            "globref",
                            "id" to fresh(),
                            children =
                                listOf(
                                    textElement("id", decl.name.name),
                                    textElement("ml", slice(decl.value.span)),
                                    textElement("layout", layout),
                                ),
                        )
                    is UseDecl ->
                        element(
                            "use",
                            "id" to fresh(),
                            children = listOf(textElement("ml", "\"${decl.path}\""), textElement("layout", layout)),
                        )
                    else -> element("ml", "id" to fresh(), children = listOf(XmlText(layout), textElement("layout", layout)))
                }
        }
        return element("globbox", children = children)
    }

    private fun colsetStructure(
        spec: ColsetSpec,
        slice: (no.hvl.cpn.lang.ast.Span) -> String,
    ): XmlElement {
        fun ids(vararg names: String) = names.map { textElement("id", it) }

        fun with(range: RangeSpec?) =
            range
                ?.let {
                    listOf(
                        element("with", children = listOf(textElement("ml", slice(it.low.span)), textElement("ml", slice(it.high.span)))),
                    )
                }.orEmpty()
        return when (spec) {
            is UnitColset -> element("unit", children = spec.newName?.let { listOf(element("with", children = ids(it.name))) }.orEmpty())
            is BoolColset ->
                element(
                    "bool",
                    children =
                        if (spec.falseName != null &&
                            spec.trueName != null
                        ) {
                            listOf(element("with", children = ids(spec.falseName!!.name, spec.trueName!!.name)))
                        } else {
                            emptyList()
                        },
                )
            is IntColset -> element("int", children = with(spec.range))
            is IntInfColset -> element("intinf", children = with(spec.range))
            is RealColset -> element("real", children = with(spec.range))
            is TimeColset -> element("time")
            is StringColset -> element("string", children = with(spec.characters))
            is EnumColset -> element("enum", children = spec.constants.map { textElement("id", it.name) })
            is IndexColset ->
                element(
                    "index",
                    children =
                        listOf(
                            textElement("ml", slice(spec.range.low.span)),
                            textElement("ml", slice(spec.range.high.span)),
                            textElement("id", spec.constructor.name),
                        ),
                )
            is ProductColset -> element("product", children = spec.components.map { textElement("id", it.text) })
            is RecordColset ->
                element(
                    "record",
                    children = spec.fields.map { element("recordfield", children = ids(it.label.name, it.colset.text)) },
                )
            is UnionColset ->
                element(
                    "union",
                    children =
                        spec.alternatives.map { alt ->
                            element(
                                "unionfield",
                                children =
                                    listOfNotNull(
                                        textElement("id", alt.constructor.name),
                                        alt.colset?.let { element("type", children = ids(it.text)) },
                                    ),
                            )
                        },
                )
            is ListColset -> element("list", children = listOf(textElement("id", spec.element.text)) + with(spec.length))
            is SubsetColset ->
                element(
                    "subset",
                    children =
                        listOf(textElement("id", spec.base.text)) +
                            listOfNotNull(
                                spec.predicate?.let { element("by", children = listOf(textElement("ml", slice(it.span)))) },
                                spec.members?.let { element("with", children = listOf(textElement("ml", slice(it.span)))) },
                            ),
                )
            is AliasColset -> element("alias", children = ids(spec.colset.text))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pages

    private fun page(
        page: PageDocument,
        old: XmlElement?,
    ): XmlElement {
        var e =
            old
                ?: element(
                    "page",
                    "id" to xmlId(page.id),
                    children = listOf(element("pageattr", "name" to page.name), element("constraints")),
                )
        e =
            e.withChild(
                "pageattr",
                (e.child("pageattr") ?: element("pageattr")).withAttribute("name", page.name),
                before = setOf("place", "trans", "arc"),
            )
        val places = e.children("place").associateBy { it["id"] }
        val transitions = e.children("trans").associateBy { it["id"] }
        val arcs = e.children("arc").associateBy { it["id"] }
        e = e.replaceAll("place", page.places.map { place(it, places[xmlId(it.id)]) }, before = setOf("trans", "arc", "Aux", "constraints"))
        e =
            e.replaceAll(
                "trans",
                page.transitions.map { transition(it, page, transitions[xmlId(it.id)]) },
                before = setOf("arc", "Aux", "constraints"),
            )
        e = e.replaceAll("arc", page.arcs.map { arc(it, page, arcs[xmlId(it.id)]) }, before = setOf("Aux", "constraints"))
        return e
    }

    private fun place(
        node: PlaceNode,
        old: XmlElement?,
    ): XmlElement {
        var e =
            old ?: element(
                "place",
                "id" to xmlId(node.id),
                children =
                    listOf(
                        posattr(node.position),
                        fillattr(""),
                        lineattr(thick = "1"),
                        textattr(),
                        textElement("text", node.name),
                        element("ellipse", "w" to number(node.size?.width ?: 60.0), "h" to number(node.size?.height ?: 40.0)),
                        element("token", "x" to "-10.000000", "y" to "0.000000"),
                        element(
                            "marking",
                            "x" to "0.000000",
                            "y" to "0.000000",
                            "hidden" to "false",
                            children =
                                listOf(
                                    element(
                                        "snap",
                                        "snap_id" to "0",
                                        "anchor.horizontal" to "0",
                                        "anchor.vertical" to "0",
                                    ),
                                ),
                        ),
                    ),
            )
        e = e.withPosition(node.position).withText(node.name).withSize("ellipse", node.size)
        e =
            e.withRegion(
                "type",
                node.colourSet,
                node.colourSetLabel ?: (node.position + Point(40.0, 25.0)),
                node.colourSetLabel,
                setOf("marking", "token", "ellipse", "text"),
            )
        e =
            e.withRegion(
                "initmark",
                node.initialMarking,
                node.markingLabel ?: (node.position + Point(40.0, -25.0)),
                node.markingLabel,
                setOf("type", "marking"),
            )
        val portType =
            when (node.port) {
                null -> null
                PortType.IN -> "In"
                PortType.OUT -> "Out"
                PortType.IN_OUT -> "I/O"
            }
        e =
            if (portType == null) {
                e.remove("port")
            } else {
                val port = (e.child("port") ?: decorated("port", node.position + Point(-25.0, -20.0))).withAttribute("type", portType)
                e.withChild("port", port, before = setOf("fusioninfo"))
            }
        val fusion = node.fusionSet
        e =
            if (fusion == null) {
                e.remove("fusioninfo")
            } else {
                e.withChild(
                    "fusioninfo",
                    (e.child("fusioninfo") ?: decorated("fusioninfo", node.position + Point(-25.0, 20.0))).withAttribute("name", fusion),
                    before = emptySet(),
                )
            }
        return e
    }

    private fun transition(
        node: TransitionNode,
        page: PageDocument,
        old: XmlElement?,
    ): XmlElement {
        var e =
            old ?: element(
                "trans",
                "id" to xmlId(node.id),
                "explicit" to "false",
                children =
                    listOf(
                        posattr(node.position),
                        fillattr(""),
                        lineattr(thick = "1", type = "solid"),
                        textattr(),
                        textElement("text", node.name),
                        element("box", "w" to number(node.size?.width ?: 60.0), "h" to number(node.size?.height ?: 40.0)),
                        element("binding", "x" to "7.200000", "y" to "-3.000000"),
                    ),
            )
        e = e.withPosition(node.position).withText(node.name).withSize("box", node.size)
        val subpage = node.subpageId
        if (subpage == null) {
            e = e.remove("subst")
        } else {
            val subpageXml = xmlId(subpage)
            // Explicit (port,socket) pairs: CPN Tools does not match sockets to ports by name.
            val ports =
                document
                    .page(subpage)
                    ?.places
                    .orEmpty()
                    .filter { it.port != null }
            val pairs =
                page.arcs.filter { it.transitionId == node.id }.map { it.placeId }.distinct().mapNotNull { socket ->
                    val port = node.portAssignments[socket] ?: ports.firstOrNull { it.name == page.place(socket)?.name }?.id
                    port?.let { xmlId(it) to xmlId(socket) }
                }
            val oldSubst = e.child("subst")
            val oldPairs =
                oldSubst?.get("portsock")?.let { s ->
                    Regex("""\(\s*([^,()\s]+)\s*,\s*([^,()\s]+)\s*\)""")
                        .findAll(s)
                        .map {
                            it.groupValues[1] to
                                it.groupValues[2]
                        }.toSet()
                }
            val portsock = if (oldPairs == pairs.toSet()) oldSubst!!["portsock"]!! else pairs.joinToString("") { (p, s) -> "($p,$s)" }
            val subst =
                (
                    oldSubst ?: element(
                        "subst",
                        children =
                            listOf(
                                decorated("subpageinfo", node.position + Point(0.0, 30.0)).withAttribute(
                                    "name",
                                    document.page(subpage)?.name ?: subpage,
                                ),
                            ),
                    )
                ).withAttribute("subpage", subpageXml).withAttribute("portsock", portsock)
            e = e.withChild("subst", subst, before = setOf("cond", "time", "code", "priority", "channel"))
        }
        e =
            e.withRegion(
                "cond",
                node.guard,
                node.guardLabel ?: (node.position + Point(-40.0, -30.0)),
                node.guardLabel,
                setOf("subst", "binding", "box", "text"),
            )
        e = e.withRegion("time", node.time, node.timeLabel ?: (node.position + Point(40.0, -30.0)), node.timeLabel, setOf("cond"))
        e = e.withRegion("code", node.code, node.position + Point(40.0, 30.0), null, setOf("time"))
        e = e.withRegion("priority", node.priority, node.position + Point(-40.0, 30.0), null, setOf("code", "channel"))
        return e
    }

    private fun arc(
        arc: ArcEdge,
        page: PageDocument,
        old: XmlElement?,
    ): XmlElement {
        val orientation =
            when (arc.kind) {
                ArcKind.INHIBITOR -> "Inhibitor"
                ArcKind.RESET -> "Reset"
                ArcKind.NORMAL ->
                    when (arc.direction) {
                        ArcDirection.PLACE_TO_TRANSITION -> "PtoT"
                        ArcDirection.TRANSITION_TO_PLACE -> "TtoP"
                        ArcDirection.BIDIRECTIONAL -> "BOTHDIR"
                    }
            }
        val from = page.place(arc.placeId)?.position ?: Point(0.0, 0.0)
        val to = page.transition(arc.transitionId)?.position ?: Point(0.0, 0.0)
        var e =
            old ?: element(
                "arc",
                "id" to xmlId(arc.id),
                "orientation" to orientation,
                "order" to "1",
                children =
                    listOf(
                        posattr(Point(0.0, 0.0)),
                        fillattr(""),
                        lineattr(thick = "1"),
                        textattr(),
                        element("arrowattr", "headsize" to "1.200000", "currentcyckle" to "2"),
                        element("transend", "idref" to xmlId(arc.transitionId)),
                        element("placeend", "idref" to xmlId(arc.placeId)),
                    ),
            )
        e = e.withAttribute("orientation", orientation)
        e =
            e.withChild(
                "transend",
                e.child("transend")!!.withAttribute("idref", xmlId(arc.transitionId)),
                before = setOf("placeend", "bendpoint", "annot"),
            )
        e = e.withChild("placeend", e.child("placeend")!!.withAttribute("idref", xmlId(arc.placeId)), before = setOf("bendpoint", "annot"))
        val oldBends = e.children("bendpoint").sortedBy { it["serial"]?.toIntOrNull() ?: 0 }
        if (oldBends.map { positionOf(it) } != arc.bends) {
            val bends =
                arc.bends.mapIndexed { i, p ->
                    element(
                        "bendpoint",
                        "id" to fresh(),
                        "serial" to "${i + 1}",
                        children = listOf(posattr(p), fillattr("Solid"), lineattr(thick = "0"), textattr()),
                    )
                }
            e = e.replaceAll("bendpoint", bends, before = setOf("annot"))
        }
        val middle = Point((from.x + to.x) / 2, (from.y + to.y) / 2) + Point(10.0, -10.0)
        return e.withRegion("annot", arc.inscription, arc.label ?: middle, arc.label, setOf("bendpoint", "placeend"))
    }

    // ---------------------------------------------------------------------------------------------
    // Hierarchy: fusion sets and page instances

    private fun fusions(old: List<XmlElement>): List<XmlElement> {
        // Unchanged since import: the file's fusion elements stay, even where they disagree with the places' labels.
        if (imported?.let(::fusionMembership) == fusionMembership(document)) return old
        val sets = LinkedHashMap<String, MutableList<String>>()
        for (page in document.pages) {
            for (place in page.places) {
                place.fusionSet?.let {
                    sets.getOrPut(it) { mutableListOf() } +=
                        xmlId(place.id)
                }
            }
        }
        val byName = old.associateBy { it["name"] }
        // The file's order first, then new fusion sets in the order their places appear.
        val names = old.mapNotNull { it["name"] }.filter { it in sets } + sets.keys.filter { it !in byName }
        return names.map { name ->
            val members = sets.getValue(name)
            val existing = byName[name]
            if (existing != null && existing.children("fusion_elm").map { it["idref"] }.toSet() == members.toSet()) {
                existing
            } else {
                element(
                    "fusion",
                    "id" to (existing?.get("id") ?: fresh()),
                    "name" to name,
                    children =
                        members.map {
                            element(
                                "fusion_elm",
                                "idref" to it,
                            )
                        },
                )
            }
        }
    }

    /** Instance tree: one top instance per page no substitution transition uses, then nested ones per substitution. */
    private sealed class Instance {
        abstract val children: List<Instance>
    }

    private data class PageInstance(
        val page: String,
        override val children: List<Instance>,
    ) : Instance()

    private data class TransInstance(
        val trans: String,
        override val children: List<Instance>,
    ) : Instance()

    private fun expectedInstances(): List<Instance> {
        val used = document.pages.flatMap { p -> p.transitions.mapNotNull { it.subpageId } }.toSet()

        fun below(
            pageId: String,
            seen: Set<String>,
        ): List<Instance> {
            val page = document.page(pageId) ?: return emptyList()
            return page.transitions.mapNotNull { t ->
                val sub = t.subpageId ?: return@mapNotNull null
                if (sub in seen) null else TransInstance(xmlId(t.id), below(sub, seen + sub))
            }
        }
        return document.pages.filter { it.id !in used }.map { PageInstance(xmlId(it.id), below(it.id, setOf(it.id))) }
    }

    private fun shapeOf(e: XmlElement): Instance? {
        val children = e.children("instance").map { shapeOf(it) ?: return null }
        return e["page"]?.let { PageInstance(it, children) } ?: e["trans"]?.let { TransInstance(it, children) }
    }

    /** The same tree with children in a fixed order: CPN Tools lists instances in the order they were created. */
    private fun canonical(i: Instance?): Instance? =
        when (i) {
            null -> null
            is PageInstance -> PageInstance(i.page, i.children.map { canonical(it)!! }.sortedBy { it.toString() })
            is TransInstance -> TransInstance(i.trans, i.children.map { canonical(it)!! }.sortedBy { it.toString() })
        }

    private fun instances(old: XmlElement?): XmlElement {
        val expected = expectedInstances()
        val same =
            old != null &&
                old.children("instance").map { canonical(shapeOf(it)) }.sortedBy { it.toString() } ==
                expected.map(::canonical).sortedBy { it.toString() }
        if (same) return old!!

        fun xml(i: Instance): XmlElement =
            when (i) {
                is PageInstance -> element("instance", "id" to fresh(), "page" to i.page, children = i.children.map(::xml))
                is TransInstance -> element("instance", "id" to fresh(), "trans" to i.trans, children = i.children.map(::xml))
            }
        return element("instances", children = expected.map(::xml))
    }

    private fun binders(instances: XmlElement): XmlElement {
        val sheets =
            instances.children("instance").mapIndexed { i, inst ->
                element(
                    "cpnsheet",
                    "id" to fresh(),
                    "panx" to "-6.000000",
                    "pany" to "-5.000000",
                    "zoom" to "1.000000",
                    "instance" to inst["id"]!!,
                    children = listOf(element("zorder", children = listOf(element("position", "value" to "$i")))),
                )
            }
        val zorder = element("zorder", children = sheets.indices.reversed().map { element("position", "value" to "$it") })
        return element(
            "binders",
            children =
                listOf(
                    element(
                        "cpnbinder",
                        "id" to fresh(),
                        "x" to "100",
                        "y" to "50",
                        "width" to "800",
                        "height" to "600",
                        children = listOf(element("sheets", children = sheets), zorder),
                    ),
                ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // A new file

    private fun skeleton(): XmlDocument =
        XmlDocument(
            prolog =
                "<?xml version=\"1.0\" encoding=\"iso-8859-1\"?>\n" +
                    "<!DOCTYPE workspaceElements PUBLIC \"-//CPN//DTD CPNXML 1.0//EN\" \"http://cpntools.org/DTD/6/cpn.dtd\">\n" +
                    "<!-- Written by cpn-kmp (a headless CPN tool), in the format of CPN Tools $TOOL_VERSION -->\n\n",
            root =
                element(
                    "workspaceElements",
                    children =
                        listOf(
                            element("generator", "tool" to "CPN Tools", "version" to TOOL_VERSION, "format" to "6"),
                            element("cpnet"),
                        ),
                ),
        )

    private fun defaultOptions(): XmlElement {
        // CPN Tools 4.0.1's defaults for a new net (simulation and performance report options).
        val on =
            setOf(
                "repavg",
                "repciavg",
                "repmax",
                "repmin",
                "repstddev",
                "avg",
                "count",
                "max",
                "min",
                "untimedavg",
                "untimedcount",
                "untimedmax",
                "untimedmin",
                "untimedsum",
            )
        val names =
            listOf("realtimestamp", "fair_be", "global_fairness") +
                listOf(
                    "repavg",
                    "repciavg",
                    "repcount",
                    "repfirstval",
                    "replastval",
                    "repmax",
                    "repmin",
                    "repssquare",
                    "repssqdev",
                    "repstddev",
                    "repsum",
                    "repvariance",
                ) +
                listOf(
                    "avg",
                    "ciavg",
                    "count",
                    "firstval",
                    "lastval",
                    "max",
                    "min",
                    "ssquare",
                    "ssqdev",
                    "stddev",
                    "sum",
                    "variance",
                    "firstupdate",
                    "interval",
                    "lastupdate",
                ) +
                listOf(
                    "untimedavg",
                    "untimedciavg",
                    "untimedcount",
                    "untimedfirstval",
                    "untimedlastval",
                    "untimedmax",
                    "untimedmin",
                    "untimedssquare",
                    "untimedssqdev",
                    "untimedstddev",
                    "untimedsum",
                    "untimedvariance",
                )
        val options =
            names.map { name ->
                element(
                    "option",
                    "name" to name,
                    children = listOf(element("value", children = listOf(textElement("boolean", "${name in on}")))),
                )
            } +
                element(
                    "option",
                    "name" to "outputdirectory",
                    children = listOf(element("value", children = listOf(textElement("text", "<same as model>")))),
                )
        return element("options", children = options)
    }

    // ---------------------------------------------------------------------------------------------
    // Elements

    private fun element(
        name: String,
        vararg attributes: Pair<String, String>,
        children: List<XmlNode> = emptyList(),
    ) = XmlElement(name, attributes.toList(), children)

    private fun textElement(
        name: String,
        text: String,
    ) = XmlElement(name, children = if (text.isEmpty()) emptyList() else listOf(XmlText(text)))

    private fun posattr(p: Point) = element("posattr", "x" to number(p.x), "y" to number(-p.y))

    private fun fillattr(pattern: String) = element("fillattr", "colour" to "White", "pattern" to pattern, "filled" to "false")

    private fun lineattr(
        thick: String,
        type: String = "Solid",
    ) = element("lineattr", "colour" to "Black", "thick" to thick, "type" to type)

    private fun textattr() = element("textattr", "colour" to "Black", "bold" to "false")

    /** A label element such as `<port>`: an id and the four style attributes, at [at]. */
    private fun decorated(
        name: String,
        at: Point,
    ) = element(name, "id" to fresh(), children = listOf(posattr(at), fillattr("Solid"), lineattr(thick = "0"), textattr()))

    /** An inscription region (`cond`, `type`, `annot`, ...): [decorated] plus its `<text>`. */
    private fun region(
        name: String,
        text: String,
        at: Point,
    ) = decorated(name, at).let {
        it.copy(
            children =
                it.children +
                    XmlElement(
                        "text",
                        listOf("tool" to "CPN Tools", "version" to TOOL_VERSION),
                        if (text.isEmpty()) emptyList() else listOf(XmlText(text)),
                    ),
        )
    }

    private fun XmlElement.withPosition(p: Point): XmlElement {
        val old = child("posattr")
        if (old != null && positionOf(old) == p) return this
        return withChild(
            "posattr",
            (old ?: posattr(p)).withAttribute("x", number(p.x)).withAttribute("y", number(-p.y)),
            before = setOf("fillattr", "lineattr", "textattr", "text"),
        )
    }

    private fun positionOf(e: XmlElement): Point {
        val p = e.child("posattr") ?: e
        return Point(p["x"]?.toDoubleOrNull() ?: 0.0, 0.0 - (p["y"]?.toDoubleOrNull() ?: 0.0))
    }

    /** The element's own `<text>` (a place's or transition's name). */
    private fun XmlElement.withText(text: String): XmlElement {
        val old = child("text")
        if (old != null && old.text == text) return this
        return withChild(
            "text",
            (old ?: XmlElement("text")).copy(children = if (text.isEmpty()) emptyList() else listOf(XmlText(text))),
            before = setOf("ellipse", "box"),
        )
    }

    /**
     * Sets an inscription region's text and, if the document has one, its [label] position; creates
     * the region at [at] if needed. The importer trims, so whitespace around unchanged text is kept.
     */
    private fun XmlElement.withRegion(
        name: String,
        text: String,
        at: Point,
        label: Point?,
        after: Set<String>,
    ): XmlElement {
        val old = child(name)
        if (old == null) {
            // A new element gets every region, as CPN Tools writes them; a parsed one only those with text.
            if (text.isEmpty() && rawStart != null) return this
            return insertAfter(region(name, text, at), after)
        }
        var updated = old
        val textElement = old.child("text") ?: XmlElement("text", listOf("tool" to "CPN Tools", "version" to TOOL_VERSION))
        if (textElement.text.trim() != text) {
            updated =
                updated.withChild(
                    "text",
                    textElement.copy(children = if (text.isEmpty()) emptyList() else listOf(XmlText(text))),
                    before = emptySet(),
                )
        }
        // A moved label: compared as CPN Tools writes numbers, so an unmoved one keeps its bytes.
        val p = old.child("posattr")
        if (label != null &&
            p != null &&
            (p["x"]?.toDoubleOrNull()?.let(::number) != number(label.x) || p["y"]?.toDoubleOrNull()?.let(::number) != number(-label.y))
        ) {
            updated =
                updated.withChild(
                    "posattr",
                    p.withAttribute("x", number(label.x)).withAttribute("y", number(-label.y)),
                    before = emptySet(),
                )
        }
        return if (updated === old) this else withChild(name, updated, before = emptySet())
    }

    /** The node's `<ellipse>` or `<box>` with [size], if the document has one and it differs. */
    private fun XmlElement.withSize(
        shape: String,
        size: Size?,
    ): XmlElement {
        val old = child(shape) ?: return this
        if (size == null) return this
        if (old["w"]?.toDoubleOrNull()?.let(::number) == number(size.width) &&
            old["h"]?.toDoubleOrNull()?.let(::number) == number(size.height)
        ) {
            return this
        }
        return withChild(shape, old.withAttribute("w", number(size.width)).withAttribute("h", number(size.height)), before = emptySet())
    }
}

/** CPN Tools writes coordinates with six decimals. */
internal fun number(value: Double): String {
    val scaled = (value * 1_000_000).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val magnitude = abs(scaled)
    return "$sign${magnitude / 1_000_000}.${(magnitude % 1_000_000).toString().padStart(6, '0')}"
}

// -------------------------------------------------------------------------------------------------
// Editing parsed XML while keeping its whitespace

internal fun XmlElement.withAttribute(
    name: String,
    value: String,
): XmlElement {
    if (this[name] == value) return this
    val attributes =
        if (this[name] ==
            null
        ) {
            attributes + (name to value)
        } else {
            attributes.map { if (it.first == name) name to value else it }
        }
    return copy(attributes = attributes)
}

/** Replaces the first child [name] with [element] (no change if it is the same), or inserts it before the first of [before]. */
internal fun XmlElement.withChild(
    name: String,
    element: XmlElement,
    before: Set<String>,
): XmlElement {
    val index = children.indexOfFirst { it is XmlElement && it.name == name }
    if (index >= 0) {
        if (children[index] === element || children[index] == element) return this
        return copy(children = children.toMutableList().also { it[index] = element })
    }
    return insert(element, children.indexOfFirst { it is XmlElement && it.name in before })
}

/** Inserts [element] after the last child named in [after], or at the end. */
internal fun XmlElement.insertAfter(
    element: XmlElement,
    after: Set<String>,
): XmlElement {
    val last = children.indexOfLast { it is XmlElement && it.name in after }
    val next =
        if (last <
            0
        ) {
            -1
        } else {
            children.subList(last + 1, children.size).indexOfFirst { it is XmlElement }.let { if (it < 0) -1 else last + 1 + it }
        }
    return insert(element, next)
}

internal fun XmlElement.remove(name: String): XmlElement {
    val index = children.indexOfFirst { it is XmlElement && it.name == name }
    if (index < 0) return this
    val list = children.toMutableList()
    list.removeAt(index)
    // Drop the indentation before it too.
    if (index > 0 && (list[index - 1] as? XmlText)?.text?.isBlank() == true) list.removeAt(index - 1)
    return copy(children = list)
}

/**
 * The children called [name] replaced by [elements], in place of the first old one, or before the
 * first of [before]. Unchanged when the elements are the same, so untouched files stay byte-identical.
 */
internal fun XmlElement.replaceAll(
    name: String,
    elements: List<XmlElement>,
    before: Set<String>,
): XmlElement {
    val old = children.filter { it is XmlElement && it.name == name }
    if (old.size == elements.size && old.indices.all { old[it] === elements[it] || old[it] == elements[it] }) return this
    var result = this
    while (result.children.any { it is XmlElement && it.name == name }) result = result.remove(name)
    val firstOld = children.indexOfFirst { it is XmlElement && it.name == name }
    // Insert where the old ones started: before whatever element followed the first one.
    val anchor =
        if (firstOld >= 0) {
            children.subList(firstOld, children.size).firstOrNull { it is XmlElement && it.name != name } as XmlElement?
        } else {
            null
        }
    for (e in elements) {
        val at =
            if (anchor != null) {
                result.children.indexOfFirst { it === anchor }
            } else {
                result.children.indexOfFirst { it is XmlElement && it.name in before }
            }
        result = result.insert(e, at)
    }
    return result
}

/** Inserts [element] at child index [at] (or at the end if negative), with the indentation the siblings use. */
private fun XmlElement.insert(
    element: XmlElement,
    at: Int,
): XmlElement {
    val indent = children.filterIsInstance<XmlText>().firstOrNull { it.text.isBlank() && '\n' in it.text }
    val list = children.toMutableList()
    if (indent == null) {
        if (at < 0) list += element else list.add(at, element)
        return copy(children = list)
    }
    // Parsed elements keep whitespace as text: put the new element after an indentation like its siblings'.
    if (at < 0) {
        val closing = (list.lastOrNull() as? XmlText)?.takeIf { it.text.isBlank() }
        if (closing != null) {
            list.removeAt(list.lastIndex)
            list += indent
            list += element
            list += closing
        } else {
            list += indent
            list += element
        }
    } else {
        // [ws, old] becomes [ws, element, ws, old]: the old child keeps the indentation before it.
        list.add(at, indent)
        list.add(at, element)
    }
    return copy(children = list)
}
