package com.tencent.kuikly.a2ui.adapter

import com.tencent.kuikly.a2ui.bridge.A2UIBridgeManager
import com.tencent.kuikly.a2ui.dsl.A2UIDSLBridge
import com.tencent.kuikly.a2ui.function.A2UICustomFunctionRegistry
import com.tencent.kuikly.a2ui.protocol.A2UIMessage
import com.tencent.kuikly.a2ui.protocol.A2UIMessageType
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2UIActionDispatchTest {
    private val catalog = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"
    private val fixedTime = 1709210096789L

    private fun JSONObject.getString(key: String): String {
        assertTrue(has(key), "Missing JSON field: $key")
        return optString(key)
    }
    private fun JSONObject.getJSONObject(key: String): JSONObject = assertNotNull(optJSONObject(key))
    private fun JSONObject.getJSONArray(key: String) = assertNotNull(optJSONArray(key))

    private fun adapter(surface: String = "form-surface", data: Map<String, Any?> = emptyMap()) =
        A2UIProtocolAdapter().apply {
            strictV10 = true
            actionClock = { fixedTime }
            convertMessage(mapOf("version" to "1.0", "createSurface" to mapOf(
                "surfaceId" to surface, "catalogId" to catalog, "dataModel" to data,
            )))
        }

    private fun update(adapter: A2UIProtocolAdapter, components: List<Map<String, Any?>>): List<A2UIMessage> =
        adapter.convertMessage(mapOf("version" to "1.0", "updateComponents" to mapOf(
            "surfaceId" to adapter.surfaceId, "components" to components,
        )))

    private fun button(action: Map<String, Any?>, extra: Map<String, Any?> = emptyMap(), id: String = "submit__declared") =
        mapOf("id" to id, "component" to "Button", "child" to "label", "action" to action) + extra

    private fun mount(adapter: A2UIProtocolAdapter, action: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()): Int {
        update(adapter, listOf(button(action, extra), mapOf("id" to "label", "component" to "Text", "text" to "Submit")))
        return adapter.getActionTags().single()
    }

    private fun event(context: Any? = emptyMap<String, Any?>()) = mapOf("event" to mapOf("name" to "submit", "context" to context))
    private fun payload(adapter: A2UIProtocolAdapter, tag: Int): JSONObject {
        val envelope = JSONObject(assertNotNull(adapter.getDispatchActionForTag(tag)))
        assertEquals("1.0", envelope.getString("version"))
        return envelope.getJSONObject("action")
    }

    @Test
    fun rawApiIsStableAndDispatchHasExactMetadata() {
        val adapter = adapter(data = mapOf("name" to "Alice"))
        val tag = mount(adapter, event(mapOf("name" to mapOf("@path" to "/name"))))
        val raw = "{\"event\":{\"name\":\"submit\",\"context\":{\"name\":\"Alice\"}}}"
        assertEquals(raw, adapter.getActionForTag(tag))
        assertEquals(
            "{\"version\":\"1.0\",\"action\":{\"name\":\"submit\",\"surfaceId\":\"form-surface\",\"sourceComponentId\":\"submit__declared\",\"timestamp\":\"2024-02-29T12:34:56.789Z\",\"context\":{\"name\":\"Alice\"}}}",
            adapter.getDispatchActionForTag(tag),
        )
        assertEquals(raw, adapter.getActionForTag(tag))
    }

    @Test
    fun twoAdaptersWithSameTagKeepSurfaceIdentityAndLiveInputsIsolated() {
        val first = adapter("first", mapOf("input" to "old"))
        val second = adapter("second", mapOf("input" to "other"))
        val firstTag = mount(first, event(mapOf("value" to mapOf("@path" to "/input"))))
        val secondTag = mount(second, event(mapOf("value" to mapOf("@path" to "/input"))))
        assertEquals(firstTag, secondTag)
        first.updateDataModelValue("input", "latest")
        assertEquals("first", payload(first, firstTag).getString("surfaceId"))
        assertEquals("latest", payload(first, firstTag).getJSONObject("context").getString("value"))
        assertEquals("second", payload(second, secondTag).getString("surfaceId"))
        assertEquals("other", payload(second, secondTag).getJSONObject("context").getString("value"))
    }

    @Test
    fun userMessageAndNestedContextResolveAtClick() {
        val adapter = adapter(data = mapOf("form" to mapOf("name" to "old", "checked" to true)))
        val tag = mount(adapter, mapOf("event" to mapOf(
            "name" to "submit", "userMessage" to mapOf("@path" to "/form/name"),
            "context" to mapOf("form" to mapOf("@path" to "/form"), "nested" to listOf(mapOf("@path" to "/form/checked"))),
        )))
        adapter.updateDataModelValue("form/name", "new")
        val action = payload(adapter, tag)
        assertEquals("new", action.getString("userMessage"))
        assertEquals("new", action.getJSONObject("context").getJSONObject("form").getString("name"))
        assertTrue(action.getJSONObject("context").getJSONArray("nested").optBoolean(0))
    }

    @Test
    fun missingContextDefaultsToObjectAndUserMessageRemainsOptional() {
        val adapter = adapter()
        val tag = mount(adapter, mapOf("event" to mapOf("name" to "submit")))
        val action = payload(adapter, tag)
        assertEquals("{}", action.getJSONObject("context").toString())
        assertFalse(action.has("userMessage"))
        assertEquals("{\"event\":{\"name\":\"submit\"}}", adapter.getActionForTag(tag))
    }

    @Test
    fun declaredInvalidContextIsRejectedWithoutChangingRawApi() {
        for (invalid in listOf(null, "not-an-object", 7, false, listOf("value"))) {
            val adapter = adapter(data = mapOf("invalid" to invalid))
            val tag = mount(adapter, event(mapOf("@path" to "/invalid")))
            val raw = adapter.getActionForTag(tag)
            assertNotNull(raw)
            assertNull(adapter.getDispatchActionForTag(tag), "Invalid resolved context: $invalid")
            assertEquals(raw, adapter.getActionForTag(tag))
        }
        val adapter = adapter()
        val tag = mount(adapter, event(null))
        assertNull(adapter.getDispatchActionForTag(tag), "Explicit null context is not an omitted context")
    }

    @Test
    fun declaredInvalidUserMessageIsRejectedWithoutChangingRawApi() {
        for (invalid in listOf(null, 7, false, listOf("value"), mapOf("value" to "text"))) {
            val adapter = adapter(data = mapOf("invalid" to invalid))
            val tag = mount(adapter, mapOf("event" to mapOf(
                "name" to "submit", "userMessage" to mapOf("@path" to "/invalid"),
            )))
            val raw = adapter.getActionForTag(tag)
            assertNotNull(raw)
            assertNull(adapter.getDispatchActionForTag(tag), "Invalid resolved userMessage: $invalid")
            assertEquals(raw, adapter.getActionForTag(tag))
        }
        val adapter = adapter()
        val tag = mount(adapter, mapOf("event" to mapOf("name" to "submit", "userMessage" to null)))
        assertNull(adapter.getDispatchActionForTag(tag))
    }

    @Test
    fun disabledAndUnknownTagsCannotDispatchUntilChecksPass() {
        val adapter = adapter(data = mapOf("enabled" to false))
        val tag = mount(adapter, event(), mapOf("@checks" to listOf(mapOf(
            "condition" to mapOf("@path" to "/enabled"), "message" to "Disabled",
        ))))
        assertNull(adapter.getDispatchActionForTag(tag))
        assertNull(adapter.getActionForTag(tag))
        assertNull(adapter.getDispatchActionForTag(Int.MAX_VALUE))
        adapter.updateDataModelValue("enabled", true)
        assertNotNull(adapter.getDispatchActionForTag(tag))
    }

    @Test
    fun deletedSubtreeAndResetRejectStaleClicks() {
        val adapter = adapter()
        update(adapter, listOf(
            mapOf("id" to "root", "component" to "Column", "children" to listOf("row")),
            mapOf("id" to "row", "component" to "Row", "children" to listOf("submit__declared")),
            button(event()), mapOf("id" to "label", "component" to "Text", "text" to "Submit"),
        ))
        val tag = adapter.getActionTags().single()
        assertNotNull(adapter.getDispatchActionForTag(tag))
        update(adapter, listOf(mapOf("id" to "root", "component" to "Column", "children" to emptyList<String>())))
        assertNull(adapter.getDispatchActionForTag(tag))
        adapter.reset()
        assertNull(adapter.getDispatchActionForTag(tag))
        assertTrue(adapter.getActionTags().isEmpty())
    }

    @Test
    fun removingActionDefinitionRemovesRegistration() {
        val adapter = adapter()
        val tag = mount(adapter, event())
        update(adapter, listOf(mapOf("id" to "submit__declared", "component" to "Button", "child" to "label")))
        assertNull(adapter.getDispatchActionForTag(tag))
        assertNull(adapter.getActionForTag(tag))
        assertTrue(adapter.getActionTags().isEmpty())
    }

    @Test
    fun functionCallStaysTopLevelAndDoesNotExecuteInAdapter() {
        val name = "dispatchVoid"
        A2UICustomFunctionRegistry.register(name, "void|sync")
        try {
            val adapter = adapter(data = mapOf("input" to "before"))
            var calls = 0
            adapter.customFunctionInvoker = { _, _, _ -> calls++; null }
            val tag = mount(adapter, mapOf("functionCall" to mapOf("@call" to name, "args" to mapOf("value" to mapOf("@path" to "/input")))))
            adapter.updateDataModelValue("input", "after")
            assertEquals(
                "{\"functionCall\":{\"@call\":\"dispatchVoid\",\"args\":{\"value\":\"after\"}}}",
                adapter.getDispatchActionForTag(tag),
            )
            assertEquals(0, calls)
        } finally {
            A2UICustomFunctionRegistry.unregister(name)
        }
    }

    @Test
    fun nestedExpressionFunctionsAreEvaluatedOnceIncludingNullResult() {
        val inner = "dispatchInner"
        val outer = "dispatchOuter"
        A2UICustomFunctionRegistry.register(inner, "string|sync")
        A2UICustomFunctionRegistry.register(outer, "string|sync")
        try {
            for (result in listOf("resolved", null)) {
                val adapter = adapter(data = mapOf("value" to "live"))
                val calls = mutableListOf<String>()
                adapter.customFunctionInvoker = { name, _, _ -> calls.add(name); if (name == inner) result else "outer" }
                val tag = mount(adapter, event(mapOf("value" to mapOf("@call" to outer, "args" to mapOf(
                    "nested" to mapOf("@call" to inner, "args" to mapOf("value" to mapOf("@path" to "/value"))),
                )))))
                calls.clear()
                assertEquals("outer", payload(adapter, tag).getJSONObject("context").getString("value"))
                assertEquals(listOf(inner, outer), calls)
            }
        } finally {
            A2UICustomFunctionRegistry.unregister(inner)
            A2UICustomFunctionRegistry.unregister(outer)
        }
    }

    private fun template(adapter: A2UIProtocolAdapter, action: Map<String, Any?>): List<Int> {
        val messages = update(adapter, listOf(
            mapOf("id" to "root", "component" to "Column", "children" to listOf("list")),
            mapOf("id" to "list", "component" to "List", "children" to mapOf("componentId" to "row", "path" to "/rows")),
            mapOf("id" to "row", "component" to "Row", "children" to listOf("spacer", "tap__declared")),
            mapOf("id" to "spacer", "component" to "Text", "text" to "Spacer"),
            button(action, id = "tap__declared"), mapOf("id" to "label", "component" to "Text", "text" to "Submit"),
        ))
        return messages.filter { it.type == A2UIMessageType.CREATE_COMPONENT && it.tag in adapter.getActionTags() }.map { it.tag }
    }

    @Test
    fun templateUsesLiveScopeAndArrayIndexNotSiblingSuffix() {
        val adapter = adapter(data = mapOf("rootValue" to "root", "rows" to listOf(mutableMapOf("value" to "first"), mutableMapOf("value" to "second"))))
        val tags = template(adapter, mapOf("event" to mapOf(
            "name" to "select", "userMessage" to mapOf("@path" to "value"),
            "context" to mapOf("value" to mapOf("@path" to "value"), "root" to mapOf("@path" to "/rootValue"),
                "index" to mapOf("@call" to "@index"), "offset" to mapOf("@call" to "@index", "args" to mapOf("offset" to 2)),
                "formatted" to mapOf("@call" to "formatString", "args" to mapOf("value" to "\${value}/\${/rootValue}"))),
        )))
        assertEquals(2, tags.size)
        adapter.updateDataModelValue("rows/0/value", "latest-first")
        adapter.updateDataModelValue("rootValue", "latest-root")
        val first = payload(adapter, tags[0])
        assertEquals("tap__declared", first.getString("sourceComponentId"))
        assertEquals("latest-first", first.getString("userMessage"))
        assertEquals("latest-first", first.getJSONObject("context").getString("value"))
        assertEquals("latest-root", first.getJSONObject("context").getString("root"))
        assertEquals("latest-first/latest-root", first.getJSONObject("context").getString("formatted"))
        assertEquals("0", first.getJSONObject("context").getString("index"))
        assertEquals("2", first.getJSONObject("context").getString("offset"))
        assertEquals("1", payload(adapter, tags[1]).getJSONObject("context").getString("index"))
    }

    @Test
    fun templateRemovalRejectsNestedStaleAction() {
        val adapter = adapter(data = mapOf("rows" to listOf(mapOf("value" to "one"), mapOf("value" to "two"))))
        val tags = template(adapter, event(mapOf("value" to mapOf("@path" to "value"))))
        adapter.convertMessage(mapOf("version" to "1.0", "updateDataModel" to mapOf(
            "surfaceId" to adapter.surfaceId, "path" to "/rows", "value" to listOf(mapOf("value" to "one")),
        )))
        assertNotNull(adapter.getDispatchActionForTag(tags[0]))
        assertNull(adapter.getDispatchActionForTag(tags[1]))
    }

    @Test
    fun nestedTemplatesUseDeepestArrayIndexAndLiveRelativeScope() {
        val adapter = adapter(data = mapOf("rows" to mutableListOf(
            mutableMapOf("items" to mutableListOf(mutableMapOf("value" to "a"), mutableMapOf("value" to "b"))),
            mutableMapOf("items" to mutableListOf(mutableMapOf("value" to "c"))),
        )))
        val action = event(mapOf("value" to mapOf("@path" to "value"), "index" to mapOf("@call" to "@index")))
        update(adapter, listOf(
            mapOf("id" to "root", "component" to "List", "children" to mapOf("componentId" to "outer", "path" to "/rows")),
            mapOf("id" to "outer", "component" to "List", "children" to mapOf("componentId" to "inner", "path" to "items")),
            mapOf("id" to "inner", "component" to "Row", "children" to listOf("spacer", "tap__declared")),
            mapOf("id" to "spacer", "component" to "Text", "text" to "Spacer"),
            button(action, id = "tap__declared"), mapOf("id" to "label", "component" to "Text", "text" to "Submit"),
        ))
        val tags = adapter.getActionTags().sorted()
        assertEquals(3, tags.size)
        adapter.updateDataModelValue("rows/0/items/1/value", "latest-b")
        assertEquals(listOf("a", "latest-b", "c"), tags.map { payload(adapter, it).getJSONObject("context").getString("value") })
        assertEquals(listOf("0", "1", "0"), tags.map { payload(adapter, it).getJSONObject("context").getString("index") })
        assertTrue(tags.all { payload(adapter, it).getString("sourceComponentId") == "tap__declared" })
    }

    @Test
    fun templateCustomCallUsesLatestValueAndExecutesOnce() {
        val name = "dispatchScoped"
        A2UICustomFunctionRegistry.register(name, "string|sync")
        try {
            val adapter = adapter(data = mapOf("rows" to mutableListOf(mutableMapOf("value" to "old"))))
            val calls = mutableListOf<String>()
            adapter.customFunctionInvoker = { _, args, _ -> calls.add(args); "resolved" }
            val tag = template(adapter, event(mapOf("value" to mapOf("@call" to name, "args" to mapOf(
                "value" to mapOf("@path" to "value"), "index" to mapOf("@call" to "@index"),
            ))))).single()
            adapter.updateDataModelValue("rows/0/value", "latest")
            calls.clear()
            assertEquals("resolved", payload(adapter, tag).getJSONObject("context").getString("value"))
            assertEquals(1, calls.size)
            val args = JSONObject(calls.single())
            assertEquals("latest", args.getString("value"))
            assertEquals("0", args.getString("index"))
        } finally {
            A2UICustomFunctionRegistry.unregister(name)
        }
    }

    @Test
    fun v09RawShapeStaysStableWhileCallbackUsesV1Envelope() {
        val adapter = A2UIProtocolAdapter().apply { actionClock = { fixedTime } }
        adapter.convertMessage(mapOf("version" to "0.9", "createSurface" to mapOf("surfaceId" to "legacy")))
        adapter.updateDataModelValue("input", "live")
        adapter.convertMessage(mapOf("version" to "0.9", "updateComponents" to mapOf("surfaceId" to "legacy", "components" to listOf(
            button(mapOf("event" to mapOf("name" to "legacy-submit", "context" to mapOf("value" to mapOf("path" to "/input"))))),
            mapOf("id" to "label", "component" to "Text", "text" to "Submit"),
        ))))
        val tag = adapter.getActionTags().single()
        assertEquals("{\"event\":{\"name\":\"legacy-submit\",\"context\":{\"value\":\"live\"}}}", adapter.getActionForTag(tag))
        assertEquals("legacy", payload(adapter, tag).getString("surfaceId"))
        assertEquals("legacy-submit", payload(adapter, tag).getString("name"))
    }

    @Test
    fun dslClickForwardsRealSurfaceAndResolvedEnvelopeOnce() {
        val adapter = adapter("dsl-real", mapOf("input" to "old"))
        val tag = mount(adapter, event(mapOf("value" to mapOf("@path" to "/input"))))
        val bridge = A2UIDSLBridge("dispatch-dsl", null).apply { protocolAdapter = adapter }
        val callbacks = mutableListOf<Triple<String, Int, String>>()
        bridge.onAction = { surface, callbackTag, action -> callbacks.add(Triple(surface, callbackTag, action)) }
        adapter.updateDataModelValue("input", "latest")
        bridge.handleViewClick(tag)
        assertEquals(1, callbacks.size)
        assertEquals("dsl-real", callbacks.single().first)
        assertEquals(tag, callbacks.single().second)
        assertEquals("latest", JSONObject(callbacks.single().third).getJSONObject("action").getJSONObject("context").getString("value"))
    }

    @Test
    fun dslFunctionCallRemainsLocallyInterceptable() {
        val instance = "dispatch-function-dsl"
        val adapter = adapter()
        val tag = mount(adapter, mapOf("functionCall" to mapOf("@call" to "openUrl", "args" to mapOf("url" to "https://example.com"))))
        val bridge = A2UIDSLBridge(instance, null).apply { protocolAdapter = adapter }
        var intercepted = 0
        var forwarded = 0
        A2UIBridgeManager.registerDslBridge(instance, bridge)
        try {
            bridge.onAction = { _, _, action ->
                if (A2UIBridgeManager.tryHandleFunctionCall(instance, action)) intercepted++ else forwarded++
            }
            bridge.handleViewClick(tag)
            assertEquals(1, intercepted)
            assertEquals(0, forwarded)
        } finally {
            A2UIBridgeManager.unregisterDslBridge(instance)
        }
    }

    @Test
    fun timestampFormatterHandlesEpochLeapDayAndCenturyBoundary() {
        assertEquals("1970-01-01T00:00:00.000Z", formatActionTimestamp(0))
        assertEquals("1969-12-31T23:59:59.999Z", formatActionTimestamp(-1))
        assertEquals("2000-02-29T00:00:00.000Z", formatActionTimestamp(951782400000))
        assertEquals("2100-03-01T00:00:00.000Z", formatActionTimestamp(4107542400000))
        assertEquals("2024-02-29T12:34:56.789Z", formatActionTimestamp(fixedTime))
    }

    @Test
    fun defaultClockUsesCurrentUtcEpochAtDispatch() {
        val adapter = adapter()
        adapter.actionClock = DateTime::currentTimestamp
        val tag = mount(adapter, event())
        val before = DateTime.currentTimestamp()
        val timestamp = payload(adapter, tag).getString("timestamp")
        val after = DateTime.currentTimestamp()
        assertTrue((before..after).any { formatActionTimestamp(it) == timestamp })
    }
}
