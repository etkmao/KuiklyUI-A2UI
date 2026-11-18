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

// No package declaration — root package on purpose so that the C++ layer can
// reach these symbols through `api->kotlin.root.*` (mirrors how KSP-generated
// `initKuikly` is authored).

import com.tencent.kuikly.a2ui.adapter.A2UIIconRegistry
import com.tencent.kuikly.a2ui.bridge.A2UIBridgeManager
import com.tencent.kuikly.a2ui.bridge.A2UIKotlinMethod
import com.tencent.kuikly.a2ui.dsl.A2UICustomViewRegistry
import com.tencent.kuikly.a2ui.function.A2UICustomFunctionRegistry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import ohos.com_tencent_kuikly_ScheduleContextTask

/**
 * A2UI Kuikly-page bridge: unidirectional writers that ArkTS calls through
 * NAPI to keep the three A2UI global registries populated when the host
 * runs in Kuikly-page mode.
 *
 * ## Why this file exists
 *
 * In Kuikly-page mode the business Pager (`A2UICaseListPager` etc.) extends
 * the plain `Pager` class, so it does not override `onReceivePagerEvent`
 * to route A2UI registration events (`a2ui_registerCustomFunction`, …)
 * into the KMP registries. Registrations issued from the ArkTS side
 * therefore never reach `A2UICustomFunctionRegistry.register(...)`, and
 * `A2UIProtocolAdapter.evaluateCustomFunctionAsString` short-circuits
 * with `descriptor == null`.
 *
 * The three writers below let ArkTS `registerA2UICustomFunction / View /
 * Icon` push straight into the same singletons `A2UIPager.onReceivePagerEvent`
 * would use on the native-surface path, so both hosting modes end up with
 * identical KMP state.
 *
 * ## Header-emission notes
 *
 * These are declared as plain `public` top-level functions (no `@CName`).
 * Kotlin/Native's `libshared_api.h` generator treats every reachable
 * public top-level function as an exported symbol, mirroring how the
 * KSP-generated `initKuikly()` reaches the header without a `@CName`
 * annotation. The C++ NAPI layer accesses them via
 * `api->kotlin.root.a2uiRegisterCustomFunction(...)` etc.
 *
 * We deliberately avoid `@CName` because Kotlin/Native's release-mode
 * DCE strips `@CName` top-level functions unless something else in the
 * compiled module marks them reachable — a caveat proven repeatedly on
 * the `unregister*` variants when we first tried the `@CName` route.
 *
 * ## Why they live in `:demo`
 *
 * The header generator only surfaces top-level public functions authored
 * inside the module currently being linked. Placing these writers in the
 * upstream `:a2ui` module would keep them out of `:demo`'s
 * generated header even though the underlying `libshared.so` bytecode
 * would still contain them.
 */

public fun a2uiRegisterCustomFunction(name: String, spec: String) {
    A2UICustomFunctionRegistry.register(name, spec)
}

public fun a2uiUnregisterCustomFunction(name: String) {
    A2UICustomFunctionRegistry.unregister(name)
}

public fun a2uiRegisterCustomView(viewName: String) {
    A2UICustomViewRegistry.register(viewName)
}

public fun a2uiUnregisterCustomView(viewName: String) {
    A2UICustomViewRegistry.unregister(viewName)
}

public fun a2uiRegisterIcon(name: String, uri: String) {
    A2UIIconRegistry.registerIcon(name, uri)
}

public fun a2uiUnregisterIcon(name: String) {
    A2UIIconRegistry.unregisterIcon(name)
}

