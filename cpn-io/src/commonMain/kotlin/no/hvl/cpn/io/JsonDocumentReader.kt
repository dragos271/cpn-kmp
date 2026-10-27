package no.hvl.cpn.io

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.PortType
import no.hvl.cpn.io.model.ArcEdge
import no.hvl.cpn.io.model.AutoLayout
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.PlaceNode
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.TransitionNode
import no.hvl.cpn.lang.value.formatInt
import no.hvl.cpn.lang.value.formatString

/**
 * Reads a JSON model (already validated by [JsonNetReader]) as an editable [CpnDocument]: layout from
 * the optional `x` / `y` fields (or [AutoLayout]), and every inscription rendered as CPN ML text.
 * Element ids match the executable net's: `Page.Name` for places and transitions.
 */
internal class JsonDocumentReader(
    private val root: JsonObject,
) {
    /** How colours of each colour set are written; enough for the JSON format's colour sets. */
    private sealed interface Kind {
        data object UnitKind : Kind

        data object BoolKind : Kind

        data object IntKind : Kind

        data object StringKind : Kind

        data object EnumKind : Kind

        data class ProductKind(
            val first: Kind,
            val second: Kind,
        ) : Kind
    }

    private val kinds =
        linkedMapOf<String, Kind>("UNIT" to Kind.UnitKind, "BOOL" to Kind.BoolKind, "INT" to Kind.IntKind, "STRING" to Kind.StringKind)

    fun read(): CpnDocument {
        val declarations = mutableListOf<String>()
        for (cs in root.optionalArray("colsets", "")) {
            val obj = cs as JsonObject
            val name = obj.string("name", "")
            val (kind, text) = colourSet(obj)
            kinds[name] = kind
            declarations += "colset $name = $text;"
        }
        // CPN Tools style: one `var` line per colour set, e.g. `var n, k : NO;`.
        val variables = root.optionalObject("variables", "")?.entries.orEmpty()
        val namesByColourSet = variables.groupBy(keySelector = { (it.value as JsonPrimitive).content }, valueTransform = { it.key })
        for ((colourSet, names) in namesByColourSet) {
            declarations += "var ${names.joinToString(", ")} : $colourSet;"
        }
        val pageObjects = root.array("pages", "").map { it as JsonObject }
        val portsByPage = pageObjects.associate { page -> page.string("name", "") to ports(page) }
        val pages = pageObjects.map { page(it, portsByPage) }
        return CpnDocument(root.string("name", ""), declarations.joinToString("\n"), pages)
    }

    private fun colourSet(obj: JsonObject): Pair<Kind, String> =
        when (obj.string("type", "")) {
            "unit" -> Kind.UnitKind to "unit"
            "bool" -> Kind.BoolKind to "bool"
            "string" -> Kind.StringKind to "string"
            "int" -> {
                val range = obj["range"] as? JsonArray
                Kind.IntKind to (if (range == null) "int" else "int with ${int(range[0])}..${int(range[1])}")
            }
            "enum" -> Kind.EnumKind to "with " + obj.array("values", "").joinToString(" | ") { (it as JsonPrimitive).content }
            else -> {
                val (a, b) = obj.array("of", "").map { (it as JsonPrimitive).content }
                Kind.ProductKind(kinds.getValue(a), kinds.getValue(b)) to "product $a * $b"
            }
        }

    private fun ports(page: JsonObject): Map<String, PortType> =
        page
            .optionalArray("places", "")
            .map { it as JsonObject }
            .mapNotNull { p ->
                when (p.optionalString("port", "")) {
                    "in" -> p.string("name", "") to PortType.IN
                    "out" -> p.string("name", "") to PortType.OUT
                    "inout" -> p.string("name", "") to PortType.IN_OUT
                    else -> null
                }
            }.toMap()

    private fun page(
        json: JsonObject,
        portsByPage: Map<String, Map<String, PortType>>,
    ): PageDocument {
        val pageName = json.string("name", "")
        val unplaced = mutableSetOf<String>()

        fun idOf(name: String) = "$pageName.$name"

        fun position(
            obj: JsonObject,
            id: String,
        ): Point {
            val x = (obj["x"] as? JsonPrimitive)?.doubleOrNull
            val y = (obj["y"] as? JsonPrimitive)?.doubleOrNull
            if (x == null || y == null) unplaced += id
            return Point(x ?: 0.0, y ?: 0.0)
        }

        val placeKinds = HashMap<String, Kind>()
        val places =
            json.optionalArray("places", "").map { it as JsonObject }.map { p ->
                val name = p.string("name", "")
                val colourSet = p.string("colset", "")
                placeKinds[name] = kinds.getValue(colourSet)
                val id = idOf(name)
                PlaceNode(
                    id = id,
                    name = name,
                    colourSet = colourSet,
                    initialMarking = multiset(kinds.getValue(colourSet), p.optionalArray("init", ""), emptyText = ""),
                    position = position(p, id),
                    port =
                        when (p.optionalString("port", "")) {
                            "in" -> PortType.IN
                            "out" -> PortType.OUT
                            "inout" -> PortType.IN_OUT
                            else -> null
                        },
                    fusionSet = p.optionalString("fusion", ""),
                )
            }

        val transitions = mutableListOf<TransitionNode>()
        val arcs = mutableListOf<ArcEdge>()
        for (t in json.optionalArray("transitions", "").map { it as JsonObject }) {
            val name = t.string("name", "")
            val id = idOf(name)
            transitions += TransitionNode(id, name, t["guard"]?.let(::expression) ?: "", position(t, id))
            for ((key, direction) in listOf(
                "in" to ArcDirection.PLACE_TO_TRANSITION,
                "out" to ArcDirection.TRANSITION_TO_PLACE,
                "both" to ArcDirection.BIDIRECTIONAL,
            )) {
                t.optionalArray(key, "").forEachIndexed { j, a ->
                    val arc = a as JsonObject
                    val placeName = arc.string("place", "")
                    arcs +=
                        ArcEdge(
                            "$id.$key$j",
                            idOf(placeName),
                            id,
                            direction,
                            arcText(arc.getValue("inscription"), placeKinds.getValue(placeName)),
                        )
                }
            }
        }
        for (s in json.optionalArray("substitutions", "").map { it as JsonObject }) {
            val name = s.string("name", "")
            val id = idOf(name)
            val subpage = s.string("subpage", "")
            transitions += TransitionNode(id, name, "", position(s, id), subpageId = subpage)
            s.optionalObject("assign", "")?.forEach { (socket, port) ->
                val direction =
                    when (portsByPage[subpage]?.get((port as JsonPrimitive).content)) {
                        PortType.IN -> ArcDirection.PLACE_TO_TRANSITION
                        PortType.OUT -> ArcDirection.TRANSITION_TO_PLACE
                        else -> ArcDirection.BIDIRECTIONAL
                    }
                arcs += ArcEdge("$id.$socket", idOf(socket), id, direction, "")
            }
        }
        return AutoLayout.layout(PageDocument(pageName, pageName, places, transitions, arcs), unplaced)
    }

    // -- CPN ML text ----------------------------------------------------------------------------

    private fun colour(
        kind: Kind,
        json: JsonElement,
    ): String =
        when (kind) {
            Kind.UnitKind -> "()"
            Kind.BoolKind -> (json as JsonPrimitive).content
            Kind.IntKind -> int(json)
            Kind.StringKind -> formatString((json as JsonPrimitive).content)
            Kind.EnumKind -> (json as JsonPrimitive).content
            is Kind.ProductKind -> {
                val pair = json as JsonArray
                "(${colour(kind.first, pair[0])},${colour(kind.second, pair[1])})"
            }
        }

    /** ``2`a++1`b``, merging repeated colours in order of first appearance. */
    private fun multiset(
        kind: Kind,
        tokens: JsonArray,
        emptyText: String,
    ): String {
        val counts = LinkedHashMap<String, Int>()
        for (token in tokens) {
            if (token is JsonObject) {
                val text = colour(kind, token.getValue("value"))
                counts[text] = (counts[text] ?: 0) + (token.getValue("count") as JsonPrimitive).intOrNull!!
            } else {
                val text = colour(kind, token)
                counts[text] = (counts[text] ?: 0) + 1
            }
        }
        return if (counts.isEmpty()) emptyText else counts.entries.joinToString("++") { (text, n) -> "$n`$text" }
    }

    /**
     * An arc inscription as CPN ML. On its own, a single token may be written bare (`n`, `(n,d)`);
     * inside `if` branches and `++` sums every part must be a multiset ([multiset] true), so it is
     * written with its count (``1`n``), or both branches of an `if` would have different types.
     */
    private fun arcText(
        json: JsonElement,
        kind: Kind,
        multiset: Boolean = false,
    ): String {
        if (json is JsonPrimitive) return if (multiset) "1`${json.content}" else json.content
        val obj = json as JsonObject
        val count = (obj["count"] as? JsonPrimitive)?.intOrNull ?: 1
        val prefix = if (count == 1 && !multiset) "" else "$count`"
        return when {
            "var" in obj -> prefix + obj.string("var", "")
            "pattern" in obj -> prefix + obj.array("pattern", "").joinToString(",", "(", ")") { (it as JsonPrimitive).content }
            "tokens" in obj -> multiset(kind, obj.array("tokens", ""), emptyText = "empty")
            "expr" in obj -> if (prefix.isEmpty()) expression(obj.getValue("expr")) else prefix + atom(obj.getValue("expr"))
            "if" in obj -> {
                val condition = expression(obj.getValue("if"))
                "if $condition then ${arcText(obj.getValue("then"), kind, true)} else ${arcText(obj.getValue("else"), kind, true)}"
            }
            else -> obj.array("sum", "").joinToString(" ++ ") { arcText(it, kind, true) }
        }
    }

    private fun expression(json: JsonElement): String =
        when (json) {
            is JsonNull -> "()"
            is JsonPrimitive ->
                when {
                    json.isString -> formatString(json.content)
                    json.booleanOrNull != null -> json.content
                    else -> int(json)
                }
            is JsonArray -> json.joinToString(",", "(", ")") { expression(it) }
            is JsonObject ->
                when {
                    "var" in json -> json.string("var", "")
                    "if" in json -> "if ${expression(
                        json.getValue("if"),
                    )} then ${expression(json.getValue("then"))} else ${expression(json.getValue("else"))}"
                    "tuple" in json -> json.array("tuple", "").joinToString(",", "(", ")") { expression(it) }
                    else -> {
                        val op = json.string("op", "")
                        val args = json.array("args", "")
                        if (op == "not") "not ${atom(args[0])}" else "${atom(args[0])} $op ${atom(args[1])}"
                    }
                }
        }

    /** An expression as an operand: compound ones in parentheses. */
    private fun atom(json: JsonElement): String {
        val text = expression(json)
        return if (json is JsonObject && ("op" in json || "if" in json)) "($text)" else text
    }

    private fun int(json: JsonElement): String = formatInt((json as JsonPrimitive).content.toLong())
}
