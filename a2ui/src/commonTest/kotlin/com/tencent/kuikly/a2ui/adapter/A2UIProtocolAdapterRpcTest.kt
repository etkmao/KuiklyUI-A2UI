/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI-A2UI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.a2ui.adapter

import com.tencent.kuikly.a2ui.function.A2UICustomFunctionRegistry
import com.tencent.kuikly.a2ui.protocol.A2UIProtocolVersion
import com.tencent.kuikly.a2ui.rpc.A2UIRpcError
import com.tencent.kuikly.a2ui.rpc.A2UIRpcRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the v1.0 bidirectional-RPC envelopes inside
 * [A2UIProtocolAdapter]. Exercises the parse \u2192 dispatch \u2192 emit path
 * without reaching into the BridgeManager / NativeBridge layer.
 *
 * The suite PINS the adapter to v1.0 via [A2UIProtocolAdapter.setExpectedProtocolVersion]
 * so there is no ambiguity about which dispatch branch fires.
 */
class A2UIProtocolAdapterRpcTest {

    private fun newAdapter(strict: Boolean = false): A2UIProtocolAdapter {
        val adapter = A2UIProtocolAdapter()
        adapter.setExpectedProtocolVersion(A2UIProtocolVersion.V1_0)
        adapter.strictV10 = strict
        if (strict) {
            A2UICustomFunctionRegistry.register("getGreeting", """{"catalogId":"custom_functions_catalog_v1_0.json","returnType":"string","allowedCallers":"rendererOrAgent","args":{"properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}}""")
            A2UICustomFunctionRegistry.register("fetchUser", """{"catalogId":"custom_functions_catalog_v1_0.json","returnType":"object","allowedCallers":"agentOnly","args":{"properties":{"id":{"type":"number"}},"required":["id"],"additionalProperties":false}}""")
        }
        // Seed a surface so wire-level surfaceId validation passes.
        // A minimal createSurface envelope is enough.
        adapter.convertMessage(
            mapOf(
                "version" to "1.0",
                "createSurface" to mapOf(
                    "surfaceId" to "s1",
                    "catalogId" to "custom_functions_catalog_v1_0.json",
                )
            )
        )
        return adapter
    }

    // Capture everything the adapter wants to emit so tests can assert
    // on wire-out behaviour without a real NativeBridge.
    private class WireSink {
        val envelopes = mutableListOf<String>()
        val emitLambda: (String) -> Unit = { envelopes += it }
    }

    // --- Inbound RPC ------------------------------------------------------