/**
 * Deliver a DateTimeInput picker result from ArkTS back into the KMP layer.
 *
 * ## Why this is a top-level bridge
 *
 * In Kuikly-page mode there is no `A2UISurfaceViewController` on the ArkTS
 * side, so the standard `sendEvent("a2ui_pickerResult", …)` path is
 * unavailable — that event is only dispatched by
 * `A2UISurfaceViewController.notifyPickerResult(...)`. Moreover the outer
 * business Pager (e.g. `A2UICaseListPager`) extends the plain `Pager`
 * class and does not override `onReceivePagerEvent` to route
 * `a2ui_pickerResult` to `A2UIBridgeManager.PICKER_RESULT`, so even a
 * manually issued event would be dropped.
 *
 * Instead we let ArkTS's `A2UIBridgeModule.call('showDateTimePicker')`
 * pop its own ArkUI DatePicker/TimePicker dialog and then reach back into
 * the KMP layer directly through this NAPI-exported writer, targeting the
 * dsl-side `instanceId` that was previously baked into the `configJson`
 * request (see `A2UIDSLBridge.showDateTimePicker`). The routing then
 * lands in `A2UIBridgeManager.callKotlinMethod(dslInstanceId,
 * PICKER_RESULT, …)` and finally in the correct
 * `A2UIDSLBridge.handlePickerResult(tag, iso)`.
 *
 * ## Thread-safety: why we hop onto the Kuikly Context Thread
 *
 * NAPI callbacks run on the ArkTS main thread. Directly invoking
 * `callKotlinMethod(PICKER_RESULT, …)` from that thread lands us straight
 * inside `A2UIDSLBridge.handlePickerResult`, which mutates the DSL Flex
 * tree (`applier.updateDateTimeInputValue` / `applier.performLayout` /
 * further protocol-adapter cascades). Those Flex/DSL operations are only
 * safe to perform on the Kuikly Context Thread — running them on ArkTS
 * main races with the host page's own layout pass and visibly corrupts
 * the outer Kuikly page layout (see Android
 * `A2UIAndroidNativeMethodHandler.sendPickerResult` which does the same
 * hop via `KuiklyRenderCoreContextScheduler`).
 *
 * We therefore stash the request under the *outer host* pager id and ask
 * Kuikly Core (`com_tencent_kuikly_ScheduleContextTask`) to invoke us
 * back on the context thread of that pager. The C callback (which cannot
 * capture a closure — see `staticCFunction` restrictions) then drains any
 * pending picker results for the pager and finally dispatches them into
 * `A2UIBridgeManager.callKotlinMethod` — this time on the correct thread.
 *
 * If the host pager id can't be resolved (legacy native-page mode, or
 * malformed configJson), we fall back to a synchronous dispatch: the
 * legacy path already runs on the correct thread anyway.
 *
 * @param dslInstanceId The internal A2UI DSL child pager id (baked into the
 *                      original picker config so ArkTS can echo it back).
 * @param tag           A2UI protocol tag of the DateTimeInput component.
 * @param isoString     Selected date/time in ISO 8601 format.
 */
@OptIn(ExperimentalForeignApi::class)
public fun a2uiNotifyPickerResult(dslInstanceId: String, tag: Int, isoString: String) {
    val hostPagerId = A2UIBridgeManager.getHostPagerIdForDsl(dslInstanceId)
    if (hostPagerId == null) {
        // No enclosing Kuikly host — assume caller is already on the correct
        // thread (legacy native-page mode).
        A2UIBridgeManager.callKotlinMethod(
            A2UIKotlinMethod.PICKER_RESULT,
            dslInstanceId,
            "$tag|$isoString",
            null
        )
        return
    }
    A2UIPickerResultRelay.enqueue(hostPagerId, dslInstanceId, tag, isoString)
    com_tencent_kuikly_ScheduleContextTask(hostPagerId, staticCFunction { pagerIdBytes: CPointer<ByteVar>? ->
        val id = pagerIdBytes?.toKString() ?: return@staticCFunction
        A2UIPickerResultRelay.drainAndDispatch(id)
    })
}

/**
 * Process-wide queue of picker results waiting to be dispatched on the
 * Kuikly Context Thread of a specific pager id.
 *
 * Producer: `a2uiNotifyPickerResult` (called from the ArkTS main thread
 *           via NAPI).
 * Consumer: `drainAndDispatch` (called from the Kuikly Context Thread of
 *           the target pager via `com_tencent_kuikly_ScheduleContextTask`
 *           → `staticCFunction` trampoline).
 *
 * Kotlin/Native's `staticCFunction` cannot capture a closure, so we can't
 * simply pass a lambda holding `dslInstanceId` / `tag` / `isoString` to
 * `com_tencent_kuikly_ScheduleContextTask`. This relay keeps the arguments
 * alive between the schedule call and the callback.
 *
 * All operations are guarded so producer/consumer running on different
 * threads (ArkTS main vs Kuikly Context) never see torn state; simple
 * synchronization is enough because the payload lifetime is measured in
 * milliseconds and picker events are one-shot per user tap.
 */
