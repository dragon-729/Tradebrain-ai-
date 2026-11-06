package com.tradebrain.ai

import android.app.Application
import com.tradebrain.ai.data.Graph

class TradeBrainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
