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

package com.tencent.kuikly.a2ui.dsl

import com.tencent.kuikly.core.base.CrossPlatFeature
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.KotlinMethod
import com.tencent.kuikly.core.manager.NativeMethod
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.nvi.NativeBridge
import com.tencent.kuikly.core.views.CheckBoxView
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.a2ui.views.DateTimeInputView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class A2UIApplierCoreTest {
    private class RecordingNative : NativeBridge.NativeBridgeDelegate {
        val calls = mutableListOf<Pair<Int, List<Any?>>>()
        override fun callNative(methodId: Int, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?): Any? {
            calls.add(methodId to listOf(arg0, arg1, arg2, arg3, arg4, arg5))
            return when (methodId) {
                NativeMethod.CALCULATE_RENDER_VIEW_SIZE -> "40.0|20.0"
                NativeMethod.FIRE_FATAL_EXCEPTION -> error("Core fatal: $arg1")
                else -> null
            }
        }
    }

    private fun withPager(block: (A2UIPager, A2UIApplier, RecordingNative) -> Unit) {
        val id = "applier-core-test"
        val oldPage = BridgeManager.currentPageId
        val oldCatch = BridgeManager.catchException
        val oldFlat = CrossPlatFeature.isIgnoreRenderViewForFlatLayer
        val oldContext = NativeBridge.isContextThread
        val native = RecordingNative()
        BridgeManager.init(false)
        NativeBridge.isContextThread = true
        CrossPlatFeature.isIgnoreRenderViewForFlatLayer = true
        BridgeManager.registerNativeBridge(id, NativeBridge().apply { delegate = native })
        PagerManager.registerPageRouter(id) { A2UIPager() }
        BridgeManager.currentPageId = id
        try {
            PagerManager.createPager(id, id, """{"rootViewWidth":375.0,"rootViewHeight":10000.0,"nativeBuild":2}""")
            val pager = PagerManager.getPager(id) as A2UIPager
            block(pager, A2UIApplier(pager), native)
        } finally {
            BridgeManager.callKotlinMethod(KotlinMethod.DESTROY_INSTANCE, id)
            BridgeManager.currentPageId = oldPage
            BridgeManager.init(oldCatch)
            CrossPlatFeature.isIgnoreRenderViewForFlatLayer = oldFlat
            NativeBridge.isContextThread = oldContext
        }
    }

    @Test
    fun publicJvmSignaturesRemainCompatibleWithLegacy() {
        fun signatures(type: Class<*>): Set<String> = type.declaredMethods
            .filter {
                java.lang.reflect.Modifier.isPublic(it.modifiers) &&
                    !(it.isSynthetic && it.name.startsWith("access\$"))
            }
            .map { method ->
                val parameters = method.parameterTypes.joinToString { if (it == type) "Self" else it.name }
                "${method.name}($parameters):${method.returnType.name}"
            }.toSet()
        assertEquals(signatures(A2UILegacyApplier::class.java), signatures(A2UIApplier::class.java))
        assertEquals(listOf(A2UIHost::class.java), A2UIApplier::class.java.constructors.single().parameterTypes.toList())
    }

    @Test
    fun realCoreCreatesUpdatesAndRemovesTheSameView() = withPager { pager, applier, native ->
        applier.createComponent(1, "KRView", 0, 0, mapOf("width" to 120, "height" to 40, "backgroundColor" to "#FF0000"))
        val view = assertNotNull(pager.registry.getView(1))
        assertTrue(view is DivView)
        assertTrue(pager.getSubviews().contains(view))
        assertEquals(120f, view.flexNode.styleWidth)
        applier.updateComponent(1, "Text", mapOf("width" to 80))
        assertSame(view, pager.registry.getView(1))
        assertEquals(80f, view.flexNode.styleWidth)
        applier.performLayout()
        assertTrue(native.calls.any { it.first == NativeMethod.CREATE_RENDER_VIEW })
        applier.removeComponent(1, 0)
        assertNull(pager.registry.getView(1))
        assertFalse(pager.getSubviews().contains(view))
        assertNull(pager.registry.getTagByNativeRef(view.nativeRef))
    }

    @Test
    fun composeControlsReceivePropertiesBeforeCoreBodyInitialization() = withPager { pager, applier, _ ->
        applier.createComponent(1, "CheckBox", 0, 0, mapOf("width" to 24, "height" to 24, "checked" to true, "__primaryColor" to "#FF0000"))
        val check = pager.registry.getView(1) as CheckBoxView
        assertEquals(24f, check.flexNode.styleWidth)
        assertTrue(check.getSubviews().isNotEmpty())
        applier.createComponent(2, "DateTimeInput", 0, 1, mapOf("width" to 200, "height" to 40, "enableDate" to true, "enableTime" to false, "value" to "2026-11-23", "__dateTimeBindingPath" to "date"))
        val date = pager.registry.getView(2) as DateTimeInputView
        assertTrue(date.getEnableDate())
        assertFalse(date.getEnableTime())
        assertEquals("2026-11-23T00:00:00Z", date.getISOString())
        assertEquals("date", applier.getDateTimeBindingPath(2))
        assertTrue(date.getSubviews().isNotEmpty())
    }

    @Test
    fun scrollerChildrenUseTheRealContentContainer() = withPager { pager, applier, _ ->
        applier.createComponent(1, "List", 0, 0, mapOf("width" to 200, "height" to 120))
        applier.createComponent(2, "View", 1, 0, mapOf("width" to 80, "height" to 30))
        val container = assertNotNull(pager.registry.getContainer(1)).realContainerView()
        val child = assertNotNull(pager.registry.getView(2))
        assertTrue(container.getSubviews().contains(child))
        applier.removeComponent(2, 1)
        assertFalse(container.getSubviews().contains(child))
        assertNull(pager.registry.getView(2))
    }
}