private object A2UIPickerResultRelay : SynchronizedObject() {

    private data class Entry(
        val dslInstanceId: String,
        val tag: Int,
        val isoString: String
    )

    /** hostPagerId → pending picker results waiting to hit the Kuikly thread. */
    private val queues = mutableMapOf<String, MutableList<Entry>>()

    fun enqueue(hostPagerId: String, dslInstanceId: String, tag: Int, isoString: String) {
        synchronized(this) {
            val bucket = queues.getOrPut(hostPagerId) { mutableListOf() }
            bucket.add(Entry(dslInstanceId, tag, isoString))
        }
    }

    fun drainAndDispatch(hostPagerId: String) {
        val snapshot: List<Entry> = synchronized(this) {
            val bucket = queues[hostPagerId] ?: return
            if (bucket.isEmpty()) return
            val copy = bucket.toList()
            bucket.clear()
            copy
        }
        for (entry in snapshot) {
            A2UIBridgeManager.callKotlinMethod(
                A2UIKotlinMethod.PICKER_RESULT,
                entry.dslInstanceId,
                "${entry.tag}|${entry.isoString}",
                null
            )
        }
    }
}

/**
 * Push an async "custom view size changed" notification from ArkTS into
 * the KMP layer. Companion to [notifyA2UICustomViewSizeChanged] on the
 * ArkTS side.
 *
 * ## Why this is a top-level bridge
 *
 * Same reason as [a2uiNotifyPickerResult]: the Kuikly-page business pager
 * extends plain `Pager`, so the usual `sendEvent` route cannot carry a
 * message into A2UI's dispatcher. Instead ArkTS calls this NAPI writer
 * directly and we land in [A2UIBridgeManager.notifyCustomViewResize]
 * which looks up the [A2UICustomComponentView] registered under
 * `(pagerId, nativeRef)` and updates its Flex node.
 *
 * ## Thread-safety: Kuikly Context Thread hop
 *
 * The actual size update eventually writes `flexNode.styleWidth` /
 * `styleHeight`, which internally marks the node dirty and expects to
 * run on the owning pager's Kuikly Context Thread. NAPI callbacks arrive
 * on the ArkTS main thread, so we schedule onto the Kuikly thread via
 * `com_tencent_kuikly_ScheduleContextTask` before touching the Flex
 * tree — identical to the picker-result path.
 *
 * @param pagerId   Pager id that owns the target custom view (either the
 *                  A2UI DSL instance id in native-page mode or the host
 *                  Kuikly pager id in Kuikly-page-embedded mode).
 * @param nativeRef Kuikly Core render-view tag of the custom view.
 * @param width     New intrinsic width in vp, or `0` to keep width.
 * @param height    New intrinsic height in vp, or `0` to keep height.
 */
@OptIn(ExperimentalForeignApi::class)
public fun a2uiNotifyCustomViewResize(
    pagerId: String,
    nativeRef: Int,
    width: Float,
    height: Float
) {
    A2UICustomViewResizeRelay.enqueue(pagerId, nativeRef, width, height)
    com_tencent_kuikly_ScheduleContextTask(pagerId, staticCFunction { pagerIdBytes: CPointer<ByteVar>? ->
        val id = pagerIdBytes?.toKString() ?: return@staticCFunction
        A2UICustomViewResizeRelay.drainAndDispatch(id)
    })
}

/**
 * Companion relay for [a2uiNotifyCustomViewResize]. Same contract as
 * [A2UIPickerResultRelay]: the ArkTS main thread enqueues, the Kuikly
 * Context Thread drains. `staticCFunction` cannot capture a closure, so
 * the payload lives here between the schedule call and the callback.
 */
