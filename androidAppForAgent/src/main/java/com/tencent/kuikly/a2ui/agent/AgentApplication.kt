package com.tencent.kuikly.a2ui.agent

import android.app.Application
import com.tencent.kuikly.a2ui.render.android.A2UIAndroidNativeMethodHandler

class AgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        A2UIAndroidNativeMethodHandler.install(this)
    }
}
