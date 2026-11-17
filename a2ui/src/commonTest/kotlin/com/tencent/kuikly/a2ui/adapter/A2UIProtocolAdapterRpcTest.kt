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

    private fun newAdapter(strict: Boolean = true): A2UIProtocolAdapter {
        val adapter = A2UIProtocolAdapter()
        adapter.setExpectedProtocolVersion(A2UIProtocolVersion.V1_0)
        adapter.strictV10 = strict
        // Seed a surface so wire-level surfaceId validation passes.
        // A minimal createSurface envelope is enough.
        adapter.convertMessage(
            mapOf(
                "version" to "1.0",
                "createSurface" to mapOf(
                    "surfaceId" to "s1",
                    "root" to mapOf("componentType" to "Div"),
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
}
