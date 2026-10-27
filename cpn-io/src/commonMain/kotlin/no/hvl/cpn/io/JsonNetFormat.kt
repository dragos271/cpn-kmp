package no.hvl.cpn.io

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import no.hvl.cpn.core.ArcInscription
import no.hvl.cpn.core.ColourSet
import no.hvl.cpn.core.ColourSets
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.NetBuilder
import no.hvl.cpn.core.Page
import no.hvl.cpn.core.PageBuilder
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.PortType
import no.hvl.cpn.core.Variable
import no.hvl.cpn.core.net

/** A malformed model file; [path] locates the problem, e.g. `pages[0].transitions[2].in[1]`. */
public class ModelFormatException(
    public val path: String,
    message: String,
) : IllegalArgumentException(if (path.isEmpty()) message else "$path: $message")

/**
 * Reads nets from the temporary JSON format of I1 (see `docs/json-format.md`). It exists so the CLI
 * can run models before the CPN ML front-end and the `.cpn` importer arrive (I2, I3).
 */
public object JsonNetFormat {
    /**
     * @throws ModelFormatException if the JSON does not follow the format.
     * @throws no.hvl.cpn.core.InvalidNetException if it does, but the net is not well-formed.
     */
    public fun parse(text: String): Net = JsonNetReader(root(text)).read()

    /**
     * The executable net plus the editable document (layout and CPN ML text) for an editor.
     * Element ids are shared: a document place `Page.P` is the net place with id `Page.P`.
     */
    public fun load(text: String): LoadedModel {
        val root = root(text)
        val net = JsonNetReader(root).read()
        return LoadedModel(net, JsonDocumentReader(root).read())
    }

    private fun root(text: String): JsonObject {
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw ModelFormatException("", "not valid JSON: ${e.message}")
            }
        return root as? JsonObject ?: throw ModelFormatException("", "expected a JSON object at the top level")
    }
}

/** A model as both the engine and an editor need it. */
public data class LoadedModel(
    val net: Net,
    val document: no.hvl.cpn.io.model.CpnDocument,
)

private class ColourSetSpec(
    val colourSet: ColourSet<Any?>,
    val decode: (JsonElement, String) -> Any?,
)

