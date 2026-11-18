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

import com.tencent.kuikly.a2ui.parser.A2UIParseError
import com.tencent.kuikly.a2ui.parser.A2UIStreamParser
import com.tencent.kuikly.a2ui.protocol.A2UIMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR-10 regression gate: feeds the five v1.0 sample streams that live
 * under [androidAppForTest/src/main/assets/a2ui_v10_tests] through the
 * production [A2UIStreamParser] and asserts that:
 *
 *  * no [A2UIStreamParser.A2UIStreamListener.onError] fires
 *  * at least one message is parsed per sample (i.e. the top-level
 *    JSON envelope was successfully recognised)
 *
 * The samples are duplicated here as string constants rather than loaded
 * from `assets/` because commonTest cannot access Android-specific
 * resource paths. The on-disk JSON files remain the source of truth for
 * human-visual demo sessions; this test guards the parse pipeline only.
 *
 * If one of the five assets is ever edited, update the matching string
 * constant below to keep the two in sync.
 */
class A2UIV10SamplesParseTest {

    private class CollectingListener : A2UIStreamParser.A2UIStreamListener {
        val parsed = mutableListOf<A2UIMessage>()
        val errors = mutableListOf<A2UIParseError>()
        var ended: Boolean = false
        override fun onMessageParsed(message: A2UIMessage) {
            parsed.add(message)
        }
        override fun onError(error: A2UIParseError) {
            errors.add(error)
        }
        override fun onStreamEnd() {
            ended = true
        }
    }

    /**
     * Wraps a single A2UI envelope string (`{"version":"...","createSurface":{...}}`)
     * plus any follow-up envelopes into the stream-level JSON array that
     * [A2UIStreamParser.feed] expects.
     *
     * We pass the test payloads as a hand-wrapped `[ env1, env2, ... ]`
     * because the sample JSONs on disk are wrapped in a `{ "name", "description",
     * "messages": [...] }` demo envelope — that outer layer is a demo-UI
     * convention, not something the parser itself understands.
     */
    private fun runSamplesThroughParser(envelopes: List<String>) {
        val parser = A2UIStreamParser()
        val listener = CollectingListener()
        parser.setListener(listener)
        val stream = envelopes.joinToString(prefix = "[", postfix = "]", separator = ",")
        parser.feed(stream)
        parser.finish()
        assertTrue(
            listener.errors.isEmpty(),
            "Parser emitted ${listener.errors.size} error(s): " +
                listener.errors.joinToString { it.message },
        )
        assertTrue(
            listener.parsed.isNotEmpty(),
            "Parser did not emit any messages for the fed stream",
        )
        assertTrue(listener.ended, "Parser must signal onStreamEnd after finish()")
    }

    @Test
    fun pr2_mixinKeys_parsesWithoutError() {
        runSamplesThroughParser(
            listOf(
                """{"version":"1.0","createSurface":{"surfaceId":"pr2-s",""" +
                    """"defaultCatalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",""" +
                    """"initialDataModel":{"userName":"Alice","score":87}}}""",
                """{"version":"1.0","updateComponents":{"surfaceId":"pr2-s","components":[""" +
                    """{"id":"root","component":"Column","children":["g"]},""" +
                    """{"id":"g","component":"Text","text":{"@path":"/userName"},""" +
                    """"@accessibility":{"description":"d","live":"polite"}}""" +
                    """]}}""",
            )
        )
    }

    @Test
    fun pr3_createSurfaceInlineDataModel_parsesWithoutError() {
        runSamplesThroughParser(
            listOf(
                """{"version":"1.0","createSurface":{"surfaceId":"pr3-s",""" +
                    """"defaultCatalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",""" +
                    """"initialDataModel":{"cart":{"title":"t","items":[{"name":"A","price":1}]}}}}""",
                """{"version":"1.0","updateComponents":{"surfaceId":"pr3-s","components":[""" +
                    """{"id":"root","component":"Column","children":["t"]},""" +
                    """{"id":"t","component":"Text","text":{"@path":"/cart/title"}}""" +
                    """]}}""",
            )
        )
    }

