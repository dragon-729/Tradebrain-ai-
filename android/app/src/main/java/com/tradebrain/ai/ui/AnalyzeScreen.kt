package com.tradebrain.ai.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradebrain.ai.data.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class AnalyzeVM : ViewModel() {
    var history by mutableStateOf<List<AnalysisSummary>>(emptyList())
    var current by mutableStateOf<AnalysisDto?>(null)
    var chat by mutableStateOf<List<MsgDto>>(emptyList())
    var analyzing by mutableStateOf(false)
    var asking by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    init { loadHistory() }

    fun loadHistory() {
        viewModelScope.launch {
            loading = true
            call { Graph.api.analyses() }.onSuccess { history = it }.onFailure { error = it.friendly() }
            loading = false
        }
    }

    fun analyze(ctx: Context, uri: Uri) {
        viewModelScope.launch {
            analyzing = true; error = null
            call {
                val bytes = prepareImage(ctx, uri)
                val part = MultipartBody.Part.createFormData(
                    "image", "chart.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                Graph.api.analyze(part)
            }.onSuccess { current = it; chat = emptyList(); loadHistory() }
                .onFailure { error = it.friendly() }
            analyzing = false
        }
    }

    fun open(id: String) {
        viewModelScope.launch {
            analyzing = true; error = null
            call { Graph.api.analysis(id) }
                .onSuccess { current = it; chat = it.messages.orEmpty() }
                .onFailure { error = it.friendly() }
            analyzing = false
        }
    }

    fun ask(q: String) {
        val a = current ?: return
        if (q.isBlank() || asking) return
        viewModelScope.launch {
            asking = true; error = null
            chat = chat + MsgDto("user", q)
            call { Graph.api.askAbout(a.id, AskBody(q)) }
                .onSuccess { chat = chat + MsgDto("assistant", it.answer) }
                .onFailure { error = it.friendly(); chat = chat.dropLast(1) }
            asking = false
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            call { Graph.api.deleteAnalysis(id) }.onSuccess {
                if (current?.id == id) current = null
                loadHistory()
            }.onFailure { error = it.friendly() }
        }
    }

    fun close() { current = null; chat = emptyList() }
}

@Composable
fun AnalyzeScreen(vm: AnalyzeVM = viewModel()) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.analyze(ctx, uri)
    }
    BackHandler(enabled = vm.current != null) { vm.close() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        vm.error?.let { ErrorBanner(it) }
        val cur = vm.current
        if (cur == null) {
            Text("Analyze a chart", style = MaterialTheme.typography.headlineSmall)
            Muted("Upload a screenshot (JPG, PNG, WEBP). The AI describes visible structure only and will say when a chart is unclear.")
            Button(onClick = { picker.launch("image/*") }, enabled = !vm.analyzing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("Choose chart image")
            }
            if (vm.analyzing) { Loading(); Muted("Analysing… this can take up to a minute.") }
            Section("History") {
                if (vm.loading) Loading()
                else if (vm.history.isEmpty()) Muted("No analyses yet.")
                vm.history.forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable { vm.open(h.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Bold(h.title ?: "Chart analysis")
                            Muted(listOfNotNull(h.pattern, h.created_at?.take(10)).joinToString(" · "))
                        }
                        IconButton(onClick = { vm.delete(h.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        } else {
            TextButton(onClick = vm::close) { Text("← Back to history") }
            Text(cur.title ?: "Chart analysis", style = MaterialTheme.typography.headlineSmall)
            ResultView(cur)
            AskSection(vm)
        }
        Muted(DISCLAIMER)
    }
}

@Composable
private fun ResultView(a: AnalysisDto) {
    val r = a.result ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (r.image_quality?.sufficient == false)
            ErrorBanner("Image quality may limit accuracy: " + r.image_quality?.issues.orEmpty().joinToString("; "))
        r.chart_overview?.let {
            Section("Chart overview") {
                Labeled("Type", it.chart_type); Labeled("Timeframe", it.timeframe ?: "not visible")
                Text(it.structure.orEmpty())
            }
        }
        Section("Detected patterns") {
            val ps = r.patterns.orEmpty()
            if (ps.isEmpty()) Muted("No pattern could be identified with enough visual evidence.")
            ps.forEach { p ->
                Bold(p.name ?: "Pattern")
                Muted(listOfNotNull(p.category, p.confidence?.let { "confidence: $it" }).joinToString(" · "))
                Text(p.explanation.orEmpty()); Labeled("Evidence", p.visual_evidence)
            }
        }
        r.trend?.let { Section("Trend") { Bold(it.classification ?: "Unclear"); Text(it.reasoning.orEmpty()) } }
        r.levels?.let { l ->
            Section("Important levels") {
                l.support.orEmpty().forEach { Bullet("Support: $it") }
                l.resistance.orEmpty().forEach { Bullet("Resistance: $it") }
                Labeled("Breakout area", l.breakout_area); Labeled("Breakdown area", l.breakdown_area)
                Muted(l.note.orEmpty())
            }
        }
        if (r.indicators.orEmpty().isNotEmpty()) Section("Indicators to consider") {
            r.indicators.orEmpty().forEach {
                Bold(it.name ?: "")
                Text(it.purpose.orEmpty()); Labeled("Check", it.what_to_check); Labeled("Limits", it.limitations)
                if (it.visible_on_chart != true) Muted("Not visible on this chart — no signal is claimed.")
            }
        }
        r.scenarios?.let { s ->
            Section("Possible scenarios") {
                listOf("Bullish" to s.bullish, "Bearish" to s.bearish, "Sideways" to s.sideways).forEach { (n, sc) ->
                    if (sc != null) { Bold(n); Text(sc.description.orEmpty()); Labeled("Invalidated if", sc.invalidation) }
                }
            }
        }
        if (r.risks.orEmpty().isNotEmpty()) Section("Risks & uncertainty") { r.risks.orEmpty().forEach { Bullet(it) } }
        Section("From your knowledge library") {
            val m = r.library_matches.orEmpty()
            if (m.isEmpty()) Muted("No related passages found in your uploaded documents.")
            m.forEach { Bold("${it.title} · p.${it.page}"); Muted(it.snippet.orEmpty()) }
            if (m.isNotEmpty()) Muted("These are related passages, not proof that this chart matches an example.")
        }
    }
}

@Composable
private fun AskSection(vm: AnalyzeVM) {
    var q by remember { mutableStateOf("") }
    Section("Ask AI about this chart") {
        vm.chat.forEach { m ->
            Text((if (m.role == "user") "You: " else "AI: ") + m.content, style = MaterialTheme.typography.bodyMedium)
        }
        if (vm.asking) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("e.g. What if support breaks?") })
            IconButton(onClick = { vm.ask(q); q = "" }, enabled = !vm.asking) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
