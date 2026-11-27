package com.tencent.kuikly.a2ui.agent

import android.app.Application
import com.tencent.kuikly.a2ui.agent.adapter.AgentVideoViewAdapter
import com.tencent.kuikly.a2ui.render.android.A2UIAndroidNativeMethodHandler
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager

class AgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (KuiklyRenderAdapterManager.krVideoViewAdapter == null) {
            KuiklyRenderAdapterManager.krVideoViewAdapter = AgentVideoViewAdapter()
        }
        A2UIAndroidNativeMethodHandler.install(this)
    }
}
