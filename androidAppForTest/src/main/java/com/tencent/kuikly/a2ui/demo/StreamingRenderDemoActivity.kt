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

package com.tencent.kuikly.a2ui.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tencent.kuikly.a2ui.render.android.A2UISurfaceView
import com.tencent.kuikly.a2ui.render.android.A2UISurfaceViewListener

/**
 * Streaming Render Test Activity.
 *
 * Simulates the behavior of an LLM-driven A2UI session: the server emits
 * A2UI v0.9 protocol envelopes (createSurface / updateComponents /
 * updateDataModel) in multiple chunks. Each chunk is scheduled on the main
 * thread with a small delay to mimic network streaming, so that the surface
 * is built up and the data model is filled in progressively.
 *
 * The rendered card is intentionally non-trivial (header image, title,
 * subtitle, rating row, price row, feature tags, ChildList review list,
 * description, action buttons) so that any layout jitter / flicker during
 * progressive rendering is easy to observe.
 *
 * All messages feed are strict A2UI v0.9 basic-catalog envelopes compliant
 * with `/a2ui/specification/v0_9/catalogs/basic/catalog.json`.
 */
class StreamingRenderDemoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StreamingRenderDemo"
        private const val SURFACE_ID = "streaming-demo-surface"
        private const val CATALOG_ID =
            "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"
        /** Inter-chunk delay (ms) to make streaming visually observable. */
        private const val CHUNK_INTERVAL_MS = 180L
    }

    private lateinit var surfaceView: A2UISurfaceView
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** Sequence counter used to build a fresh surfaceId for every click. */
    private var streamSeq: Int = 0
    private var currentSurfaceId: String = SURFACE_ID
    private var streaming: Boolean = false

    // ---- Divider Y-coordinate logger ----
    /** Last observed Y (top) of the divider-like view relative to surfaceView, in px. -1 means unknown. */
    private var lastDividerY: Int = -1
    /** Last observed height of the divider-like view, in px. */
    private var lastDividerH: Int = -1
    /** GlobalLayout listener used to track divider Y changes over time. */
    private var dividerLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        initSurface()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        uninstallDividerLogger()
        if (::surfaceView.isInitialized) {
            surfaceView.destroy()
        }
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF6F3C"))
            setPadding(dp(24), dp(48), dp(24), dp(16))
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "←"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "流式渲染测试"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(back)
        headerRow.addView(title)
        header.addView(headerRow)

        val headerSub = TextView(this).apply {
            text = "点击下方按钮按 A2UI v0.9 协议分段推送 envelope，观察 UI 是否抖动"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#FFE3D1"))
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(headerSub)
        root.addView(header)

        // Controls row
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        startButton = Button(this).apply {
            text = "开始流式渲染"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF6F3C"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { onStartClicked() }
        }
        controls.addView(startButton)

        statusText = TextView(this).apply {
            text = "就绪"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        controls.addView(statusText)
        root.addView(controls)

        // Scrollable card container
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }

        val cardContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        surfaceView = A2UISurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        cardContainer.addView(surfaceView)
        scrollContent.addView(cardContainer)

        scroll.addView(scrollContent)
        root.addView(scroll)

        return root
    }

    private fun initSurface() {
        surfaceView.init("{\"debug\": true}")
        surfaceView.setListener(object : A2UISurfaceViewListener {
            override fun onAction(surfaceId: String, tag: Int, action: String, params: String?) {
                Log.d(TAG, "onAction: surfaceId=$surfaceId tag=$tag action=$action params=$params")
                Toast.makeText(
                    this@StreamingRenderDemoActivity,
                    "action: $action",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onDataModelChanged(dataModelJson: String, changedKey: String) {
                Log.d(TAG, "onDataModelChanged: key=$changedKey")
            }

            override fun onOpenUrl(url: String) {
                Log.d(TAG, "onOpenUrl: $url")
                try {
                    val uri = android.net.Uri.parse(url)
                    val i = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(i)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open URL: $url", e)
                }
            }
        })
    }

    private fun onStartClicked() {
        if (streaming) {
            Toast.makeText(this, "流式渲染进行中...", Toast.LENGTH_SHORT).show()
            return
        }
        // Each click resets to a brand-new surfaceId to avoid "surface already exists" error.
        streamSeq++
        currentSurfaceId = "$SURFACE_ID-$streamSeq"
        streaming = true
        startButton.isEnabled = false
        startButton.alpha = 0.6f
        statusText.text = "推送中... (surfaceId=$currentSurfaceId)"

        // Reset surface view so previous render is torn down before new stream starts.
        uninstallDividerLogger()
        surfaceView.destroy()
        // Re-create the surfaceView in-place to clear previous content.
        val parent = surfaceView.parent as? FrameLayout
        parent?.removeView(surfaceView)
        surfaceView = A2UISurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent?.addView(surfaceView)
        initSurface()
        installDividerLogger()
        lastDividerY = -1
        lastDividerH = -1

        scheduleStreamChunks(buildChunks(currentSurfaceId))
    }

    /**
     * Schedule each chunk to be written with [CHUNK_INTERVAL_MS] interval,
     * then call [A2UISurfaceView.finishStream] at the end.
     *
     * Also emits a one-line `chunk #N ↓` marker log right before each writeStream
     * so that subsequent divider-Y logs can be correlated with the chunk that
     * triggered them.
     */
    private fun scheduleStreamChunks(chunks: List<String>) {
        val total = chunks.size
        for ((index, chunk) in chunks.withIndex()) {
            handler.postDelayed({
                try {
                    val preview = chunk.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && it != "{" } ?: ""
                    Log.d(TAG, "chunk #${index + 1}/$total ↓  $preview")
                    surfaceView.writeStream(chunk)
                    statusText.text = "推送中... (${index + 1}/$total)"
                } catch (e: Exception) {
                    Log.e(TAG, "writeStream failed at chunk $index: ${e.message}", e)
                }
            }, CHUNK_INTERVAL_MS * (index + 1))
        }
        handler.postDelayed({
            surfaceView.finishStream()
            statusText.text = "流式渲染完成 ($total 段)"
            streaming = false
            startButton.isEnabled = true
            startButton.alpha = 1f
            Log.d(TAG, "finishStream called, total chunks = $total")
        }, CHUNK_INTERVAL_MS * (total + 1))
    }

    // region === Divider Y-coordinate logger ===

    /**
     * Install a global layout listener on [surfaceView] that scans the rendered
     * view hierarchy after every layout pass, picks the view that "looks like"
     * the A2UI Divider (very short height, wide width), and logs its Y position
     * (top, relative to surfaceView) and height whenever either changes.
     *
     * This deliberately does NOT depend on any internal A2UI SDK API — it only
     * uses Android View APIs so it works regardless of how the renderer maps
     * A2UI components to native views.
     */
    private fun installDividerLogger() {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val found = findDividerLike(surfaceView)
            if (found == null) {
                if (lastDividerY != -1) {
                    Log.d(TAG, "divider: (not found yet; was y=$lastDividerY)")
                }
                return@OnGlobalLayoutListener
            }
            val (view, yPx, hPx) = found
            if (yPx != lastDividerY || hPx != lastDividerH) {
                val density = resources.displayMetrics.density
                val yDp = yPx / density
                val hDp = hPx / density
                val delta = if (lastDividerY == -1) 0 else (yPx - lastDividerY)
                val deltaDp = delta / density
                val arrow = when {
                    lastDividerY == -1 -> "init"
                    delta > 0 -> "↓ down"
                    delta < 0 -> "↑ up"
                    else -> "="
                }
                Log.d(
                    TAG,
                    "divider y=${yPx}px (${"%.1f".format(yDp)}dp) " +
                            "h=${hPx}px (${"%.1f".format(hDp)}dp) " +
                            "Δ=${delta}px (${"%.1f".format(deltaDp)}dp) $arrow  " +
                            "view=${view.javaClass.simpleName}"
                )
                lastDividerY = yPx
                lastDividerH = hPx
            }
        }
        dividerLayoutListener = listener
        surfaceView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun uninstallDividerLogger() {
        val listener = dividerLayoutListener ?: return
        if (::surfaceView.isInitialized) {
            // viewTreeObserver may be stale after view detach; guard with try.
            try {
                surfaceView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            } catch (_: Exception) {
                // ignore
            }
        }
        dividerLayoutListener = null
    }

    /**
     * Recursively search [root]'s descendants for a view that visually matches
     * an A2UI horizontal Divider.
     *
     * Heuristic:
     *   - measured height in [1, 6*density] px   (Divider is a thin line)
     *   - measured width  > 100*density px       (Divider stretches wide)
     *   - is a leaf-ish View (no children, or children don't satisfy size)
     *   - visibility == VISIBLE
     *
     * Returns (view, topRelativeToRoot, height) or null.
     *
     * If multiple candidates exist, returns the one closest to the vertical
     * center of [root] (most likely to be our /divider_1 component).
     */
    private fun findDividerLike(root: View): Triple<View, Int, Int>? {
        val density = resources.displayMetrics.density
        val minW = (100 * density).toInt()
        val maxH = (6 * density).toInt()
        val candidates = mutableListOf<Triple<View, Int, Int>>()
        collectDividerLike(root, root, 0, candidates, minW, maxH)
        if (candidates.isEmpty()) return null
        // Prefer the candidate whose Y is closest to root's center.
        val centerY = root.height / 2
        return candidates.minByOrNull { kotlin.math.abs(it.second - centerY) }
    }

    private fun collectDividerLike(
        root: View,
        node: View,
        offsetY: Int,
        out: MutableList<Triple<View, Int, Int>>,
        minW: Int,
        maxH: Int
    ) {
        if (node.visibility != View.VISIBLE) return
        val myTop = offsetY + node.top
        if (node is ViewGroup) {
            for (i in 0 until node.childCount) {
                collectDividerLike(root, node.getChildAt(i), myTop, out, minW, maxH)
            }
        }
        // Treat non-container views (or small containers) as potential dividers.
        val w = node.width
        val h = node.height
        if (node !== root && h in 1..maxH && w >= minW) {
            out += Triple(node, myTop, h)
        }
    }

    // endregion

    // region === Chunk builders ===

    /**
     * Build an ordered list of A2UI v0.9 envelope JSON strings to be streamed
     * sequentially. The feed is intentionally split into many very-small
     * envelopes so that each step changes only a tiny portion of the UI, to
     * make progressive-rendering jitter easy to observe.
     *
     * High-level phases:
     *   P0  createSurface
     *   P1  skeleton: root -> main_col, 4 empty section containers one by one
     *   P2  header_section: image, title, subtitle appended one by one
     *   P3  middle_section: rating row then its children; price row then its children; tags row + template
     *   P4  bottom_section: reviews title, reviews list + template; description; action row (buttons appended one by one)
     *   P5  dataModel: product fields pushed field-by-field
     *   P6  dataModel: tag items appended one by one via /product/tags/N
     *   P7  dataModel: review items appended one by one via /reviews/N
     *   P8  dataModel: toggle isFavorite
     */
    private fun buildChunks(sid: String): List<String> {
        val list = mutableListOf<String>()

        // ----- P0: create surface -----
        list += chunkCreateSurface(sid)

        // ----- P1: skeleton, 5 small steps -----
        // (1a) root Card + empty main_col
        list += updateComponents(sid, """
              { "id": "root", "component": "Card", "child": "main_col" },
              { "id": "main_col", "component": "Column", "children": [] }
        """)
        // (1b) attach header_section (empty) to main_col
        list += updateComponents(sid, """
              { "id": "header_section", "component": "Column", "children": [] },
              { "id": "main_col", "component": "Column", "children": ["header_section"] }
        """)
        // (1c) append middle_section
        list += updateComponents(sid, """
              { "id": "middle_section", "component": "Column", "children": [] },
              { "id": "main_col", "component": "Column",
                "children": ["header_section", "middle_section"] }
        """)
        // (1d) append divider
        list += updateComponents(sid, """
              { "id": "divider_1", "component": "Divider", "axis": "horizontal" },
              { "id": "main_col", "component": "Column",
                "children": ["header_section", "middle_section", "divider_1"] }
        """)
        // (1e) append bottom_section
        list += updateComponents(sid, """
              { "id": "bottom_section", "component": "Column", "children": [] },
              { "id": "main_col", "component": "Column",
                "children": ["header_section", "middle_section", "divider_1", "bottom_section"] }
        """)

        // ----- P2: header_section fills in 3 steps -----
        // (2a) cover image
        list += updateComponents(sid, """
              {
                "id": "cover_image",
                "component": "Image",
                "url": { "path": "/product/imageUrl" },
                "description": { "path": "/product/name" },
                "fit": "cover",
                "variant": "largeFeature"
              },
              { "id": "header_section", "component": "Column",
                "children": ["cover_image"] }
        """)
        // (2b) product name
        list += updateComponents(sid, """
              {
                "id": "product_name",
                "component": "Text",
                "text": { "path": "/product/name" },
                "variant": "h3"
              },
              { "id": "header_section", "component": "Column",
                "children": ["cover_image", "product_name"] }
        """)
        // (2c) subtitle
        list += updateComponents(sid, """
              {
                "id": "product_subtitle",
                "component": "Text",
                "text": { "path": "/product/subtitle" },
                "variant": "caption"
              },
              { "id": "header_section", "component": "Column",
                "children": ["cover_image", "product_name", "product_subtitle"] }
        """)

        // ----- P3: middle_section -----
        // (3a) attach empty rating_row to middle_section
        list += updateComponents(sid, """
              { "id": "rating_row", "component": "Row", "children": [], "align": "center" },
              { "id": "middle_section", "component": "Column",
                "children": ["rating_row"] }
        """)
        // (3b) stars_text added into rating_row
        list += updateComponents(sid, """
              {
                "id": "stars_text",
                "component": "Text",
                "text": { "path": "/product/stars" },
                "variant": "body"
              },
              { "id": "rating_row", "component": "Row",
                "children": ["stars_text"], "align": "center" }
        """)
        // (3c) review_count_text added into rating_row
        list += updateComponents(sid, """
              {
                "id": "review_count_text",
                "component": "Text",
                "text": {
                  "call": "formatString",
                  "args": {
                    "value": "(${'$'}{formatNumber(value: ${'$'}{/product/reviewCount})} ${'$'}{pluralize(value: ${'$'}{/product/reviewCount}, one: 'review', other: 'reviews')})"
                  },
                  "returnType": "string"
                },
                "variant": "caption"
              },
              { "id": "rating_row", "component": "Row",
                "children": ["stars_text", "review_count_text"], "align": "center" }
        """)
        // (3d) attach empty price_row
        list += updateComponents(sid, """
              { "id": "price_row", "component": "Row", "children": [],
                "align": "center", "justify": "start" },
              { "id": "middle_section", "component": "Column",
                "children": ["rating_row", "price_row"] }
        """)
        // (3e) current_price
        list += updateComponents(sid, """
              {
                "id": "current_price",
                "component": "Text",
                "text": {
                  "call": "formatCurrency",
                  "args": {
                    "value": { "path": "/product/price" },
                    "currency": "USD"
                  },
                  "returnType": "string"
                },
                "variant": "h2"
              },
              { "id": "price_row", "component": "Row",
                "children": ["current_price"], "align": "center", "justify": "start" }
        """)
        // (3f) original_price
        list += updateComponents(sid, """
              {
                "id": "original_price",
                "component": "Text",
                "text": {
                  "call": "formatCurrency",
                  "args": {
                    "value": { "path": "/product/originalPrice" },
                    "currency": "USD"
                  },
                  "returnType": "string"
                },
                "variant": "caption"
              },
              { "id": "price_row", "component": "Row",
                "children": ["current_price", "original_price"],
                "align": "center", "justify": "start" }
        """)
        // (3g) tags_row (ChildList template) + template definition
        list += updateComponents(sid, """
              {
                "id": "tag_item_tpl",
                "component": "Text",
                "text": { "path": "label" },
                "variant": "caption"
              },
              {
                "id": "tags_row",
                "component": "Row",
                "children": { "componentId": "tag_item_tpl", "path": "/product/tags" },
                "align": "center"
              },
              { "id": "middle_section", "component": "Column",
                "children": ["rating_row", "price_row", "tags_row"] }
        """)

        // ----- P4: bottom_section -----
        // (4a) reviews title
        list += updateComponents(sid, """
              { "id": "reviews_title", "component": "Text",
                "text": "Latest Reviews", "variant": "h5" },
              { "id": "bottom_section", "component": "Column",
                "children": ["reviews_title"] }
        """)
        // (4b) reviews list container + template definitions
        list += updateComponents(sid, """
              {
                "id": "review_author",
                "component": "Text",
                "text": {
                  "call": "formatString",
                  "args": { "value": "${'$'}{author}:" },
                  "returnType": "string"
                },
                "variant": "caption"
              },
              {
                "id": "review_body",
                "component": "Text",
                "text": { "path": "content" },
                "variant": "body"
              },
              {
                "id": "review_row_tpl",
                "component": "Row",
                "children": ["review_author", "review_body"],
                "align": "start"
              },
              {
                "id": "reviews_list",
                "component": "Column",
                "children": { "componentId": "review_row_tpl", "path": "/reviews" }
              },
              { "id": "bottom_section", "component": "Column",
                "children": ["reviews_title", "reviews_list"] }
        """)
        // (4c) description title
        list += updateComponents(sid, """
              { "id": "description_title", "component": "Text",
                "text": "Description", "variant": "h5" },
              { "id": "bottom_section", "component": "Column",
                "children": ["reviews_title", "reviews_list", "description_title"] }
        """)
        // (4d) description text
        list += updateComponents(sid, """
              {
                "id": "description_text",
                "component": "Text",
                "text": { "path": "/product/description" },
                "variant": "body"
              },
              { "id": "bottom_section", "component": "Column",
                "children": ["reviews_title", "reviews_list", "description_title", "description_text"] }
        """)
        // (4e) empty action_row
        list += updateComponents(sid, """
              { "id": "action_row", "component": "Row", "children": [],
                "justify": "spaceBetween", "align": "center" },
              { "id": "bottom_section", "component": "Column",
                "children": ["reviews_title", "reviews_list", "description_title", "description_text", "action_row"] }
        """)
        // (4f) buy button
        list += updateComponents(sid, """
              { "id": "buy_btn_label", "component": "Text", "text": "Buy Now" },
              {
                "id": "buy_btn",
                "component": "Button",
                "child": "buy_btn_label",
                "variant": "primary",
                "action": {
                  "event": {
                    "name": "buyNow",
                    "context": {
                      "productId": { "path": "/product/id" },
                      "price": { "path": "/product/price" }
                    }
                  }
                }
              },
              { "id": "action_row", "component": "Row",
                "children": ["buy_btn"],
                "justify": "spaceBetween", "align": "center" }
        """)
        // (4g) favorite button
        list += updateComponents(sid, """
              {
                "id": "fav_btn_label",
                "component": "Text",
                "text": {
                  "call": "formatString",
                  "args": {
                    "value": "♥ Favorite [${'$'}{/product/isFavorite}]"
                  },
                  "returnType": "string"
                }
              },
              {
                "id": "fav_btn",
                "component": "Button",
                "child": "fav_btn_label",
                "variant": "default",
                "action": {
                  "event": {
                    "name": "toggleFavorite",
                    "context": {
                      "productId": { "path": "/product/id" }
                    }
                  }
                }
              },
              { "id": "action_row", "component": "Row",
                "children": ["buy_btn", "fav_btn"],
                "justify": "spaceBetween", "align": "center" }
        """)

        // ----- P5: dataModel - product fields pushed one by one -----
        // Initialize /product as empty object first, and /reviews as empty array.
        list += updateDataModelPath(sid, "/product", "{}")
        list += updateDataModelPath(sid, "/reviews", "[]")

        // field-by-field push
        list += updateDataModelPath(sid, "/product/id", "\"SKU-20261116-001\"")
        list += updateDataModelPath(sid, "/product/name", "\"Streaming Test Headphones\"")
        list += updateDataModelPath(sid, "/product/subtitle",
            "\"Over-ear · Active Noise Cancellation · 40h Battery\"")
        list += updateDataModelPath(sid, "/product/imageUrl",
            "\"https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600&h=360&fit=crop\"")
        list += updateDataModelPath(sid, "/product/stars", "\"★★★★☆\"")
        list += updateDataModelPath(sid, "/product/reviewCount", "1286")
        list += updateDataModelPath(sid, "/product/price", "199.99")
        list += updateDataModelPath(sid, "/product/originalPrice", "259.99")
        list += updateDataModelPath(sid, "/product/isFavorite", "false")
        list += updateDataModelPath(sid, "/product/description",
            "\"A relatively complex A2UI card used to stress-test progressive streaming rendering. " +
            "Watch for layout shifts as each chunk arrives: cover image, titles, rating, price row, " +
            "tag row, reviews list, description and action buttons should appear step by step " +
            "without jittering the previously-rendered content.\"")

        // ----- P6: tags appended one by one via array index path -----
        // /product/tags must be an array first.
        list += updateDataModelPath(sid, "/product/tags", "[]")
        list += updateDataModelPath(sid, "/product/tags/0", "{\"label\": \"#Hot\"}")
        list += updateDataModelPath(sid, "/product/tags/1", "{\"label\": \"#FreeShipping\"}")
        list += updateDataModelPath(sid, "/product/tags/2", "{\"label\": \"#1YrWarranty\"}")

        // ----- P7: reviews appended one by one via /reviews/N -----
        list += updateDataModelPath(sid, "/reviews/0",
            "{\"author\": \"Alice\", \"content\": \"Sound is crystal clear, ANC works wonders on the subway.\"}")
        list += updateDataModelPath(sid, "/reviews/1",
            "{\"author\": \"Bob\", \"content\": \"Comfort could be better for long sessions, but battery life is amazing.\"}")
        list += updateDataModelPath(sid, "/reviews/2",
            "{\"author\": \"Carol\", \"content\": \"Pairs instantly with my laptop and phone. Great for calls.\"}")
        list += updateDataModelPath(sid, "/reviews/3",
            "{\"author\": \"Dave\", \"content\": \"Best wireless headphones I've owned in this price range.\"}")
        list += updateDataModelPath(sid, "/reviews/4",
            "{\"author\": \"Eve\", \"content\": \"Bass is punchy and highs are detailed. Build quality feels premium.\"}")

        // ----- P8: flip isFavorite -----
        list += updateDataModelPath(sid, "/product/isFavorite", "true")

        return list
    }

    /**
     * Build an `updateComponents` envelope wrapping the given component array body.
     * @param body raw JSON text of the inner components array elements (no surrounding brackets).
     */
    private fun updateComponents(sid: String, body: String): String = """
        {
          "version": "v0.9",
          "updateComponents": {
            "surfaceId": "$sid",
            "components": [
$body
            ]
          }
        }
    """.trimIndent()

    /**
     * Build an `updateDataModel` envelope that patches a single JSON path.
     * @param valueJson the raw JSON text of the value (string literal must include quotes).
     */
    private fun updateDataModelPath(sid: String, path: String, valueJson: String): String = """
        {
          "version": "v0.9",
          "updateDataModel": {
            "surfaceId": "$sid",
            "path": "$path",
            "value": $valueJson
          }
        }
    """.trimIndent()

    private fun chunkCreateSurface(sid: String): String = """
        {
          "version": "v0.9",
          "createSurface": {
            "surfaceId": "$sid",
            "catalogId": "$CATALOG_ID",
            "theme": {
              "primaryColor": "#FF6F3C",
              "agentDisplayName": "StreamingTestAgent"
            }
          }
        }
    """.trimIndent()

    // endregion
}
