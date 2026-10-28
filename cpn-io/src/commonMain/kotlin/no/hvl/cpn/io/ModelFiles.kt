package no.hvl.cpn.io

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import no.hvl.cpn.core.ArcDirection
import no.hvl.cpn.core.PortType
import no.hvl.cpn.io.model.ArcEdge
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.io.model.PageDocument
import no.hvl.cpn.io.model.PlaceNode
import no.hvl.cpn.io.model.Point
import no.hvl.cpn.io.model.TransitionNode
import no.hvl.cpn.io.model.compile
import no.hvl.cpn.io.model.structure

/** How a model was saved, so an editor can tell the user what the file can be used for. */
public enum class SavedFormat {
    /** The runnable JSON format (docs/json-format.md) with the edited layout: `cpn simulate` can run it. */
    RUNNABLE_MODEL,

    /** The editable document format: opens in the app; runs once the CPN ML evaluator exists (T2.5). */
    DOCUMENT,
}

public data class SavedModel(
    val text: String,
    val format: SavedFormat,
)

/**
 * Opening and saving model files for editors. Two formats are read:
 *
 * - the runnable JSON model format, which becomes a [LoadedModel] with an executable net;
 * - the document format ([CpnDocumentFormat]), which keeps inscriptions as CPN ML text and has no
 *   executable net until the evaluator exists.
 *
 * Saving keeps a model runnable whenever it can: if only the layout changed since it was opened, the
 * original runnable JSON is written back with the new positions. After structural edits (new
 * elements, changed inscriptions) the document format is written instead.
 */
public object ModelFiles {
    private val pretty = Json { prettyPrint = true }

    /**
     * @throws ModelFormatException if [text] is not a model file.
     * @throws no.hvl.cpn.core.InvalidNetException if it is a runnable model that is not well-formed.
     */
    public fun open(text: String): LoadedModel {
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw ModelFormatException("", "not valid JSON: ${e.message}")
            }
        if (root is JsonObject && (root["format"] as? JsonPrimitive)?.content == CpnDocumentFormat.FORMAT) {
            // Editable documents run once compiled; with problems, they open for editing only.
            val document = CpnDocumentFormat.read(text)
            return LoadedModel(net = document.compile().net, document = document)
        }
        return JsonNetFormat.load(text)
    }

    public fun save(
        model: LoadedModel,
        document: CpnDocument,
    ): SavedModel {
        val source = model.source
        return if (source != null && document.structure() == model.document.structure()) {
            SavedModel(withLayout(source, document), SavedFormat.RUNNABLE_MODEL)
        } else {
            SavedModel(CpnDocumentFormat.write(document), SavedFormat.DOCUMENT)
        }
    }

    /** An empty model with one page. */
    public fun newModel(name: String = "Untitled"): LoadedModel =
        LoadedModel(
            net = null,
            document = CpnDocument(name, "", listOf(PageDocument("Page1", "Page1", emptyList(), emptyList(), emptyList()))),
        )

    /** [source] (runnable JSON) with every place, transition and substitution moved to its position in [document]. */
    internal fun withLayout(
        source: String,
        document: CpnDocument,
    ): String {
        val root = Json.parseToJsonElement(source) as JsonObject
        val pages =
            (root["pages"] as JsonArray).map { pageElement ->
                val page = pageElement as JsonObject
                val pageName = (page["name"] as JsonPrimitive).content
                val docPage = document.page(pageName)

                fun positioned(key: String): JsonElement? =
                    (page[key] as? JsonArray)?.let { elements ->
                        JsonArray(
                            elements.map { element ->
                                val obj = element as JsonObject
                                val id = "$pageName.${(obj["name"] as JsonPrimitive).content}"
                                val position = docPage?.place(id)?.position ?: docPage?.transition(id)?.position
                                if (position == null) obj else obj.withPosition(position)
                            },
                        )
                    }
                JsonObject(
                    page.mapValues { (key, value) ->
                        if (key in setOf("places", "transitions", "substitutions")) positioned(key) ?: value else value
                    },
                )
            }
        return pretty.encodeToString(JsonElement.serializer(), JsonObject(root + ("pages" to JsonArray(pages))))
    }

    /** Sets `x` and `y`, right after `name` if they were not there before, keeping the other keys in order. */
    private fun JsonObject.withPosition(position: Point): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, value) in this) {
            if (key == "x" || key == "y") continue
            result[key] = value
            if (key == "name") {
                result["x"] = coordinate(position.x)
                result["y"] = coordinate(position.y)
            }
        }
        return JsonObject(result)
    }
}