    @Test
    fun inbound_callRendererFunction_routesToHandler() {
        val adapter = newAdapter()
        val received = mutableListOf<A2UIRpcRequest>()
        adapter.onIncomingAgentCall = { received += it }

        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "callRendererFunction" to mapOf(
                "surfaceId" to "s1",
                "requestId" to "r-100",
                "name" to "openPage",
                "args" to mapOf("path" to "/profile"),
            )
        ))

        assertEquals(1, received.size)
        val req = received[0]
        assertEquals(A2UIRpcRequest.Direction.AGENT_TO_RENDERER, req.direction)
        assertEquals("s1", req.surfaceId)
        assertEquals("r-100", req.requestId)
        assertEquals("openPage", req.name)
        assertEquals(mapOf("path" to "/profile"), req.args)
        assertTrue(adapter.rpcDispatcher.hasPending("r-100"))
    }

    @Test
    fun inbound_callRendererFunction_withoutHandler_autoFailsWithUnknownFunction() {
        val adapter = newAdapter()
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        // No onIncomingAgentCall registered.

        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "callRendererFunction" to mapOf(
                "surfaceId" to "s1",
                "requestId" to "r-200",
                "name" to "noop",
                "args" to emptyMap<String, Any?>(),
            )
        ))

        // Adapter must have synthesised a rendererFunctionResponse carrying
        // UNKNOWN_FUNCTION so the agent isn't left waiting.
        assertEquals(1, sink.envelopes.size)
        val out = sink.envelopes[0]
        assertTrue(out.contains("rendererFunctionResponse"))
        assertTrue(out.contains("UNKNOWN_FUNCTION"))
        assertTrue(out.contains("r-200"))
        assertFalse(adapter.rpcDispatcher.hasPending("r-200"))
    }

    @Test
    fun inbound_callRendererFunction_surfaceMismatch_isRejectedSilently() {
        val adapter = newAdapter()
        val received = mutableListOf<A2UIRpcRequest>()
        adapter.onIncomingAgentCall = { received += it }

        // surfaceId "other" does NOT match the seeded "s1".
        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "callRendererFunction" to mapOf(
                "surfaceId" to "other",
                "requestId" to "r-300",
                "name" to "foo",
                "args" to emptyMap<String, Any?>(),
            )
        ))

        assertTrue(received.isEmpty())
        assertFalse(adapter.rpcDispatcher.hasPending("r-300"))
    }

    @Test
    fun inbound_callRendererFunction_missingName_isRejected() {
        val adapter = newAdapter()
        val received = mutableListOf<A2UIRpcRequest>()
        adapter.onIncomingAgentCall = { received += it }

        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "callRendererFunction" to mapOf(
                "surfaceId" to "s1",
                "requestId" to "r-400",
                // "name" missing
                "args" to emptyMap<String, Any?>(),
            )
        ))

        assertTrue(received.isEmpty())
    }

    // --- Outbound RPC -----------------------------------------------------

    @Test
    fun respondToAgent_emitsRendererFunctionResponseEnvelope() {
        val adapter = newAdapter()
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        // Seed a pending via inbound call.
        adapter.onIncomingAgentCall = { }
        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "callRendererFunction" to mapOf(
                "surfaceId" to "s1", "requestId" to "r-500",
                "name" to "f", "args" to emptyMap<String, Any?>(),
            )
        ))
        sink.envelopes.clear()

        adapter.respondToAgent("s1", "r-500", result = mapOf("ok" to true))

        assertEquals(1, sink.envelopes.size)
        val out = sink.envelopes[0]
        assertTrue(out.contains("rendererFunctionResponse"))
        assertTrue(out.contains("\"requestId\":\"r-500\""))
        assertTrue(out.contains("\"result\""))
        assertFalse(adapter.rpcDispatcher.hasPending("r-500"))
    }

    @Test
    fun invokeAgentFunction_emitsCallAgentFunctionAndCorrelatesResponse() {
        val adapter = newAdapter()
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda

        var received: Any? = null
        var completed = false
        val reqId = adapter.invokeAgentFunction(
            surfaceId = "s1",
            name = "fetchUser",
            args = mapOf("id" to 7),
            timeoutMs = null,
            onComplete = {
                completed = true
                received = it.result
            },
        )

        // Minted id is non-empty and the outbound envelope is correct.
        assertNotNull(reqId)
        assertEquals(1, sink.envelopes.size)
        assertTrue(sink.envelopes[0].contains("callAgentFunction"))
        assertTrue(sink.envelopes[0].contains("\"name\":\"fetchUser\""))

        // Simulate the agent reply arriving on the stream.
        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "agentFunctionResponse" to mapOf(
                "surfaceId" to "s1",
                "requestId" to reqId,
                "result" to mapOf("name" to "alice"),
            )
        ))

        assertTrue(completed)
        @Suppress("UNCHECKED_CAST")
        assertEquals("alice", (received as Map<String, Any?>)["name"])
    }

    @Test
    fun agentFunctionResponse_withBothResultAndError_isTreatedAsWireFormatError() {
        val adapter = newAdapter()
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda

        var error: A2UIRpcError? = null
        val reqId = adapter.invokeAgentFunction(
            surfaceId = "s1",
            name = "foo",
            args = emptyMap(),
            timeoutMs = null,
            onComplete = { error = it.error },
        )

        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "agentFunctionResponse" to mapOf(
                "surfaceId" to "s1",
                "requestId" to reqId,
                "result" to "oops",
                "error" to mapOf("code" to "X"),
            )
        ))

        // Dispatcher's parseRpcResponse emits a WIRE_FORMAT_ERROR response
        // so callers never hang on malformed replies.
        assertNotNull(error)
        assertEquals(A2UIRpcError.Codes.WIRE_FORMAT_ERROR, error!!.code)
    }

    @Test
    fun reset_cancelsAllPendingRpcsWithSurfaceClosed() {
        val adapter = newAdapter()
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda

        var received: A2UIRpcError? = null
        adapter.invokeAgentFunction(
            surfaceId = "s1",
            name = "slow",
            args = emptyMap(),
            timeoutMs = null,
            onComplete = { received = it.error },
        )
        assertEquals(1, adapter.rpcDispatcher.pendingCountFor())

        adapter.reset()

        assertEquals(0, adapter.rpcDispatcher.pendingCountFor())
        assertNotNull(received)
        assertEquals(A2UIRpcError.Codes.SURFACE_CLOSED, received!!.code)
    }

    @Test
    fun strict_officialRequest_allowsSurfaceIndependentContext_andEmitsOfficialResponse() {
        val adapter = newAdapter(strict = true)
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        var received: A2UIRpcRequest? = null
        adapter.onIncomingAgentCall = { received = it }
        adapter.convertMessage(mapOf("version" to "1.0", "callRendererFunction" to mapOf(
            "functionCallId" to "official-1",
            "callFunction" to mapOf("@call" to "getGreeting",
                "catalogId" to "custom_functions_catalog_v1_0.json", "args" to mapOf("name" to "Ada")))))
        val request = assertNotNull(received)
        assertEquals("", request.surfaceId)
        adapter.respondToAgent(request.surfaceId, request.requestId, "Hello, Ada!")
        val wire = sink.envelopes.single()
        assertTrue(wire.contains("\"functionCallId\":\"official-1\""))
        assertTrue(wire.contains("\"value\":\"Hello, Ada!\""))
        assertFalse(wire.contains("surfaceId"))
        assertFalse(wire.contains("requestId"))
        assertFalse(wire.contains("result"))
    }

    @Test
    fun strict_rejectsLegacyRequestAliases() {
        val adapter = newAdapter(strict = true)
        var called = false
        adapter.onIncomingAgentCall = { called = true }
        adapter.convertMessage(mapOf("version" to "1.0", "callRendererFunction" to mapOf(
            "surfaceId" to "s1", "requestId" to "legacy", "name" to "getGreeting", "args" to emptyMap<String, Any?>())))
        assertFalse(called)
        assertFalse(adapter.rpcDispatcher.hasPending("legacy"))
    }

    @Test
    fun strict_outboundOfficialCall_correlatesNullValueWithoutSurfaceId() {
        val adapter = newAdapter(strict = true)
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        var completed = false
        val id = adapter.invokeAgentFunction("s1", "fetchUser", mapOf("id" to 42), 1000) {
            completed = true
            assertNull(it.result)
            assertNull(it.error)
        }
        val wire = sink.envelopes.single()
        assertTrue(wire.contains("\"functionCallId\""))
        assertTrue(wire.contains("\"callFunction\""))
        assertTrue(wire.contains("\"@call\":\"fetchUser\""))
        assertFalse(wire.contains("timeoutMs"))
        assertFalse(wire.contains("requestId"))
        adapter.convertMessage(mapOf("version" to "1.0", "agentFunctionResponse" to mapOf(
            "functionCallId" to id, "value" to null)))
        assertTrue(completed)
    }

    @Test
    fun strict_catalogGate_rejectsUnknownCatalogAndDoesNotFallbackToGlobal() {
        val adapter = newAdapter(strict = true)
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        var called = false
        adapter.onIncomingAgentCall = { called = true }
        A2UICustomFunctionRegistry.register("globalOnlyRpc", "string|sync")
        fun call(name: String, catalog: String, id: String) {
            adapter.convertMessage(mapOf("version" to "1.0", "callRendererFunction" to mapOf(
                "functionCallId" to id, "callFunction" to mapOf("@call" to name,
                    "catalogId" to catalog, "args" to emptyMap<String, Any?>()))))
        }
        call("globalOnlyRpc", "unknown_catalog.json", "unknown_catalog")
        call("globalOnlyRpc", "custom_functions_catalog_v1_0.json", "unknown_function")
        assertFalse(called)
        assertTrue(sink.envelopes[0].contains("UNKNOWN_CATALOG"))
        assertTrue(sink.envelopes[1].contains("UNKNOWN_FUNCTION"))
        assertEquals(0, adapter.rpcDispatcher.pendingCountFor())
        assertNotNull(A2UICustomFunctionRegistry.lookup("globalOnlyRpc", "unknown_catalog.json"))
        assertNull(A2UICustomFunctionRegistry.lookupExact("globalOnlyRpc", "unknown_catalog.json"))
        A2UICustomFunctionRegistry.unregister("globalOnlyRpc")
    }

    @Test
    fun strict_catalogGate_rejectsAgentOnlyAndInvalidArgumentsOnRenderer() {
        val adapter = newAdapter(strict = true)
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        var called = false
        adapter.onIncomingAgentCall = { called = true }
        fun call(name: String, args: Map<String, Any?>, id: String) {
            adapter.convertMessage(mapOf("version" to "1.0", "callRendererFunction" to mapOf(
                "functionCallId" to id, "callFunction" to mapOf("@call" to name,
                    "catalogId" to "custom_functions_catalog_v1_0.json", "args" to args))))
        }
        call("fetchUser", mapOf("id" to 42), "wrong_direction")
        call("getGreeting", mapOf("name" to 42), "wrong_type")
        call("getGreeting", emptyMap(), "missing_argument")
        call("getGreeting", mapOf("name" to "Ada", "extra" to true), "extra_argument")
        assertFalse(called)
        assertTrue(sink.envelopes.first().contains("FUNCTION_NOT_ALLOWED"))
        assertTrue(sink.envelopes.drop(1).all { it.contains("INVALID_ARGS") })
        assertTrue(sink.envelopes.all { !it.contains("surfaceId") })
        assertEquals(0, adapter.rpcDispatcher.pendingCountFor())
    }

    @Test
    fun strict_catalogGate_outboundRejectsInvalidArgumentsWithoutWireOrPendingLeak() {
        val adapter = newAdapter(strict = true)
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        var code: String? = null
        adapter.invokeAgentFunction("s1", "fetchUser", mapOf("id" to "42"), null) { code = it.error?.code }
        assertEquals(A2UIRpcError.Codes.INVALID_ARGS, code)
        assertTrue(sink.envelopes.isEmpty())
        assertEquals(0, adapter.rpcDispatcher.pendingCountFor())
        adapter.invokeAgentFunction("other", "fetchUser", mapOf("id" to 42), null) { code = it.error?.code }
        assertEquals(A2UIRpcError.Codes.INVALID_ARGS, code)
        assertTrue(sink.envelopes.isEmpty())
    }

    @Test
    fun strict_catalogGate_activationAndRendererOnlyCannotBeInvokedByAgent() {
        val adapter = newAdapter(strict = true)
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda
        var called = false
        adapter.onIncomingAgentCall = { called = true }
        A2UICustomFunctionRegistry.register("trustedRpc", """{"catalogId":"custom_functions_catalog_v1_0.json","returnType":"void","allowedCallers":"rendererOrAgent","requiresUserActivation":true}""")
        A2UICustomFunctionRegistry.register("rendererOnlyRpc", """{"catalogId":"custom_functions_catalog_v1_0.json","returnType":"void","allowedCallers":"rendererOnly"}""")
        for (name in listOf("trustedRpc", "rendererOnlyRpc")) {
            adapter.convertMessage(mapOf("version" to "1.0", "callRendererFunction" to mapOf(
                "functionCallId" to name, "callFunction" to mapOf("@call" to name,
                    "catalogId" to "custom_functions_catalog_v1_0.json", "args" to emptyMap<String, Any?>()))))
        }
        assertFalse(called)
        assertTrue(sink.envelopes[0].contains("USER_ACTIVATION_REQUIRED"))
        assertTrue(sink.envelopes[1].contains("FUNCTION_NOT_ALLOWED"))
        A2UICustomFunctionRegistry.unregister("trustedRpc", "custom_functions_catalog_v1_0.json")
        A2UICustomFunctionRegistry.unregister("rendererOnlyRpc", "custom_functions_catalog_v1_0.json")
    }

    @Test
    fun catalogMetadata_parseAndQualifiedSnapshotPreserveLegacyExecutionSpec() {
        val spec = """{"name":"snapshotRpc","catalogId":"snapshot_catalog.json","returnType":"string","invokeMode":"async","allowedCallers":"rendererOrAgent","args":{"properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}}"""
        assertTrue(A2UICustomFunctionRegistry.register("snapshot_catalog.json::snapshotRpc", spec))
        val descriptor = assertNotNull(A2UICustomFunctionRegistry.lookupExact("snapshotRpc", "snapshot_catalog.json"))
        assertEquals("snapshotRpc", descriptor.name)
        assertEquals("string|sync", descriptor.toSpec())
        assertNull(descriptor.validateArguments(mapOf("name" to "Ada")))
        assertNotNull(descriptor.validateArguments(mapOf("name" to null)))
        assertFalse(A2UICustomFunctionRegistry.register("snapshotRpc", """{"catalogId":"snapshot_catalog.json","returnType":"string","allowedCallers":"invalid"}"""))
        A2UICustomFunctionRegistry.unregister("snapshotRpc", "snapshot_catalog.json")
    }

    // --- Outbound-only keys appearing on inbound path are ignored ---------

    @Test
    fun inbound_rendererFunctionResponse_isIgnored() {
        val adapter = newAdapter()
        val sink = WireSink()
        adapter.emitWireMessage = sink.emitLambda

        // This envelope is outbound-only from renderer's POV.
        adapter.convertMessage(mapOf(
            "version" to "1.0",
            "rendererFunctionResponse" to mapOf(
                "surfaceId" to "s1",
                "requestId" to "whatever",
                "result" to 1,
            )
        ))

        assertTrue(sink.envelopes.isEmpty())
    }

    private val rpcCatalog = "https://ka2ui.dev/catalogs/rpc-v1.json"
    private val basicCatalog = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"

    private fun withRpcCatalog(test: (A2UIProtocolAdapter, MutableList<Map<String, Any?>>) -> Unit) {
        val previous = A2UICustomFunctionRegistry.lookupExact("getGreeting", rpcCatalog)
        try {
            assertTrue(A2UICustomFunctionRegistry.register("getGreeting", """{"catalogId":"$rpcCatalog","type":"object","description":"Returns a greeting string for the supplied name without side effects.","returnType":"string","allowedCallers":"rendererOrAgent","properties":{"@call":{"const":"getGreeting"},"args":{"type":"object","properties":{"name":{"${'$'}ref":"common_types.json#/${'$'}defs/DynamicString"}},"required":["name"],"unevaluatedProperties":false}},"required":["@call","args"]}"""))
            val adapter = A2UIProtocolAdapter()
            adapter.strictV10 = true
            adapter.setExpectedProtocolVersion(A2UIProtocolVersion.V1_0)
            adapter.convertMessage(mapOf("version" to "1.0", "createSurface" to mapOf(
                "surfaceId" to "s1", "catalogId" to basicCatalog,
            )))
            val emitted = mutableListOf<Map<String, Any?>>()
            adapter.emitWireMessage = { emitted += com.tencent.kuikly.a2ui.parser.SimpleJsonParser.parse(it)!! }
            test(adapter, emitted)
        } finally {
            A2UICustomFunctionRegistry.unregister("getGreeting", rpcCatalog)
            previous?.let(A2UICustomFunctionRegistry::register)
        }
    }

    private fun officialCall(id: String, catalog: String = rpcCatalog, name: String = "getGreeting") =
        mapOf("version" to "1.0", "callRendererFunction" to mapOf(
            "surfaceId" to "s1", "functionCallId" to id,
            "callFunction" to mapOf("@call" to name, "catalogId" to catalog, "args" to mapOf("name" to "Ada")),
        ))

    private fun officialReply(id: String, value: Any?) = mapOf("version" to "1.0",
        "agentFunctionResponse" to mapOf("functionCallId" to id, "value" to value))

    @Test
    fun officialManifestRegistrationEnforcesResolvedStringAndUniqueArgument() = withRpcCatalog { adapter, emitted ->
        val descriptor = A2UICustomFunctionRegistry.lookupExact("getGreeting", rpcCatalog)!!
        assertEquals(mapOf("name" to "string"), descriptor.argumentTypes)
        assertEquals(setOf("name"), descriptor.requiredArguments)
        assertFalse(descriptor.allowAdditionalArguments)
        assertFalse(descriptor.requiresUserActivation)
        assertNull(descriptor.validateArguments(mapOf("name" to "Ada")))
        val invalid = listOf(emptyMap<String, Any?>(), mapOf("name" to 42),
            mapOf("name" to mapOf("@path" to "/name")), mapOf("name" to "Ada", "extra" to true))
        var executions = 0
        adapter.onIncomingAgentCall = { executions++ }
        invalid.forEachIndexed { index, args ->
            assertNotNull(descriptor.validateArguments(args))
            adapter.convertMessage(mapOf("version" to "1.0", "callRendererFunction" to mapOf(
                "surfaceId" to "s1", "functionCallId" to "invalid-$index",
                "callFunction" to mapOf("@call" to "getGreeting", "catalogId" to rpcCatalog, "args" to args),
            )))
        }
        assertEquals(0, executions)
        assertEquals(invalid.size, emitted.size)
        emitted.forEach {
            val payload = it["rendererFunctionResponse"] as Map<*, *>
            assertEquals(A2UIRpcError.Codes.INVALID_ARGS, (payload["error"] as Map<*, *>)["code"])
        }
    }

    @Test
    fun strictRpcCatalogOverrideDoesNotChangeBasicSurfaceCatalog() = withRpcCatalog { adapter, emitted ->
        val completions = mutableListOf<com.tencent.kuikly.a2ui.rpc.A2UIRpcResponse>()
        val id = adapter.invokeAgentFunction("s1", "getGreeting", mapOf("name" to "Ada"), null,
            onComplete = { completions += it }, catalogId = rpcCatalog)
        val envelope = emitted.single()
        assertEquals("1.0", envelope["version"])
        val payload = envelope["callAgentFunction"] as Map<*, *>
        assertEquals(setOf("surfaceId", "functionCallId", "callFunction"), payload.keys)
        assertEquals(id, payload["functionCallId"])
        assertEquals(rpcCatalog, (payload["callFunction"] as Map<*, *>)["catalogId"])
        adapter.convertMessage(officialReply(id, "Hello, Ada!"))
        adapter.convertMessage(officialReply(id, "duplicate"))
        assertEquals("Hello, Ada!", completions.single().result)
        var rejected: com.tencent.kuikly.a2ui.rpc.A2UIRpcResponse? = null
        adapter.invokeAgentFunction("s1", "getGreeting", mapOf("name" to "Ada"), null) { rejected = it }
        assertEquals("UNKNOWN_CATALOG", rejected?.error?.code)
        assertEquals(1, emitted.size)
    }

    @Test
    fun strictIncomingCallbackKeepsCatalogAndCompletesRealNullOnce() = withRpcCatalog { adapter, emitted ->
        val received = mutableListOf<A2UIRpcRequest>()
        adapter.onIncomingAgentCall = { received += it }
        adapter.convertMessage(officialCall("real-null"))
        adapter.convertMessage(officialCall("real-null"))
        assertEquals(rpcCatalog, received.single().catalogId)
        adapter.respondToAgent("s1", "real-null", null)
        adapter.respondToAgent("s1", "real-null", "duplicate")
        adapter.convertMessage(officialCall("real-null"))
        assertEquals(1, received.size)
        val payload = emitted.single()["rendererFunctionResponse"] as Map<*, *>
        assertEquals(setOf("functionCallId", "value"), payload.keys)
        assertEquals("real-null", payload["functionCallId"])
        assertTrue(payload.containsKey("value"))
        assertNull(payload["value"])
    }

    @Test
    fun strictUnknownFunctionAndCatalogNeverRunHost() = withRpcCatalog { adapter, emitted ->
        var executions = 0
        adapter.onIncomingAgentCall = { executions++ }
        adapter.convertMessage(officialCall("catalog", catalog = "https://unknown.invalid/catalog.json"))
        adapter.convertMessage(officialCall("function", name = "unknown"))
        assertEquals(0, executions)
        val codes = emitted.map { ((it["rendererFunctionResponse"] as Map<*, *>)["error"] as Map<*, *>)["code"] }
        assertEquals(listOf("UNKNOWN_CATALOG", A2UIRpcError.Codes.UNKNOWN_FUNCTION), codes)
        assertEquals(0, adapter.rpcDispatcher.pendingCountFor())
    }

    @Test
    fun deleteSurfaceCancelsBothDirectionsAndRejectsLateReplies() = withRpcCatalog { adapter, emitted ->
        val completions = mutableListOf<com.tencent.kuikly.a2ui.rpc.A2UIRpcResponse>()
        adapter.onIncomingAgentCall = {}
        adapter.convertMessage(officialCall("agent-deleted"))
        val id = adapter.invokeAgentFunction("s1", "getGreeting", mapOf("name" to "Ada"), null,
            onComplete = { completions += it }, catalogId = rpcCatalog)
        adapter.convertMessage(mapOf("version" to "1.0", "deleteSurface" to mapOf("surfaceId" to "other")))
        assertEquals(2, adapter.rpcDispatcher.pendingCountFor())
        val messages = adapter.convertMessage(mapOf("version" to "1.0", "deleteSurface" to mapOf("surfaceId" to "s1")))
        assertTrue(messages.any { it.type == com.tencent.kuikly.a2ui.protocol.A2UIMessageType.DESTROY_SURFACE })
        assertEquals("", adapter.surfaceId)
        assertEquals(A2UIRpcError.Codes.SURFACE_CLOSED, completions.single().error?.code)
        assertEquals(0, adapter.rpcDispatcher.pendingCountFor())
        val count = emitted.size
        adapter.convertMessage(officialReply(id, "late"))
        adapter.respondToAgent("s1", "agent-deleted", "late")
        assertEquals(count, emitted.size)
        assertEquals(1, completions.size)
        val response = emitted.last()["rendererFunctionResponse"] as Map<*, *>
        assertEquals("agent-deleted", response["functionCallId"])
        assertEquals(A2UIRpcError.Codes.SURFACE_CLOSED, (response["error"] as Map<*, *>)["code"])
        adapter.onIncomingAgentCall = { error("deleted surface must not execute late requests") }
        adapter.convertMessage(officialCall("new-late-id"))
        assertEquals(0, adapter.rpcDispatcher.pendingCountFor())
        val rejected = emitted.last()["rendererFunctionResponse"] as Map<*, *>
        assertEquals(A2UIRpcError.Codes.SURFACE_CLOSED, (rejected["error"] as Map<*, *>)["code"])
    }

    @Test
    fun resetCancelsPendingOnceWithOfficialWireResponse() = withRpcCatalog { adapter, emitted ->
        adapter.onIncomingAgentCall = {}
        adapter.convertMessage(officialCall("reset"))
        val completions = mutableListOf<com.tencent.kuikly.a2ui.rpc.A2UIRpcResponse>()
        val id = adapter.invokeAgentFunction("s1", "getGreeting", mapOf("name" to "Ada"), null,
            onComplete = { completions += it }, catalogId = rpcCatalog)
        adapter.reset()
        adapter.reset()
        adapter.convertMessage(officialReply(id, null))
        adapter.respondToAgent("s1", "reset", null)
        assertEquals(1, completions.size)
        assertEquals(A2UIRpcError.Codes.SURFACE_CLOSED, completions.single().error?.code)
        assertEquals(2, emitted.size)
        assertEquals(setOf("functionCallId", "error"), (emitted.last()["rendererFunctionResponse"] as Map<*, *>).keys)
    }

    @Test
    fun strictTimeoutEmitsOfficialFailureAndDropsLateResponse() = withRpcCatalog { adapter, emitted ->
        adapter.onIncomingAgentCall = {}
        adapter.convertMessage(officialCall("timeout"))
        val completions = mutableListOf<com.tencent.kuikly.a2ui.rpc.A2UIRpcResponse>()
        val id = adapter.invokeAgentFunction("s1", "getGreeting", mapOf("name" to "Ada"), 1L,
            onComplete = { completions += it }, catalogId = rpcCatalog)
        adapter.rpcDispatcher.tick(com.tencent.kuikly.core.datetime.DateTime.currentTimestamp() + 30_000L)
        adapter.convertMessage(officialReply(id, "late"))
        adapter.respondToAgent("s1", "timeout", "late")
        assertEquals(A2UIRpcError.Codes.TIMEOUT, completions.single().error?.code)
        val response = emitted.last()["rendererFunctionResponse"] as Map<*, *>
        assertEquals("timeout", response["functionCallId"])
        assertEquals(A2UIRpcError.Codes.TIMEOUT, (response["error"] as Map<*, *>)["code"])
    }
}