private object A2UICustomViewResizeRelay : SynchronizedObject() {

    private data class Entry(
        val nativeRef: Int,
        val width: Float,
        val height: Float
    )

    /** pagerId → pending resize events waiting to hit the Kuikly thread. */
    private val queues = mutableMapOf<String, MutableList<Entry>>()

    fun enqueue(pagerId: String, nativeRef: Int, width: Float, height: Float) {
        synchronized(this) {
            val bucket = queues.getOrPut(pagerId) { mutableListOf() }
            bucket.add(Entry(nativeRef, width, height))
        }
    }

    fun drainAndDispatch(pagerId: String) {
        val snapshot: List<Entry> = synchronized(this) {
            val bucket = queues[pagerId] ?: return
            if (bucket.isEmpty()) return
            val copy = bucket.toList()
            bucket.clear()
            copy
        }
        for (entry in snapshot) {
            A2UIBridgeManager.notifyCustomViewResize(
                pagerId,
                entry.nativeRef,
                entry.width,
                entry.height
            )
        }
    }
}

// =====================================================================
//  A2UI v1.0 Bidirectional-RPC NAPI writers (PR-6b follow-up)
//
//  These three top-level functions let the ArkTS A2UIBridgeModule /
//  A2UINativeManager reach into the Kotlin core's RPC entry points
//  (`A2UIBridgeManager.respondToAgentJson / failAgentRequestJson /
//  invokeAgentFunctionJson`) from the ohosArm64 app, mirroring the
//  Android `A2UIRpcBridge` Java facade and the iOS `A2UIRpcBridge`
//  ObjC facade.
//
//  Threading contract
//  ------------------
//  All three Kotlin entry points are safe to call from any thread.
//  They only touch `A2UIBridgeManager.dslBridgeMap` lookups + the
//  `A2UIRpcDispatcher`'s internal state, both of which are already
//  guarded by the dispatcher's own synchronisation (see KDoc on
//  `A2UIRpcDispatcher.pendingAgentCalls` / `handlerMap`).
//
//  The outbound-RPC completion callback for `a2uiInvokeAgentFunction`
//  is delivered on whatever thread the response arrives on (typically
//  the Kotlin core's stream-parse thread). The C++ NAPI shim is
//  responsible for hopping the JSON back to the ArkTS main thread via
//  `napi_call_threadsafe_function`.
// =====================================================================

/**
 * Respond to an inbound `callRendererFunction` with a success result.
 *
 * Mirrors `A2UIBridgeManager.respondToAgentJson` — the ArkTS host calls
 * this once it has finished processing a `callRendererFunction` RPC
 * that arrived through the renderer-function-json-handler (registered
 * via `A2UIBridgeManager.setRendererFunctionJsonHandler`).
 *
 * @param instanceId A2UI DSL surface instance id (same string that was
 *                   used as the `A2UISurfaceDSLView.instanceId` key).
 * @param surfaceId  surfaceId echoed from the inbound RPC request.
 * @param requestId  requestId echoed from the inbound RPC request.
 * @param resultJson Arbitrary JSON fragment to return to the agent.
 *                   Empty string is equivalent to `null` (void result).
 */
public fun a2uiRespondToRpc(
    instanceId: String,
    surfaceId: String,
    requestId: String,
    resultJson: String
) {
    A2UIBridgeManager.respondToAgentJson(
        instanceId = instanceId,
        surfaceId = surfaceId,
        requestId = requestId,
        resultJson = resultJson.takeIf { it.isNotEmpty() }
    )
}

/**
 * Respond to an inbound `callRendererFunction` with a failure.
 *
 * Mirrors `A2UIBridgeManager.failAgentRequestJson`. See
 * `A2UIRpcError.Codes` for the canonical error-code vocabulary; hosts
 * may use custom codes with the `x-` prefix.
 *
 * @param instanceId  A2UI DSL surface instance id.
 * @param surfaceId   surfaceId echoed from the inbound RPC request.
 * @param requestId   requestId echoed from the inbound RPC request.
 * @param code        Error code string (see `A2UIRpcError.Codes`).
 * @param message     Human-readable error message. Empty → null.
 * @param detailsJson Optional JSON object with extra diagnostics. Empty
 *                    → null; malformed JSON is silently dropped.
 */