private fun coordinate(value: Double): JsonPrimitive =
    if (value == kotlin.math.floor(value) &&
        kotlin.math.abs(value) < 1e15
    ) {
        JsonPrimitive(value.toLong())
    } else {
        JsonPrimitive(value)
    }

/**
 * The editable document format: everything an editor shows, with inscriptions as CPN ML text.
 *
 * ```jsonc
 * { "format": "cpn-document", "version": 1, "name": "...", "declarations": "colset NO = int; ...",
 *   "pages": [{ "id": "P", "name": "P",
 *     "places": [{ "id": "P.A", "name": "A", "colset": "NO", "init": "1`1", "x": 100, "y": 80 }],
 *     "transitions": [{ "id": "P.T", "name": "T", "guard": "n > 0", "x": 200, "y": 80, "subpage": null }],
 *     "arcs": [{ "id": "P.a1", "place": "P.A", "transition": "P.T", "direction": "in", "inscription": "n" }] }] }
 * ```
 */
public object CpnDocumentFormat {
    public const val FORMAT: String = "cpn-document"
    public const val VERSION: Int = 1

    private val pretty = Json { prettyPrint = true }

    public fun write(document: CpnDocument): String =
        pretty.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("name", document.name)
                put("declarations", document.declarations)
                put("pages", buildJsonArray { document.pages.forEach { add(page(it)) } })
            },
        )

    private fun page(page: PageDocument): JsonObject =
        buildJsonObject {
            put("id", page.id)
            put("name", page.name)
            put(
                "places",
                buildJsonArray {
                    for (p in page.places) {
                        add(
                            buildJsonObject {
                                put("id", p.id)
                                put("name", p.name)
                                put("colset", p.colourSet)
                                put("init", p.initialMarking)
                                put("x", coordinate(p.position.x))
                                put("y", coordinate(p.position.y))
                                p.port?.let { put("port", portName(it)) }
                                p.fusionSet?.let { put("fusion", it) }
                            },
                        )
                    }
                },
            )
            put(
                "transitions",
                buildJsonArray {
                    for (t in page.transitions) {
                        add(
                            buildJsonObject {
                                put("id", t.id)
                                put("name", t.name)
                                put("guard", t.guard)
                                put("x", coordinate(t.position.x))
                                put("y", coordinate(t.position.y))
                                t.subpageId?.let { put("subpage", it) }
                                if (t.portAssignments.isNotEmpty()) {
                                    put("ports", buildJsonObject { t.portAssignments.forEach { (socket, port) -> put(socket, port) } })
                                }
                            },
                        )
                    }
                },
            )
            put(
                "arcs",
                buildJsonArray {
                    for (a in page.arcs) {
                        add(
                            buildJsonObject {
                                put("id", a.id)
                                put("place", a.placeId)
                                put("transition", a.transitionId)
                                put("direction", directionName(a.direction))
                                put("inscription", a.inscription)
                                if (a.bends.isNotEmpty()) {
                                    put(
                                        "bends",
                                        buildJsonArray {
                                            a.bends.forEach {
                                                add(
                                                    buildJsonArray {
                                                        add(coordinate(it.x))
                                                        add(coordinate(it.y))
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }

    /** @throws ModelFormatException if [text] is not a document of this format. */
    public fun read(text: String): CpnDocument {
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw ModelFormatException("", "not valid JSON: ${e.message}")
            } as? JsonObject ?: throw ModelFormatException("", "expected a JSON object at the top level")
        if ((root["format"] as? JsonPrimitive)?.content != FORMAT) throw ModelFormatException("format", "expected \"$FORMAT\"")
        val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (version != VERSION) throw ModelFormatException("version", "unsupported version $version; this app reads version $VERSION")
        val pages = root.array("pages", "").mapIndexed { i, p -> readPage(p.asObject("pages[$i]"), "pages[$i]") }
        if (pages.isEmpty()) throw ModelFormatException("pages", "a model needs at least one page")
        return CpnDocument(root.string("name", ""), root.optionalString("declarations", "") ?: "", pages)
    }

    private fun readPage(
        json: JsonObject,
        path: String,
    ): PageDocument {
        val places =
            json.optionalArray("places", path).mapIndexed { i, p ->
                val at = "$path.places[$i]"
                val obj = p.asObject(at)
                PlaceNode(
                    id = obj.string("id", at),
                    name = obj.string("name", at),
                    colourSet = obj.optionalString("colset", at) ?: "",
                    initialMarking = obj.optionalString("init", at) ?: "",
                    position = position(obj, at),
                    port = obj.optionalString("port", at)?.let { portType(it, "$at.port") },
                    fusionSet = obj.optionalString("fusion", at),
                )
            }
        val transitions =
            json.optionalArray("transitions", path).mapIndexed { i, t ->
                val at = "$path.transitions[$i]"
                val obj = t.asObject(at)
                TransitionNode(
                    id = obj.string("id", at),
                    name = obj.string("name", at),
                    guard = obj.optionalString("guard", at) ?: "",
                    position = position(obj, at),
                    subpageId = obj.optionalString("subpage", at),
                    portAssignments =
                        obj
                            .optionalObject("ports", at)
                            ?.entries
                            ?.associate { (socket, port) ->
                                socket to
                                    (
                                        (port as? JsonPrimitive)?.takeIf { it.isString }?.content
                                            ?: fail("$at.ports.$socket", "expected a port id")
                                    )
                            }.orEmpty(),
                )
            }
        val nodeIds = (places.map { it.id } + transitions.map { it.id }).toSet()
        val arcs =
            json.optionalArray("arcs", path).mapIndexed { i, a ->
                val at = "$path.arcs[$i]"
                val obj = a.asObject(at)
                val arc =
                    ArcEdge(
                        id = obj.string("id", at),
                        placeId = obj.string("place", at),
                        transitionId = obj.string("transition", at),
                        direction = direction(obj.string("direction", at), "$at.direction"),
                        inscription = obj.optionalString("inscription", at) ?: "",
                        bends =
                            obj.optionalArray("bends", at).mapIndexed { j, b ->
                                val pair = b.asArray("$at.bends[$j]")
                                Point(number(pair[0], "$at.bends[$j]"), number(pair[1], "$at.bends[$j]"))
                            },
                    )
                if (arc.placeId !in nodeIds) fail("$at.place", "no place '${arc.placeId}' on this page")
                if (arc.transitionId !in nodeIds) fail("$at.transition", "no transition '${arc.transitionId}' on this page")
                arc
            }
        return PageDocument(json.string("id", path), json.string("name", path), places, transitions, arcs)
    }

    private fun position(
        obj: JsonObject,
        path: String,
    ): Point = Point(number(obj.required("x", path), "$path.x"), number(obj.required("y", path), "$path.y"))

    private fun number(
        json: JsonElement,
        path: String,
    ): Double = (json as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail(path, "expected a number")

    private fun directionName(direction: ArcDirection): String =
        when (direction) {
            ArcDirection.PLACE_TO_TRANSITION -> "in"
            ArcDirection.TRANSITION_TO_PLACE -> "out"
            ArcDirection.BIDIRECTIONAL -> "both"
        }

    private fun direction(
        value: String,
        path: String,
    ): ArcDirection =
        when (value) {
            "in" -> ArcDirection.PLACE_TO_TRANSITION
            "out" -> ArcDirection.TRANSITION_TO_PLACE
            "both" -> ArcDirection.BIDIRECTIONAL
            else -> fail(path, "expected in, out or both")
        }

    private fun portName(port: PortType): String =
        when (port) {
            PortType.IN -> "in"
            PortType.OUT -> "out"
            PortType.IN_OUT -> "inout"
        }

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
}
