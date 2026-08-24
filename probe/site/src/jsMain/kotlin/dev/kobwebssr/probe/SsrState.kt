package dev.kobwebssr.probe

import androidx.compose.runtime.saveable.SaveableStateRegistry
import kotlinx.browser.window

/**
 * **M3-01 / D3.** State transfer between the server render and the client, built on Compose's own
 * `SaveableStateRegistry` rather than on a bespoke serialiser.
 *
 * Kilua does this by hand with a `stateSerializer` because it has no such primitive. Compose does,
 * it is published for every target, and it brings the right discipline with it: state crosses the
 * boundary only when its author said it should, by writing `rememberSaveable` instead of
 * `remember`. Anything else is recomputed on the client, and that is the documented behaviour
 * rather than a defect.
 *
 * The price, stated plainly: only values this file knows how to encode survive. Right now that is
 * `String`, `Boolean`, `Int` and `Double`. Widening it is a serialisation problem, not a Compose
 * one — see the research, D3.
 */
object SsrState {
    private const val GLOBAL = "KOBWEB_SSR_STATE"

    /** Reads what the server put in the page, if this is a server-rendered load. */
    private fun restored(): Map<String, List<Any?>>? {
        val raw = window.asDynamic()[GLOBAL] as? String ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun createRegistry(): SaveableStateRegistry =
        SaveableStateRegistry(restored()) { value -> value.isEncodable() }

    /**
     * Installed on `window` so the renderer can pull the state out after the composition settles.
     * The renderer is outside the application and cannot reach into the composition any other way.
     */
    fun exposeTo(registry: SaveableStateRegistry) {
        window.asDynamic()["__kobwebSsrSaveState"] = { encode(registry.performSave()) }
    }

    private fun Any?.isEncodable() = this is String || this is Boolean || this is Int || this is Double

    // Hand-rolled rather than kotlinx.serialization: the value type is `Any?` and the set of things
    // that can cross is deliberately tiny, so a schema would describe more than is true.
    private fun encode(saved: Map<String, List<Any?>>): String {
        val entries = saved.entries.joinToString(",") { (key, values) ->
            val encodedValues = values.joinToString(",") { value ->
                when (value) {
                    is String -> "{\"t\":\"s\",\"v\":${quote(value)}}"
                    is Boolean -> "{\"t\":\"b\",\"v\":$value}"
                    is Int -> "{\"t\":\"i\",\"v\":$value}"
                    is Double -> "{\"t\":\"d\",\"v\":$value}"
                    else -> "null"
                }
            }
            "${quote(key)}:[$encodedValues]"
        }
        return "{$entries}"
    }

    private fun decode(raw: String): Map<String, List<Any?>> {
        val parsed = JSON.parse<dynamic>(raw)
        val result = mutableMapOf<String, List<Any?>>()
        val keys = js("Object.keys")(parsed) as Array<String>
        keys.forEach { key ->
            val list = parsed[key] as Array<dynamic>
            result[key] = list.map { item ->
                when (item?.t as? String) {
                    "s" -> item.v as String
                    "b" -> item.v as Boolean
                    "i" -> (item.v as Number).toInt()
                    "d" -> (item.v as Number).toDouble()
                    else -> null
                }
            }
        }
        return result
    }

    private fun quote(value: String) = JSON.stringify(value)
}