public fun a2uiFailRpc(
    instanceId: String,
    surfaceId: String,
    requestId: String,
    code: String,
    message: String,
    detailsJson: String
) {
    A2UIBridgeManager.failAgentRequestJson(
        instanceId = instanceId,
        surfaceId = surfaceId,
        requestId = requestId,
        code = code,
        message = message.takeIf { it.isNotEmpty() },
        detailsJson = detailsJson.takeIf { it.isNotEmpty() }
    )
}

/**
 * Fire a renderer-to-agent RPC (`callAgentFunction`) and arrange for
 * the response to be delivered through a C callback.
 *
 * ## Callback transport
 *
 * The caller passes a C function pointer `onComplete` and an opaque
 * `callbackCtx` cookie. When the Kotlin RPC dispatcher resolves the
 * pending call (success, failure, timeout, or surface close), we
 * invoke `onComplete(callbackCtx, responseCStr)` with the full wire-
 * response JSON serialised the same way as `invokeAgentFunctionJson`:
 *
 * ```
 *   {"surfaceId":"...","requestId":"...","result":...}                // success
 *   {"surfaceId":"...","requestId":"...","error":{"code":"...", ...}} // failure
 * ```
 *
 * The C string buffer is allocated with `memScoped` on the Kotlin
 * side and *only* valid for the duration of the `onComplete` call —
 * the C++ shim MUST copy it (typically via `napi_create_string_utf8`
 * inside a `napi_call_threadsafe_function` callback) before returning.
 *
 * ## Threading
 *
 * `onComplete` is invoked on whatever thread the response arrives on
 * (Kotlin core's stream-parse thread for successful replies, the
 * dispatcher's timer thread for timeouts, the destroy-time cleanup
 * thread for surface close). The C++ NAPI shim is expected to hop
 * the JSON onto the ArkTS main thread via
 * `napi_call_threadsafe_function`.
 *
 * ## Error handling
 *
 * Returns `null` when the instance is unknown or the stream is still
 * in v0.9 mode (no requestId minted → `onComplete` is NOT invoked).
 * The caller should treat `null` as a synchronous error and clean up
 * the `callbackCtx` immediately.
 *
 * @param instanceId   A2UI DSL surface instance id.
 * @param surfaceId    surfaceId to embed in the outbound envelope.
 * @param name         Agent function name.
 * @param argsJson     JSON object serialised as string. Empty/malformed
 *                     → `{}`.
 * @param timeoutMs    Dispatcher timeout in ms. `0` means "use the
 *                     dispatcher default" (matches the iOS facade's
 *                     convention of using `0` as "no explicit value",
 *                     since ObjC/ArkTS cannot express `Long?`).
 * @param callbackCtx  Opaque cookie forwarded to `onComplete`. Typically
 *                     a C++-side `napi_threadsafe_function` handle wrapper.
 * @param onComplete   C function pointer invoked exactly once on
 *                     dispatch resolution. May be `null` to fire-and-
 *                     forget (the Kotlin dispatcher still runs the
 *                     outbound RPC, but no reply is forwarded).
 * @return The minted Kotlin-side requestId, or `null` on synchronous
 *         dispatch failure.
 */
@OptIn(ExperimentalForeignApi::class)
public fun a2uiInvokeAgentFunction(
    instanceId: String,
    surfaceId: String,
    name: String,
    argsJson: String,
    timeoutMs: Long,
    callbackCtx: COpaquePointer?,
    onComplete: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>?
): String? {
    return A2UIBridgeManager.invokeAgentFunctionJson(
        instanceId = instanceId,
        surfaceId = surfaceId,
        name = name,
        argsJson = argsJson.takeIf { it.isNotEmpty() },
        timeoutMs = timeoutMs.takeIf { it > 0L }
    ) { responseJson ->
        // Dispatcher-side completion: pass the JSON back to the C++
        // shim through the provided function pointer. `onComplete`
        // may be null when the caller opted out of a reply; in that
        // case we simply drop the response.
        if (onComplete != null) {
            // The C callee treats the string as borrowed; memScoped
            // guarantees the buffer stays alive for the duration of
            // the invoke() call below.
            memScoped {
                val cstr = responseJson.cstr.ptr
                onComplete.invoke(callbackCtx, cstr)
            }
        }
    }
}