private class JsonNetReader(
    private val root: JsonObject,
) {
    private val colourSets = LinkedHashMap<String, ColourSetSpec>()
    private val variables = LinkedHashMap<String, Variable<Any?>>()
    private val pageJson = LinkedHashMap<String, Pair<JsonObject, String>>()
    private val pages = mutableMapOf<String, Page>()
    private val inProgress = mutableSetOf<String>()

    init {
        builtIn("UNIT", ColourSets.UNIT) { json, path ->
            if (json is JsonNull ||
                (json as? JsonPrimitive)?.content == "()"
            ) {
                Unit
            } else {
                fail(path, "expected null for ()")
            }
        }
        builtIn("BOOL", ColourSets.BOOL) { json, path ->
            (json as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
                ?: fail(path, "expected a boolean")
        }
        builtIn("INT", ColourSets.INT, ::decodeInt)
        builtIn("STRING", ColourSets.STRING, ::decodeString)
    }

    fun read(): Net {
        val name = root.string("name", "")
        root.optionalArray("colsets", "").forEachIndexed { i, cs -> readColourSet(cs.asObject("colsets[$i]"), "colsets[$i]") }
        root.optionalObject("variables", "")?.forEach { (varName, cs) ->
            variables[varName] = Variable(varName, colourSet(cs, "variables.$varName").colourSet)
        }
        val pagesJson = root.array("pages", "")
        if (pagesJson.isEmpty()) throw ModelFormatException("pages", "a net needs at least one page")
        pagesJson.forEachIndexed { i, page ->
            val path = "pages[$i]"
            val obj = page.asObject(path)
            val pageName = obj.string("name", path)
            if (pageJson.put(pageName, obj to path) != null) throw ModelFormatException("$path.name", "duplicate page '$pageName'")
        }
        return net(name) {
            variables.values.forEach { declare(it) }
            // Pages are built subpages-first, whatever their order in the file.
            pageJson.keys.forEach { buildPage(it) }
        }
    }

    private fun NetBuilder.buildPage(name: String): Page {
        pages[name]?.let { return it }
        val (json, path) = pageJson[name] ?: throw IllegalStateException(name)
        if (!inProgress.add(name)) throw ModelFormatException(path, "page '$name' (indirectly) contains itself")
        // Subpages first, so they exist when the substitution transitions refer to them.
        val subpages =
            json
                .optionalArray("substitutions", path)
                .mapIndexed { i, s ->
                    val subPath = "$path.substitutions[$i]"
                    val subName = s.asObject(subPath).string("subpage", subPath)
                    if (subName !in pageJson) throw ModelFormatException("$subPath.subpage", "no page named '$subName'")
                    subName to buildPage(subName)
                }.toMap()
        val page = page(name) { readPage(json, path, subpages) }
        inProgress.remove(name)
        pages[name] = page
        return page
    }

    private fun PageBuilder.readPage(
        json: JsonObject,
        path: String,
        subpages: Map<String, Page>,
    ) {
        val places = LinkedHashMap<String, Place<Any?>>()
        json.optionalArray("places", path).forEachIndexed { i, p ->
            val placePath = "$path.places[$i]"
            val obj = p.asObject(placePath)
            val name = obj.string("name", placePath)
            val spec = colourSet(obj.required("colset", placePath), "$placePath.colset")
            val init = obj.optionalArray("init", placePath).let { tokens(spec, it, "$placePath.init") }
            val port = obj.optionalString("port", placePath)?.let { portType(it, "$placePath.port") }
            val fusion = obj.optionalString("fusion", placePath)
            if (port != null && fusion != null) throw ModelFormatException(placePath, "a port place cannot also be in a fusion set")
            places[name] = if (port != null) port(name, spec.colourSet, port, init) else place(name, spec.colourSet, init, fusion)
        }

        fun placeNamed(
            name: String,
            at: String,
        ) = places[name] ?: throw ModelFormatException(at, "no place '$name' on this page")

        json.optionalArray("transitions", path).forEachIndexed { i, t ->
            val tPath = "$path.transitions[$i]"
            val obj = t.asObject(tPath)
            val guard = obj["guard"]?.let { guard(it, "$tPath.guard") } ?: Guard.TRUE
            transition(obj.string("name", tPath), guard) {
                for (key in listOf("in", "out", "both")) {
                    obj.optionalArray(key, tPath).forEachIndexed { j, a ->
                        val arcPath = "$tPath.$key[$j]"
                        val arc = a.asObject(arcPath)
                        val target = placeNamed(arc.string("place", arcPath), "$arcPath.place")
                        val inscription = arcInscription(arc.required("inscription", arcPath), "$arcPath.inscription", target)
                        when (key) {
                            "in" -> input(target, inscription)
                            "out" -> output(target, inscription)
                            else -> both(target, inscription)
                        }
                    }
                }
            }
        }

        json.optionalArray("substitutions", path).forEachIndexed { i, s ->
            val sPath = "$path.substitutions[$i]"
            val obj = s.asObject(sPath)
            val subpage = subpages.getValue(obj.string("subpage", sPath))
            substitution(obj.string("name", sPath), subpage) {
                obj.optionalObject("assign", sPath)?.forEach { (socket, port) ->
                    val portName =
                        (port as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$sPath.assign.$socket", "expected a port name")
                    val portPlace =
                        subpage.places.singleOrNull { it.name == portName }
                            ?: fail("$sPath.assign.$socket", "no place '$portName' on page '${subpage.name}'")
                    @Suppress("UNCHECKED_CAST")
                    assign(placeNamed(socket, "$sPath.assign.$socket"), portPlace as Place<Any?>)
                }
            }
        }
    }

    private fun readColourSet(
        json: JsonObject,
        path: String,
    ) {
        val name = json.string("name", path)
        if (name in colourSets) throw ModelFormatException("$path.name", "colour set '$name' is already defined")
        colourSets[name] =
            when (val type = json.string("type", path)) {
                "unit", "bool", "string" -> colourSets.getValue(type.uppercase())
                "int" -> {
                    val range = json["range"]?.let { it.asArray("$path.range") }
                    if (range == null) {
                        colourSets.getValue("INT")
                    } else {
                        val (low, high) =
                            range.map { decodeInt(it, "$path.range") as Int }.also {
                                if (it.size !=
                                    2
                                ) {
                                    fail("$path.range", "expected [low, high]")
                                }
                            }
                        spec(ColourSets.intRange(name, low..high)) { j, p ->
                            (decodeInt(j, p) as Int).also {
                                if (it !in
                                    low..high
                                ) {
                                    fail(p, "$it is not in $name")
                                }
                            }
                        }
                    }
                }
                "enum" -> {
                    val values = json.array("values", path).mapIndexed { i, v -> decodeString(v, "$path.values[$i]") }
                    spec(ColourSets.enumeration(name, values)) { j, p ->
                        decodeString(j, p).also {
                            if (it !in
                                values
                            ) {
                                fail(p, "'$it' is not a colour of $name")
                            }
                        }
                    }
                }
                "product" -> {
                    val parts = json.array("of", path)
                    if (parts.size != 2) fail("$path.of", "only products of two colour sets are supported")
                    val first = colourSet(parts[0], "$path.of[0]")
                    val second = colourSet(parts[1], "$path.of[1]")
                    spec(ColourSets.product(name, first.colourSet, second.colourSet)) { j, p ->
                        val pair = j.asArray(p)
                        if (pair.size != 2) fail(p, "expected a pair [a, b]")
                        first.decode(pair[0], "$p[0]") to second.decode(pair[1], "$p[1]")
                    }
                }
                else -> fail("$path.type", "unknown colour set type '$type'; expected unit, bool, int, string, enum or product")
            }
    }

    /** Initial or constant tokens: a list of colours, where `{"value": v, "count": n}` repeats a colour. */
    private fun tokens(
        spec: ColourSetSpec,
        json: JsonArray,
        path: String,
    ): Multiset<Any?> =
        json.foldIndexed(Multiset.empty()) { i, acc, token ->
            val tokenPath = "$path[$i]"
            if (token is JsonObject) {
                val count =
                    token.required("count", tokenPath).let {
                        (it as? JsonPrimitive)?.intOrNull
                            ?: fail("$tokenPath.count", "expected an integer")
                    }
                acc + Multiset.repeated(spec.decode(token.required("value", tokenPath), "$tokenPath.value"), count)
            } else {
                acc + spec.decode(token, tokenPath)
            }
        }

    /**
     * Arc inscriptions. A bare string is a variable; objects are `var`, `pattern`, `tokens`, `expr`,
     * `if` or `sum` (see `docs/json-format.md`).
     */
    private fun arcInscription(
        json: JsonElement,
        path: String,
        place: Place<Any?>,
    ): ArcInscription<Any?> {
        if (json is JsonPrimitive && json.isString) return ArcInscription.VariableTerm(variable(json.content, path))
        val obj = json.asObject(path)
        val count = obj["count"]?.let { (it as? JsonPrimitive)?.intOrNull ?: fail("$path.count", "expected an integer") } ?: 1
        if (count < 1) fail("$path.count", "count must be at least 1")
        return when {
            "var" in obj -> ArcInscription.VariableTerm(variable(obj.string("var", path), "$path.var"), count)
            "pattern" in obj -> {
                val names = obj.array("pattern", path)
                if (names.size != 2) fail("$path.pattern", "only pair patterns [a, b] are supported")
                val (first, second) =
                    names.mapIndexed { i, n ->
                        variable(
                            (n as? JsonPrimitive)?.content ?: fail("$path.pattern[$i]", "expected a variable name"),
                            "$path.pattern[$i]",
                        )
                    }
                @Suppress("UNCHECKED_CAST")
                ArcInscription.PairPattern(first, second, count) as ArcInscription<Any?>
            }
            "tokens" in obj -> ArcInscription.Constant(tokens(placeSpec(place), obj.array("tokens", path), "$path.tokens"))
            "expr" in obj -> {
                val expr = parseExpr(obj.getValue("expr"), "$path.expr", variables)
                ArcInscription.Function(expr.variables, "expr") { b -> Multiset.repeated(expr.eval(b), count) }
            }
            "if" in obj -> {
                val condition = parseExpr(obj.getValue("if"), "$path.if", variables)
                val then = arcInscription(obj.required("then", path), "$path.then", place)
                val otherwise = arcInscription(obj.required("else", path), "$path.else", place)
                ArcInscription.Function(condition.variables + then.variables + otherwise.variables, "if") { b ->
                    if (condition.eval(b) as Boolean) then.evaluate(b) else otherwise.evaluate(b)
                }
            }
            "sum" in obj -> {
                val parts = obj.array("sum", path).mapIndexed { i, part -> arcInscription(part, "$path.sum[$i]", place) }
                ArcInscription.Function(parts.flatMap { it.variables }.toSet(), "sum") { b ->
                    parts.fold(Multiset.empty()) { acc, part ->
                        acc +
                            part.evaluate(b)
                    }
                }
            }
            else -> fail(path, "expected a variable name or an object with one of: var, pattern, tokens, expr, if, sum")
        }
    }

    private fun guard(
        json: JsonElement,
        path: String,
    ): Guard {
        val expr = parseExpr(json, path, variables)
        return Guard.of(*expr.variables.toTypedArray(), source = "guard") { b ->
            expr.eval(b) as? Boolean ?: throw ModelFormatException(path, "guard evaluated to a non-boolean in $b")
        }
    }

    private fun colourSet(
        json: JsonElement,
        path: String,
    ): ColourSetSpec {
        val name = (json as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(path, "expected a colour set name")
        return colourSets[name] ?: fail(path, "unknown colour set '$name'; defined: ${colourSets.keys}")
    }

    private fun placeSpec(place: Place<Any?>): ColourSetSpec = colourSets.values.first { it.colourSet === place.colourSet }

    private fun variable(
        name: String,
        path: String,
    ): Variable<Any?> = variables[name] ?: fail(path, "undeclared variable '$name'")

    private fun portType(
        value: String,
        path: String,
    ): PortType =
        when (value) {
            "in" -> PortType.IN
            "out" -> PortType.OUT
            "inout" -> PortType.IN_OUT
            else -> fail(path, "expected in, out or inout")
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> builtIn(
        name: String,
        colourSet: ColourSet<T>,
        decode: (JsonElement, String) -> Any?,
    ) {
        colourSets[name] = ColourSetSpec(colourSet as ColourSet<Any?>, decode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> spec(
        colourSet: ColourSet<T>,
        decode: (JsonElement, String) -> Any?,
    ) = ColourSetSpec(colourSet as ColourSet<Any?>, decode)

    private fun decodeInt(
        json: JsonElement,
        path: String,
    ): Any = (json as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: fail(path, "expected an integer")

    private fun decodeString(
        json: JsonElement,
        path: String,
    ): String = (json as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(path, "expected a string")
}

internal fun fail(
    path: String,
    message: String,
): Nothing = throw ModelFormatException(path, message)

internal fun JsonElement.asObject(path: String): JsonObject = this as? JsonObject ?: fail(path, "expected an object")

internal fun JsonElement.asArray(path: String): JsonArray = this as? JsonArray ?: fail(path, "expected an array")

internal fun JsonObject.required(
    key: String,
    path: String,
): JsonElement = this[key] ?: fail(path, "missing '$key'")

internal fun JsonObject.string(
    key: String,
    path: String,
): String =
    (required(key, path) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: fail(if (path.isEmpty()) key else "$path.$key", "expected a string")

internal fun JsonObject.optionalString(
    key: String,
    path: String,
): String? = if (key in this) string(key, path) else null

internal fun JsonObject.array(
    key: String,
    path: String,
): JsonArray = required(key, path).asArray(if (path.isEmpty()) key else "$path.$key")

internal fun JsonObject.optionalArray(
    key: String,
    path: String,
): JsonArray = if (key in this) array(key, path) else JsonArray(emptyList())

internal fun JsonObject.optionalObject(
    key: String,
    path: String,
): JsonObject? = this[key]?.asObject(if (path.isEmpty()) key else "$path.$key")
