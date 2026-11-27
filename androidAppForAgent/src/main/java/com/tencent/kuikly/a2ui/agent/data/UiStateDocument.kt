package com.tencent.kuikly.a2ui.agent.data

import org.json.JSONArray
import org.json.JSONObject

internal object UiStateDocument {
    const val PROFILE_ID = "basic-v1"
    const val MAX_MESSAGE_BYTES = 65536
    const val MAX_DOCUMENT_BYTES = 1048576
    const val MAX_COMPONENTS = 512
    const val MAX_MESSAGES = 64
    private const val BASIC_CATALOG = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"
    private val messageTypes = listOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface")
    private val components = setOf("Text", "Image", "Icon", "Video", "AudioPlayer", "Row", "Column", "List", "Card", "Tabs", "Modal", "Divider", "Button", "TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput")
    private val functions = setOf("@index", "required", "regex", "length", "numeric", "email", "formatString", "formatNumber", "formatCurrency", "formatDate", "pluralize", "openUrl", "and", "or", "not")

    fun empty(): JSONObject = JSONObject().put("surfaces", JSONObject())

    fun validateMessage(envelope: JSONObject, wire: Boolean = true): String {
        if (envelope.toString().toByteArray(Charsets.UTF_8).size > if (wire) MAX_MESSAGE_BYTES else MAX_DOCUMENT_BYTES) throw SyncRequired()
        if (envelope.optString("version") != "1.0" || envelope.length() != 2) throw SyncRequired()
        val type = messageTypes.singleOrNull { envelope.has(it) } ?: throw SyncRequired()
        val body = envelope.getJSONObject(type)
        if (body.getString("surfaceId").isBlank()) throw SyncRequired()
        return type
    }

    fun apply(document: JSONObject, envelopes: List<JSONObject>, wire: Boolean = true): JSONObject {
        if (envelopes.size > MAX_MESSAGES) throw SyncRequired()
        val next = JSONObject(document.toString())
        val surfaces = next.getJSONObject("surfaces")
        envelopes.forEach { envelope ->
            val type = validateMessage(envelope, wire)
            val body = envelope.getJSONObject(type)
            val id = body.getString("surfaceId")
            when (type) {
                "createSurface" -> {
                    if (surfaces.has(id) || body.optString("catalogId", BASIC_CATALOG) != BASIC_CATALOG) throw SyncRequired()
                    val surface = JSONObject().put("surfaceId", id).put("components", JSONObject())
                        .put("dataModel", body.optJSONObject("dataModel")?.let { JSONObject(it.toString()) } ?: JSONObject())
                    listOf("catalogId", "sendDataModel", "metadata").forEach { key -> if (body.has(key)) surface.put(key, body.get(key)) }
                    if (body.has("components")) mergeComponents(surface, body.getJSONArray("components"))
                    surfaces.put(id, surface)
                }
                "updateComponents" -> mergeComponents(surfaces.optJSONObject(id) ?: throw SyncRequired(), body.getJSONArray("components"))
                "updateDataModel" -> {
                    val surface = surfaces.optJSONObject(id) ?: throw SyncRequired()
                    updateData(surface, body.optString("path", "/"), body.get("value"))
                }
                "deleteSurface" -> { if (!surfaces.has(id)) throw SyncRequired(); surfaces.remove(id) }
            }
        }
        val totalComponents = surfaces.keys().asSequence().sumOf { surfaces.getJSONObject(it).getJSONObject("components").length() }
        if (totalComponents > MAX_COMPONENTS || next.toString().toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) throw SyncRequired()
        return next
    }

    private fun mergeComponents(surface: JSONObject, updates: JSONArray) {
        if (updates.length() !in 1..MAX_COMPONENTS) throw SyncRequired()
        val stored = surface.getJSONObject("components")
        val seen = mutableSetOf<String>()
        updates.objects().forEach { component ->
            val id = component.getString("id")
            if (id.isBlank() || !seen.add(id) || component.getString("component") !in components || component.has("styles")) throw SyncRequired()
            validateFunctions(component)
            stored.put(id, JSONObject(component.toString()))
        }
    }