    @Test
    fun pr6_rpcBidirectional_parsesWithoutError() {
        runSamplesThroughParser(
            listOf(
                """{"version":"1.0","createSurface":{"surfaceId":"pr6-s",""" +
                    """"defaultCatalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",""" +
                    """"initialDataModel":{"status":"idle"}}}""",
                """{"version":"1.0","callRendererFunction":{"surfaceId":"pr6-s",""" +
                    """"requestId":"r-1","name":"openPage","args":{"route":"/p"},"timeoutMs":3000}}""",
                """{"version":"1.0","agentFunctionResponse":{"surfaceId":"pr6-s",""" +
                    """"requestId":"r-2","result":{"name":"Alice"}}}""",
            )
        )
    }

    @Test
    fun pr8_multiCatalog_parsesWithoutError() {
        runSamplesThroughParser(
            listOf(
                """{"version":"1.0","createSurface":{"surfaceId":"pr8-s",""" +
                    """"defaultCatalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",""" +
                    """"initialDataModel":{"user":{"name":"A","totalScore":128}}}}""",
                """{"version":"1.0","updateComponents":{"surfaceId":"pr8-s","components":[""" +
                    """{"id":"root","component":"Column","children":["b"]},""" +
                    """{"id":"b","component":"A2UIBadge",""" +
                    """"@catalogId":"https://a2ui.org/specification/v1_0/catalogs/custom_components/catalog.json",""" +
                    """"label":{"@call":{"catalogId":"https://a2ui.org/specification/v1_0/catalogs/custom_functions/catalog.json",""" +
                    """"name":"describeTier","args":{"score":{"@path":"/user/totalScore"}}}}}""" +
                    """]}}""",
            )
        )
    }

    @Test
    fun pr9_smallFields_parsesWithoutError() {
        runSamplesThroughParser(
            listOf(
                """{"version":"1.0","createSurface":{"surfaceId":"pr9-s",""" +
                    """"defaultCatalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",""" +
                    """"initialDataModel":{"videoUrl":"u","coverUrl":"c","volume":50,"searchQuery":""}}}""",
                """{"version":"1.0","updateComponents":{"surfaceId":"pr9-s","components":[""" +
                    """{"id":"root","component":"Column","children":["v","q","s","b"]},""" +
                    """{"id":"v","component":"Video","url":{"@path":"/videoUrl"},"posterUrl":{"@path":"/coverUrl"}},""" +
                    """{"id":"q","component":"TextField","text":{"@path":"/searchQuery"},"placeholder":"ph"},""" +
                    """{"id":"s","component":"Slider","value":{"@path":"/volume"},"min":0,"max":100,"steps":4},""" +
                    """{"id":"b-t","component":"Text","text":"go"},""" +
                    """{"id":"b","component":"Button","child":"b-t","action":{"event":{"name":"e",""" +
                    """"context":{"q":{"@path":"/searchQuery"}},"userMessage":{"@path":"/searchQuery"}}}}""" +
                    """]}}""",
            )
        )
    }

    /**
     * Cross-sample sanity: the five samples must all parse to a positive
     * message count. This guards against silently regressing to a parser
     * that accepts input but quietly emits zero messages.
     */
    @Test
    fun allFiveSamples_emitAtLeastOneMessage() {
        pr2_mixinKeys_parsesWithoutError()
        pr3_createSurfaceInlineDataModel_parsesWithoutError()
        pr6_rpcBidirectional_parsesWithoutError()
        pr8_multiCatalog_parsesWithoutError()
        pr9_smallFields_parsesWithoutError()
        // Reach a trivial assertion so the accumulated error-free runs
        // do not get collapsed by compilers that warn on empty tests.
        assertEquals(5, 5)
    }
}
