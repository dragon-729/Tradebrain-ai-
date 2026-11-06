package com.tradebrain.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tradebrain.ai.ui.AppRoot
import com.tradebrain.ai.ui.TradeBrainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TradeBrainTheme { AppRoot() } }
    }
}
