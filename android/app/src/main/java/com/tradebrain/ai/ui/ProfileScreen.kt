package com.tradebrain.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.tradebrain.ai.BuildConfig
import com.tradebrain.ai.data.Graph

@Composable
fun ProfileScreen() {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile", style = MaterialTheme.typography.headlineSmall)
        Section("Account") {
            Labeled("Email", Graph.session.email)
            OutlinedButton(onClick = { Graph.session.clear() }) { Text("Sign out") }
        }
        Section("About & credits") {
            Text("TradeBrain AI v${BuildConfig.VERSION_NAME}")
            Muted("AI chart analysis, a personal trading-knowledge library and a source-cited assistant.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { uri.openUri("https://github.com/dragon-729") }) { Text("GitHub") }
                FilledTonalButton(onClick = { uri.openUri("https://t.me/YogeshBhai729") }) { Text("Telegram") }
            }
            Muted("github.com/dragon-729  ·  @YogeshBhai729")
        }
        Section("Privacy") {
            Muted("Documents are private by default. Only publish material you have the right to distribute. " +
                "Deleting a document also deletes its extracted text and embeddings.")
        }
        Muted(DISCLAIMER)
    }
}