/**
 * Install a C function pointer as this instance's inbound
 * `callRendererFunction` handler.
 *
 * Mirror of [a2uiInvokeAgentFunction] for the opposite direction: ArkTS
 * registers a JS callback through the NAPI shim, which wraps it in a
 * `napi_threadsafe_function` and hands us the holder pointer + a C
 * trampoline. We then install a Kotlin lambda that, whenever Kotlin's
 * `A2UIRpcDispatcher` dispatches an inbound call, serialises the request
 * payload and forwards it through the trampoline. The C++ shim is in
 * charge of hopping onto the ArkTS main thread.
 *
 * ## Why we do NOT thread-hop in Kotlin
 *
 * The companion `setRendererFunctionJsonHandler` runs the handler on
 * whatever thread the dispatcher is on — typically the Kotlin stream-
 * parse thread (Kuikly Context Thread for native-page / Kuikly Core
 * worker for embedded mode). The Android / iOS facades document the
 * same contract and let the host do its own hop. On OHOS we would
 * eventually need to reach the ArkTS main thread anyway, so the C++
 * shim is the natural place to do that hop — it already owns a
 * threadsafe-function to drive ArkTS callbacks for
 * [a2uiInvokeAgentFunction].
 *
 * ## Lifetime
 *
 * The NAPI shim owns the lifetime of `callbackCtx` (typically a
 * `RpcInboundHandlerHolder*`). The Kotlin side only treats the pointer
 * as an opaque cookie and never dereferences it, so there's nothing to
 * free here. On surface close `rendererFunctionHandlers` is cleared by
 * [A2UIBridgeManager.installRpcTransport] / surface-destroy, which in
 * turn simply drops our reference to the C function pointer; the shim
 * is expected to call [a2uiRemoveRendererFunctionHandler] from its own
 * ArkTS-side cleanup (`aboutToDisappear`) to release the tsfn.
 *
 * @param instanceId  A2UI DSL surface instance id to attach the handler to.
 * @param callbackCtx Opaque cookie forwarded verbatim to `onCall`.
 * @param onCall      C function pointer invoked for every inbound
 *                    `callRendererFunction` on this instance. May be
 *                    `null`, which is equivalent to calling
 *                    [a2uiRemoveRendererFunctionHandler].
 */
@OptIn(ExperimentalForeignApi::class)
public fun a2uiSetRendererFunctionHandler(
    instanceId: String,
    callbackCtx: COpaquePointer?,
    onCall: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>?
) {
    if (onCall == null) {
        A2UIBridgeManager.setRendererFunctionJsonHandler(instanceId, null)
        return
    }
    A2UIBridgeManager.setRendererFunctionJsonHandler(instanceId) { requestJson ->
        // Same lifetime contract as the outbound-RPC completion in
        // [a2uiInvokeAgentFunction]: the C callee treats the string as
        // borrowed; memScoped keeps the buffer alive for the duration
        // of the invoke() call.
        memScoped {
            val cstr = requestJson.cstr.ptr
            onCall.invoke(callbackCtx, cstr)
        }
    }
}

/**
 * Detach the inbound renderer-function handler previously installed via
 * [a2uiSetRendererFunctionHandler]. Idempotent; calling this on an
 * instance without an installed handler is a no-op.
 *
 * Hosts are expected to call this from their teardown path
 * (`aboutToDisappear` on OHOS, `deinit` on iOS, `onDestroy` on Android)
 * so the NAPI shim can release the associated threadsafe-function.
 */
public fun a2uiRemoveRendererFunctionHandler(instanceId: String) {
    A2UIBridgeManager.setRendererFunctionJsonHandler(instanceId, null)
}