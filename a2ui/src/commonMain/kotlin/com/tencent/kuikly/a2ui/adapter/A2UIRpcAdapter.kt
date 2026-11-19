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
import com.tencent.kuikly.a2ui.rpc.A2UIRpcDispatcher
import com.tencent.kuikly.a2ui.rpc.A2UIRpcError
import com.tencent.kuikly.a2ui.rpc.A2UIRpcRequest
import com.tencent.kuikly.a2ui.rpc.A2UIRpcResponse

/** Wire adaptation only; the parent adapter retains dispatcher and stream lifecycle ownership. */
internal class A2UIRpcAdapter(
    private val rpcDispatcher: A2UIRpcDispatcher,
    private val getSurfaceId: () -> String,
    private val getProtocolVersion: () -> A2UIProtocolVersion,
    private val getStrictV10: () -> Boolean,
    private val getIncomingAgentCall: () -> ((A2UIRpcRequest) -> Unit)?,
    private val getEmitWireMessage: () -> ((String) -> Unit)?,
    private val serializeToJson: (Any?) -> String?,
) {
    private val currentSurfaceId: String get() = getSurfaceId()
    private val _protocolVersion: A2UIProtocolVersion get() = getProtocolVersion()
    private val strictV10: Boolean get() = getStrictV10()
    private val onIncomingAgentCall: ((A2UIRpcRequest) -> Unit)? get() = getIncomingAgentCall()
    private val emitWireMessage: ((String) -> Unit)? get() = getEmitWireMessage()

    /** Handle an incoming `callRendererFunction` envelope (agent → renderer). */
    fun handleCallRendererFunction(payload: Map<String, Any?>?) {
        val parsed = parseRpcRequest(
            payload = payload,
            direction = A2UIRpcRequest.Direction.AGENT_TO_RENDERER,
            envelopeKey = "callRendererFunction",
        ) ?: return

        // Record the pending entry (for timeout bookkeeping). The host
        // may call `respondToAgent` / `failAgentRequest` later — those
        // paths will look up this requestId again.
        rpcDispatcher.onIncomingAgentCall(parsed)

        val handler = onIncomingAgentCall
        if (handler == null) {
            // No host handler attached → immediately fail back so the
            // agent isn't left waiting. Log once, loudly.
            println(
                "[A2UI] warn: callRendererFunction '${parsed.name}' received " +
                    "but no host handler is installed. Replying with " +
                    "UNKNOWN_FUNCTION. Call " +
                    "A2UIBridgeManager.setRendererFunctionHandler to opt in."
            )
            val resp = rpcDispatcher.failAgentCall(
                surfaceId = parsed.surfaceId,
                requestId = parsed.requestId,
                error = A2UIRpcError.unknownFunction(parsed.name),
            )
            if (resp != null) emitRendererFunctionResponse(resp)
            return
        }
        // Invoke the host asynchronously from the adapter's POV — the
        // host will call `respondToAgent` / `failAgentRequest` when
        // ready. We swallow handler exceptions here so a buggy host
        // can't take the whole stream down; the pending entry still
        // exists and will eventually time out if the host never
        // responds.
        try {
            handler(parsed)
        } catch (t: Throwable) {
            println(
                "[A2UI] error: rendererFunctionHandler for '${parsed.name}' " +
                    "threw: ${t.message}. Failing RPC with INTERNAL_ERROR."
            )
            val resp = rpcDispatcher.failAgentCall(
                surfaceId = parsed.surfaceId,
                requestId = parsed.requestId,
                error = A2UIRpcError.internalError(t.message ?: "handler threw"),
            )
            if (resp != null) emitRendererFunctionResponse(resp)
        }
    }

    /** Handle an incoming `agentFunctionResponse` envelope (reply to renderer-originated call). */
    fun handleAgentFunctionResponse(payload: Map<String, Any?>?) {
        val resp = parseRpcResponse(
            payload = payload,
            direction = A2UIRpcRequest.Direction.RENDERER_TO_AGENT,
            envelopeKey = "agentFunctionResponse",
        ) ?: return

        val entry = rpcDispatcher.onIncomingAgentResponse(resp) ?: return
        try {
            entry.onComplete?.invoke(resp)
        } catch (t: Throwable) {
            println(
                "[A2UI] error: onComplete for requestId='${resp.requestId}' " +
                    "threw: ${t.message}"
            )
        }
    }

    /** Ignore outbound-only envelope keys received on the inbound path. */
    fun handleRendererFunctionResponse(payload: Map<String, Any?>?) {
        if (payload == null) return
        println(
            "[A2UI] warn: envelope key 'rendererFunctionResponse' is an " +
                "outbound-only message from the renderer side; ignoring " +
                "it on the inbound path."
        )
    }

    fun handleCallAgentFunctionIncoming(payload: Map<String, Any?>?) {
        if (payload == null) return
        println(
            "[A2UI] warn: envelope key 'callAgentFunction' is an " +
                "outbound-only message from the renderer side; ignoring " +
                "it on the inbound path."
        )
    }

    /** Emit a host result for an earlier request; no-op after timeout. */
    fun respondToAgent(surfaceId: String, requestId: String, result: Any?) {
        val resp = rpcDispatcher.completeAgentCall(surfaceId, requestId, result)
            ?: return
        emitRendererFunctionResponse(resp)
    }

    /** Failure variant of [respondToAgent]. */
    fun failAgentRequest(
        surfaceId: String,
        requestId: String,
        error: A2UIRpcError,
    ) {
        val resp = rpcDispatcher.failAgentCall(surfaceId, requestId, error) ?: return
        emitRendererFunctionResponse(resp)
    }

    /** Initiate a renderer-originated call and return its opaque requestId. */
    fun invokeAgentFunction(
        surfaceId: String,
        name: String,
        args: Map<String, Any?>,
        timeoutMs: Long?,
        onComplete: (A2UIRpcResponse) -> Unit,
    ): String {
        val req = rpcDispatcher.beginRendererCall(
            surfaceId = surfaceId,
            name = name,
            args = args,
            timeoutMs = timeoutMs,
            onComplete = onComplete,
        )
        emitCallAgentFunction(req)
        return req.requestId
    }

    /** Validate a request; malformed envelopes are diagnosed and dropped. */
    private fun parseRpcRequest(
        payload: Map<String, Any?>?,
        direction: A2UIRpcRequest.Direction,
        envelopeKey: String,
    ): A2UIRpcRequest? {
        if (payload == null) {
            rpcWireError(envelopeKey, "payload is not a Map")
            return null
        }
        val surfaceId = payload["surfaceId"] as? String
        if (surfaceId.isNullOrEmpty()) {
            rpcWireError(envelopeKey, "missing or non-string 'surfaceId'")
            return null
        }
        // Cross-check against adapter's current surface. Mismatch is a
        // strict-mode reject because the dispatcher's pending table is
        // keyed by requestId only; accepting mismatched surfaceIds would
        // make cancel-by-surface unreliable.
        if (currentSurfaceId.isNotEmpty() && surfaceId != currentSurfaceId) {
            rpcWireError(
                envelopeKey,
                "surfaceId '$surfaceId' does not match current '$currentSurfaceId'",
            )
            return null
        }
        val requestId = payload["requestId"] as? String
        if (requestId.isNullOrEmpty()) {
            rpcWireError(envelopeKey, "missing or non-string 'requestId'")
            return null
        }
        val name = payload["name"] as? String
        if (name.isNullOrEmpty()) {
            rpcWireError(envelopeKey, "missing or non-string 'name'")
            return null
        }
        val argsRaw = payload["args"]
        val args: Map<String, Any?> = when (argsRaw) {
            null -> emptyMap()
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                argsRaw as Map<String, Any?>
            }
            else -> {
                rpcWireError(envelopeKey, "'args' must be a Map when present")
                return null
            }
        }
        val timeoutMs = when (val t = payload["timeoutMs"]) {
            null -> null
            is Number -> {
                val v = t.toLong()
                if (v < 0L) {
                    rpcWireError(envelopeKey, "'timeoutMs' must be non-negative")
                    return null
                }
                v
            }
            else -> {
                rpcWireError(envelopeKey, "'timeoutMs' must be a Number")
                return null
            }
        }
        return A2UIRpcRequest(
            direction = direction,
            surfaceId = surfaceId,
            requestId = requestId,
            name = name,
            args = args,
            timeoutMs = timeoutMs,
        )
    }

    /** Malformed result/error combinations complete the call with WIRE_FORMAT_ERROR. */
    private fun parseRpcResponse(
        payload: Map<String, Any?>?,
        direction: A2UIRpcRequest.Direction,
        envelopeKey: String,
    ): A2UIRpcResponse? {
        if (payload == null) {
            rpcWireError(envelopeKey, "payload is not a Map")
            return null
        }
        val surfaceId = payload["surfaceId"] as? String
        if (surfaceId.isNullOrEmpty()) {
            rpcWireError(envelopeKey, "missing or non-string 'surfaceId'")
            return null
        }
        val requestId = payload["requestId"] as? String
        if (requestId.isNullOrEmpty()) {
            rpcWireError(envelopeKey, "missing or non-string 'requestId'")
            return null
        }
        val hasResult = payload.containsKey("result")
        val errorRaw = payload["error"]
        if (hasResult == (errorRaw != null)) {
            // Both present or both absent → wire-format violation.
            rpcWireError(
                envelopeKey,
                "exactly one of 'result' / 'error' must be present",
            )
            return A2UIRpcResponse.failure(
                direction = direction,
                surfaceId = surfaceId,
                requestId = requestId,
                error = A2UIRpcError.wireFormat(
                    "$envelopeKey envelope had both or neither of result/error",
                ),
            )
        }
        return if (errorRaw != null) {
            val errMap = errorRaw as? Map<*, *>
            if (errMap == null) {
                rpcWireError(envelopeKey, "'error' must be a Map")
                return A2UIRpcResponse.failure(
                    direction = direction,
                    surfaceId = surfaceId,
                    requestId = requestId,
                    error = A2UIRpcError.wireFormat("error field was not a Map"),
                )
            }
            val code = (errMap["code"] as? String)?.takeIf { it.isNotEmpty() }
                ?: A2UIRpcError.Codes.INTERNAL_ERROR
            val message = errMap["message"] as? String
            @Suppress("UNCHECKED_CAST")
            val details = errMap["details"] as? Map<String, Any?>
            A2UIRpcResponse.failure(
                direction = direction,
                surfaceId = surfaceId,
                requestId = requestId,
                error = A2UIRpcError(code, message, details),
            )
        } else {
            A2UIRpcResponse.success(
                direction = direction,
                surfaceId = surfaceId,
                requestId = requestId,
                result = payload["result"],
            )
        }
    }

    private fun rpcWireError(envelopeKey: String, detail: String) {
        val prefix = if (strictV10 && _protocolVersion == A2UIProtocolVersion.V1_0) {
            "[A2UI] error"
        } else {
            "[A2UI] warn"
        }
        println("$prefix: v1.0 RPC '$envelopeKey' envelope invalid — $detail")
    }

    private fun emitRendererFunctionResponse(resp: A2UIRpcResponse) {
        val json = serializeToJson(mapOf(
            "version" to A2UIProtocolVersion.V1_0.wireValue,
            "rendererFunctionResponse" to buildResponsePayload(resp),
        )) ?: return
        (emitWireMessage ?: return).invoke(json)
    }

    private fun emitCallAgentFunction(req: A2UIRpcRequest) {
        val payload = mutableMapOf<String, Any?>(
            "surfaceId" to req.surfaceId,
            "requestId" to req.requestId,
            "name" to req.name,
            "args" to req.args,
        )
        req.timeoutMs?.let { payload["timeoutMs"] = it }
        val json = serializeToJson(mapOf(
            "version" to A2UIProtocolVersion.V1_0.wireValue,
            "callAgentFunction" to payload,
        )) ?: return
        (emitWireMessage ?: return).invoke(json)
    }

    private fun buildResponsePayload(resp: A2UIRpcResponse): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "surfaceId" to resp.surfaceId,
            "requestId" to resp.requestId,
        )
        if (resp.isSuccess) {
            base["result"] = resp.result
        } else {
            val err = resp.error!!
            val errMap = mutableMapOf<String, Any?>(
                "code" to err.code,
            )
            err.message?.let { errMap["message"] = it }
            err.details?.let { errMap["details"] = it }
            base["error"] = errMap
        }
        return base
    }
}
