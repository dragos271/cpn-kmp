package no.hvl.cpn.io.python

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import no.hvl.cpn.lang.eval.ExternalFunction
import no.hvl.cpn.lang.eval.ExternalFunctionException
import no.hvl.cpn.lang.eval.ExternalFunctions

/**
 * `(* @python lab_delays.py delay_section *)` before `fun delaySection ...`: the CPN ML function
 * [name] is implemented by [function] in the Python file [file] (F13).
 */
public data class PythonBinding(
    val name: String,
    val file: String,
    val function: String,
)

/**
 * Python functions called from a model (F13). The annotation is an SML comment, so the model stays
 * valid CPN ML and opens in CPN Tools, which runs the function's own body instead.
 *
 * Python is started when it is first needed (a simulation starts, or a function is called) and one
 * process is shared by every model in this program, so recompiling a model while it is edited does
 * not start new processes. A file that changed on disk is loaded again when the next simulation starts. Every simulation's seed
 * is passed on, so random numbers drawn in Python (`random`, NumPy) repeat for the same seed.
 *
 * @param directory where relative file names are looked up: the model file's folder.
 */
public class PythonFunctions(
    public val bindings: List<PythonBinding>,
    directory: String?,
    private val launcher: ProcessLauncher,
    private val python: String = pythonCommand(),
) : ExternalFunctions {
    private val files = bindings.associate { it.name to resolve(it.file, directory) }
    private val byName = bindings.associateBy { it.name }
    private val loaded = HashSet<String>()

    override fun lookup(name: String): ExternalFunction? {
        val binding = byName[name] ?: return null
        return ExternalFunction { arguments -> call(binding, arguments) }
    }

    /**
     * Starts Python if needed and checks that every file loads and defines its function, so a
     * mistake is reported before a simulation rather than at the first call.
     * @throws ExternalFunctionException if Python cannot be started or a file or function is missing.
     */
    public fun check() {
        for (b in bindings) ensureLoaded(b)
    }

    /**
     * Seeds Python's `random` (and NumPy's, if loaded) for a simulation with [seed]. If Python cannot
     * be started, nothing happens here: the first call reports why.
     */
    public fun seed(seed: Long) {
        runCatching {
            request(
                buildJsonObject {
                    put("op", "seed")
                    put("seed", seed)
                },
            )
        }
    }

    private fun ensureLoaded(binding: PythonBinding) {
        if (binding.name in loaded) return
        request(
            buildJsonObject {
                put("op", "load")
                put("file", files.getValue(binding.name))
                put("function", binding.function)
            },
            "loading ${binding.file} for ${binding.name}",
        )
        loaded += binding.name
    }

    private fun call(
        binding: PythonBinding,
        arguments: List<Any?>,
    ): Any? {
        ensureLoaded(binding)
        return fromJson(
            request(
                buildJsonObject {
                    put("op", "call")
                    put("file", files.getValue(binding.name))
                    put("function", binding.function)
                    put("args", JsonArray(arguments.map(::toJson)))
                },
                "${binding.function} in ${binding.file}",
            ),
        )
    }

    private fun request(
        message: JsonObject,
        what: String = "Python",
    ): JsonElement? {
        val channel =
            try {
                shared(launcher, python)
            } catch (e: ProcessStartException) {
                throw ExternalFunctionException("Python is needed for ${bindings.joinToString { it.name }}, but ${e.message}")
            }
        channel.send(message.toString())
        val line = channel.receive()
        if (line == null) {
            processes.remove(python)
            throw ExternalFunctionException("the Python process ended. ${channel.errors().trim()}".trim())
        }
        val reply = Json.parseToJsonElement(line).jsonObject
        reply["error"]?.let { throw ExternalFunctionException("$what raised ${(it as JsonPrimitive).content}") }
        return reply["ok"]
    }

    public companion object {
        private val ANNOTATION =
            Regex("""\(\*\s*@python\s+(\S+)\s+([A-Za-z_]\w*)\s*\*\)\s*fun\s+([A-Za-z][\w']*)""")

        /** Python processes by command, shared by all models. */
        private val processes = HashMap<String, LineChannel>()

        /** The `@python` annotations in [declarations], in order. */
        public fun annotations(declarations: String): List<PythonBinding> =
            ANNOTATION.findAll(declarations).map { PythonBinding(it.groupValues[3], it.groupValues[1], it.groupValues[2]) }.toList()

        private fun shared(
            launcher: ProcessLauncher,
            python: String,
        ): LineChannel = processes.getOrPut(python) { launcher.start(listOf(python, "-u", "-c", BRIDGE), null) }

        /** Stops the shared Python processes (they also stop when the program exits). */
        public fun shutdown() {
            processes.values.forEach { it.close() }
            processes.clear()
        }

        /** [file] relative to [directory], unless it is absolute. */
        internal fun resolve(
            file: String,
            directory: String?,
        ): String {
            val absolute = file.startsWith("/") || file.startsWith("\\") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(file)
            return if (absolute || directory.isNullOrEmpty()) file else directory.trimEnd('/', '\\') + "/" + file
        }

        private fun toJson(value: Any?): JsonElement =
            when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                is List<*> -> JsonArray(value.map(::toJson))
                is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
                else -> throw ExternalFunctionException("cannot send $value to Python")
            }

        private fun fromJson(json: JsonElement?): Any? =
            when (json) {
                null, JsonNull -> null
                is JsonPrimitive ->
                    when {
                        json.isString -> json.content
                        json.booleanOrNull != null -> json.booleanOrNull
                        json.longOrNull != null && '.' !in json.content && 'e' !in json.content.lowercase() -> json.longOrNull
                        else -> json.doubleOrNull
                    }
                is JsonArray -> json.map(::fromJson)
                is JsonObject -> json.mapValues { fromJson(it.value) }
            }

        /**
         * The Python side: reads one JSON request per line and answers with `{"ok": value}` or
         * `{"error": "..."}`. The model's functions print to standard error, so their output cannot
         * break the protocol.
         */
        private val BRIDGE =
            """
            import sys, json, os, random, importlib.util
            out = sys.stdout
            sys.stdout = sys.stderr
            modules = {}
            checked = {}
            def load(path):
                full = os.path.abspath(path)
                # A file is checked for changes once per simulation run (see "seed"), not on every call.
                if full in modules and full in checked:
                    return modules[full][0]
                checked[full] = True
                if not os.path.exists(full):
                    raise FileNotFoundError("no file " + full)
                changed = os.path.getmtime(full)
                if full not in modules or modules[full][1] != changed:
                    if os.path.dirname(full) not in sys.path:
                        sys.path.insert(0, os.path.dirname(full))
                    spec = importlib.util.spec_from_file_location("cpn_model_%d" % len(modules), full)
                    module = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(module)
                    modules[full] = (module, changed)
                return modules[full][0]
            functions = {}
            def function(path, name):
                module = load(path)
                cached = functions.get((path, name))
                if cached is not None and cached[0] is module:
                    return cached[1]
                f = getattr(module, name, None)
                if not callable(f):
                    raise AttributeError("%s defines no function %s" % (path, name))
                functions[(path, name)] = (module, f)
                return f
            def plain(o):
                if hasattr(o, "item"):
                    return o.item()
                if hasattr(o, "tolist"):
                    return o.tolist()
                if isinstance(o, (tuple, set)):
                    return list(o)
                raise TypeError("cannot return %r to the model" % (o,))
            for line in sys.stdin:
                request = json.loads(line)
                try:
                    op = request["op"]
                    if op == "call":
                        result = function(request["file"], request["function"])(*request["args"])
                    elif op == "load":
                        function(request["file"], request["function"])
                        result = None
                    elif op == "seed":
                        checked.clear()
                        random.seed(request["seed"])
                        numpy = sys.modules.get("numpy")
                        if numpy is not None:
                            numpy.random.seed(request["seed"] % 4294967296)
                        result = None
                    reply = {"ok": result}
                except Exception as e:
                    reply = {"error": "%s: %s" % (type(e).__name__, e)}
                out.write(json.dumps(reply, default=plain) + "\n")
                out.flush()
            """.trimIndent()
    }
}
