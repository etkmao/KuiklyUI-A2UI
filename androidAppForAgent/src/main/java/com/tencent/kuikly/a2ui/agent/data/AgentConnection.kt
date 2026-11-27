package com.tencent.kuikly.a2ui.agent.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class AgentConnection(
    val baseUrl: String,
    val routesJson: String,
    val headerName: String = "",
    val headerValue: String = ""
) {
    fun validate(allowHttp: Boolean) {
        val url = baseUrl.toHttpUrlOrNull() ?: error("请输入有效的服务地址")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "服务地址不能包含凭据、查询或片段"
        }
        require(allowHttp || url.isHttps) { "非调试版本只允许 HTTPS" }
        val routes = JSONObject(routesJson)
        ROUTE_KEYS.forEach { key ->
            val path = routes.optString(key, DEFAULT_ROUTES[key] ?: "")
            require(path.startsWith('/') && !path.startsWith("//") && !path.contains("..") && !path.contains('?') && !path.contains('#')) {
                "请配置有效的 $key 相对路径"
            }
        }
        require(headerName.none { it <= ' ' || it >= '\u007f' || it == ':' } && headerValue.none { it == '\r' || it == '\n' }) {
            "请求头格式无效"
        }
        require(headerValue.isEmpty() || headerName.isNotBlank()) { "请填写请求头名称" }
    }

    fun url(key: String, vararg parameters: Pair<String, String>): String {
        val routes = JSONObject(routesJson)
        var route = if (!routes.has(key) && key in DEFAULT_ROUTES) DEFAULT_ROUTES.getValue(key) else routes.getString(key)
        parameters.forEach { (name, value) ->
            require(value.matches(Regex("[A-Za-z0-9._:-]+"))) { "无效路径参数" }
            route = route.replace("{$name}", value)
        }
        require(!route.contains('{') && !route.contains('}')) { "路径参数未配置完整" }
        return baseUrl.trimEnd('/') + route
    }

    companion object {
        private const val ACTION_ROUTE = "/v1/sessions/{sessionId}/actions"
        val DEFAULT_ROUTES = mapOf(
            "actions" to ACTION_ROUTE,
            "renderers" to "/v1/sessions/{sessionId}/renderers",
            "renderer" to "/v1/sessions/{sessionId}/renderers/{rendererId}",
            "rpc" to "/v1/sessions/{sessionId}/rpc",
            "rpcRenderer" to "/v1/sessions/{sessionId}/rpc/renderer",
            "rpcResponses" to "/v1/sessions/{sessionId}/rpc/responses",
            "rpcCall" to "/v1/sessions/{sessionId}/rpc/{functionCallId}",
            "rpcCancel" to "/v1/sessions/{sessionId}/rpc/{functionCallId}/cancel"
        )
        val ROUTE_KEYS = listOf("sessions", "runs", "events", "snapshot", "card", "messages", "cancel") + DEFAULT_ROUTES.keys
        fun emptyRoutes(): String = JSONObject().apply {
            ROUTE_KEYS.forEach { put(it, DEFAULT_ROUTES[it] ?: "") }
        }.toString(2)
    }
}
