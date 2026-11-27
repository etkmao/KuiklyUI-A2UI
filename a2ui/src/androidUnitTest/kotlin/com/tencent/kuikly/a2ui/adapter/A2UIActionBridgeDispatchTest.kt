package com.tencent.kuikly.a2ui.adapter

import com.tencent.kuikly.a2ui.bridge.A2UIBridgeManager
import com.tencent.kuikly.a2ui.bridge.A2UIKotlinMethod
import com.tencent.kuikly.a2ui.bridge.A2UINativeMethod
import com.tencent.kuikly.a2ui.dsl.A2UIDSLBridge
import com.tencent.kuikly.a2ui.function.A2UICustomFunctionRegistry
import com.tencent.kuikly.core.nvi.NativeBridge
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class A2UIActionBridgeDispatchTest {
    private fun assertLocalDispatch(name: String, nativeMethod: Int) {
        val instance = "action-bridge-$name"
        val adapter = A2UIProtocolAdapter()
        adapter.convertMessage(mapOf("version" to "1.0", "createSurface" to mapOf(
            "surfaceId" to "native-real", "catalogId" to "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",
            "dataModel" to mapOf("value" to "before"),
        )))
        adapter.convertMessage(mapOf("version" to "1.0", "updateComponents" to mapOf(
            "surfaceId" to "native-real", "components" to listOf(
                mapOf("id" to "button", "component" to "Button", "child" to "label", "action" to mapOf(
                    "functionCall" to mapOf("@call" to name, "args" to mapOf("value" to mapOf("@path" to "/value"))),
                )),
                mapOf("id" to "label", "component" to "Text", "text" to "Run"),
            ),
        )))
        val commands = mutableListOf<Pair<Int, List<Any?>>>()
        val native = NativeBridge().apply {
            delegate = object : NativeBridge.NativeBridgeDelegate {
                override fun callNative(methodId: Int, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?): Any? {
                    commands.add(methodId to listOf(arg0, arg1, arg2, arg3, arg4, arg5))
                    return null
                }
            }
        }
        val bridge = A2UIDSLBridge(instance, native).apply { protocolAdapter = adapter }
        var forwarded = 0
        A2UIBridgeManager.registerNativeBridge(instance, native)
        A2UIBridgeManager.registerDslBridge(instance, bridge)
        try {
            bridge.onAction = { _, _, action ->
                if (!A2UIBridgeManager.tryHandleFunctionCall(instance, action)) forwarded++
            }
            adapter.updateDataModelValue("value", "latest")
            bridge.handleViewClick(adapter.getActionTags().single())
            val execution = commands.filter { it.first == nativeMethod }.single()
            assertEquals(name, execution.second[1])
            assertEquals("latest", JSONObject(execution.second[2] as String).optString("value"))
            assertEquals(1, commands.count { it.first == nativeMethod })
            assertEquals(1, commands.count { it.first == A2UINativeMethod.FLUSH_UI_TASKS })
            assertEquals(0, forwarded)
            assertFalse(commands.any { it.first == A2UINativeMethod.ACTION_CALLBACK })
        } finally {
            A2UIBridgeManager.unregisterDslBridge(instance)
            A2UIBridgeManager.callKotlinMethod(A2UIKotlinMethod.DESTROY, instance, null, null)
        }
    }

    @Test
    fun builtinFunctionCallDispatchesToNativeExactlyOnce() {
        assertLocalDispatch("openUrl", A2UINativeMethod.EXECUTE_FUNCTION_CALL)
    }

    @Test
    fun customVoidFunctionCallDispatchesToNativeExactlyOnce() {
        val name = "actionBridgeVoid"
        A2UICustomFunctionRegistry.register(name, "void|sync")
        try {
            assertLocalDispatch(name, A2UINativeMethod.INVOKE_CUSTOM_FUNCTION)
        } finally {
            A2UICustomFunctionRegistry.unregister(name)
        }
    }
}
