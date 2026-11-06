package com.tradebrain.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradebrain.ai.data.*
import kotlinx.coroutines.launch

data class ChatItem(val fromUser: Boolean, val text: String, val sources: List<SourceDto> = emptyList())

class ChatVM : ViewModel() {
    var items by mutableStateOf<List<ChatItem>>(emptyList())
    var scope by mutableStateOf("personal")
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var conversationId: String? = null

    fun newChat() { items = emptyList(); conversationId = null; error = null }

    fun send(text: String) {
        if (text.isBlank() || sending) return
        viewModelScope.launch {
            sending = true; error = null
            items = items + ChatItem(true, text)
            call { Graph.api.chat(ChatBody(text, conversationId, scope)) }
                .onSuccess { conversationId = it.conversation_id; items = items + ChatItem(false, it.answer, it.sources.orEmpty()) }
                .onFailure { error = it.friendly(); items = items.dropLast(1) }
            sending = false
        }
    }
}

private val SCOPES = listOf("personal" to "My library", "public" to "Public", "both" to "Both", "general" to "General AI")

@Composable
fun AssistantScreen(vm: ChatVM = viewModel()) {
    val clip = LocalClipboardManager.current
    val state = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(vm.items.size) { if (vm.items.isNotEmpty()) state.animateScrollToItem(vm.items.lastIndex) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI Assistant", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::newChat) { Text("New chat") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SCOPES.forEach { (k, label) ->
                FilterChip(selected = vm.scope == k, onClick = { vm.scope = k }, label = { Text(label) })
            }
        }
        Muted(if (vm.scope == "general") "Answers come from general AI knowledge, not your documents."
        else "Answers cite your documents when relevant passages are found.")
        vm.error?.let { ErrorBanner(it) }
        LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            if (vm.items.isEmpty()) item { EmptyState("Ask about candlestick patterns, indicators, or anything in your uploaded documents.") }
            itemsIndexed(vm.items) { _, m ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = if (m.fromUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m.text)
                        m.sources.forEach { Muted("[S${it.n}] ${it.title} · p.${it.page}") }
                        if (!m.fromUser) {
                            if (m.sources.isEmpty() && vm.scope != "general") Muted("No document sources were used for this answer.")
                            IconButton(onClick = { clip.setText(AnnotatedString(m.text)) }) { Icon(Icons.Default.ContentCopy, "Copy") }
                        }
                    }
                }
            }
            if (vm.sending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask a question…") }, maxLines = 4)
            IconButton(onClick = { vm.send(input); input = "" }, enabled = !vm.sending) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
