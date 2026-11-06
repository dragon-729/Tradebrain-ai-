package com.tradebrain.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradebrain.ai.data.*
import kotlinx.coroutines.launch

class HomeVM : ViewModel() {
    var stats by mutableStateOf<Stats?>(null)
    var recent by mutableStateOf<List<AnalysisSummary>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true; error = null
            call { Graph.api.stats() }.onSuccess { stats = it }.onFailure { error = it.friendly() }
            call { Graph.api.analyses() }.onSuccess { recent = it.take(5) }
            loading = false
        }
    }
}

@Composable
fun HomeScreen(onGo: (Tab) -> Unit, vm: HomeVM = viewModel()) {
    val name = Graph.session.email?.substringBefore("@") ?: "trader"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome, $name", style = MaterialTheme.typography.headlineSmall)
        vm.error?.let { ErrorBanner(it, vm::load) }
        if (vm.loading) Loading()
        vm.stats?.let { s ->
            Section("My knowledge") {
                Labeled("Documents", "${s.documents} (${s.ready_documents} ready)")
                Labeled("Chart analyses", s.analyses.toString())
                Labeled("Concepts extracted", s.concepts.toString())
            }
        }
        Section("Quick actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onGo(Tab.Analyze) }, Modifier.weight(1f)) { Text("Analyze chart") }
                FilledTonalButton(onClick = { onGo(Tab.Library) }, Modifier.weight(1f)) { Text("Upload") }
            }
            FilledTonalButton(onClick = { onGo(Tab.Assistant) }, Modifier.fillMaxWidth()) { Text("Ask AI about my library") }
        }
        Section("Recent analyses") {
            if (vm.recent.isEmpty() && !vm.loading) Muted("No analyses yet. Upload a chart screenshot to start.")
            vm.recent.forEach { a ->
                Column(Modifier.fillMaxWidth().clickable { onGo(Tab.Analyze) }.padding(vertical = 4.dp)) {
                    Bold(a.title ?: "Chart analysis")
                    Muted(listOfNotNull(a.pattern, a.created_at?.take(10)).joinToString(" · "))
                }
            }
        }
        Muted(DISCLAIMER)
    }
}