    private fun validateFunctions(value: Any) {
        when (value) {
            is JSONObject -> {
                if (value.has("@call") && ((value.getString("@call") != "@index" && value.getString("@call") !in functions) || value.optString("catalogId", BASIC_CATALOG) != BASIC_CATALOG)) throw SyncRequired()
                if (value.has("@catalogId") && value.getString("@catalogId") != BASIC_CATALOG) throw SyncRequired()
                value.keys().forEach { validateFunctions(value.get(it)) }
            }
            is JSONArray -> (0 until value.length()).forEach { validateFunctions(value.get(it)) }
        }
    }

    private fun updateData(surface: JSONObject, path: String, value: Any) {
        if (path.isEmpty() || path == "/") {
            if (value !== JSONObject.NULL && value !is JSONObject) throw SyncRequired()
            surface.put("dataModel", if (value === JSONObject.NULL) JSONObject() else JSONObject(value.toString()))
            return
        }
        if (!path.startsWith("/")) throw SyncRequired()
        val keys = path.drop(1).split('/').map {
            if (Regex("~(?:[^01]|$)").containsMatchIn(it)) throw SyncRequired()
            it.replace("~1", "/").replace("~0", "~").also { key -> if (key in setOf("__proto__", "prototype", "constructor")) throw SyncRequired() }
        }
        if (keys.size > 128) throw SyncRequired()
        val deleting = value === JSONObject.NULL
        var parent: Any = surface.getJSONObject("dataModel")
        keys.forEachIndexed { position, key ->
            val container = parent
            val index = if (container is JSONArray) {
                if (!Regex("0|[1-9][0-9]*").matches(key)) throw SyncRequired()
                val parsed = key.toIntOrNull() ?: throw SyncRequired()
                if (!deleting && parsed > 10000) throw SyncRequired()
                parsed
            } else 0
            if (position == keys.lastIndex) {
                when (container) {
                    is JSONObject -> if (deleting) container.remove(key) else container.put(key, value)
                    is JSONArray -> if (deleting) { if (index < container.length()) container.remove(index) } else {
                        while (container.length() < index) container.put(JSONObject.NULL)
                        container.put(index, value)
                    }
                    else -> throw SyncRequired()
                }
                return
            }
            var next: Any? = when (container) {
                is JSONObject -> container.opt(key)
                is JSONArray -> container.opt(index)
                else -> throw SyncRequired()
            }
            if (next == null || next === JSONObject.NULL) {
                if (deleting) return
                next = if (Regex("0|[1-9][0-9]*").matches(keys[position + 1])) JSONArray() else JSONObject()
                if (container is JSONObject) container.put(key, next) else {
                    container as JSONArray
                    while (container.length() < index) container.put(JSONObject.NULL)
                    container.put(index, next)
                }
            }
            if (next !is JSONObject && next !is JSONArray) throw SyncRequired()
            parent = next
        }
    }

    fun replay(document: JSONObject): List<String> {
        val surfaces = document.getJSONObject("surfaces")
        return surfaces.keys().asSequence().sorted().map { id ->
            val surface = surfaces.getJSONObject(id)
            val create = JSONObject().put("surfaceId", id).put("dataModel", surface.getJSONObject("dataModel"))
            listOf("catalogId", "sendDataModel", "metadata").forEach { key -> if (surface.has(key)) create.put(key, surface.get(key)) }
            val stored = surface.getJSONObject("components")
            if (stored.length() > 0) create.put("components", JSONArray(stored.keys().asSequence().sorted().map { stored.getJSONObject(it) }.toList()))
            JSONObject().put("version", "1.0").put("createSurface", create).toString()
        }.toList().also { if (it.size > MAX_MESSAGES) throw SyncRequired() }
    }

    fun restore(replay: List<String>): JSONObject {
        if (JSONArray(replay.map(::JSONObject)).toString().toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) throw SyncRequired()
        val messages = replay.map(::JSONObject)
        if (messages.any { validateMessage(it, wire = false) != "createSurface" }) throw SyncRequired()
        return apply(empty(), messages, wire = false)
    }
}
