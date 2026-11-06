package com.tradebrain.ai.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class LibraryVM : ViewModel() {
    var docs by mutableStateOf<List<DocumentDto>>(emptyList())
    var loading by mutableStateOf(true)
    var uploading by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var sharePublic by mutableStateOf(false)
    val hasProcessing get() = docs.any { it.status == "processing" }

    init { load() }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) loading = true
            call { Graph.api.documents() }.onSuccess { docs = it; if (!silent) error = null }
                .onFailure { if (!silent) error = it.friendly() }
            loading = false
        }
    }

    fun upload(ctx: Context, uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                uploading++
                call {
                    val f = readPicked(ctx, u)
                    val part = MultipartBody.Part.createFormData("file", f.name, f.bytes.toRequestBody(f.mime.toMediaType()))
                    Graph.api.uploadDocument(part, (if (sharePublic) "public" else "private").toRequestBody("text/plain".toMediaType()))
                }.onFailure { error = it.friendly() }
                uploading--
            }
            load(silent = true)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            call { Graph.api.deleteDocument(id) }.onSuccess { load(true) }.onFailure { error = it.friendly() }
        }
    }

    fun retry(id: String) {
        viewModelScope.launch {
            call { Graph.api.reprocess(id) }.onSuccess { load(true) }.onFailure { error = it.friendly() }
        }
    }

    fun toggleVisibility(d: DocumentDto) {
        val next = if (d.visibility == "public") "private" else "public"
        viewModelScope.launch {
            call { Graph.api.setVisibility(d.id, VisibilityBody(next)) }.onSuccess { load(true) }
                .onFailure { error = it.friendly() }
        }
    }
}

@Composable
fun LibraryScreen(vm: LibraryVM = viewModel()) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(ctx, uris)
    }
    var confirmDelete by remember { mutableStateOf<DocumentDto?>(null) }

    LaunchedEffect(vm.hasProcessing) {
        while (vm.hasProcessing) { delay(3000); vm.load(silent = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("My Knowledge", style = MaterialTheme.typography.headlineSmall)
        vm.error?.let { ErrorBanner(it, { vm.load() }) }
        Button(onClick = { picker.launch(arrayOf("application/pdf", "text/plain", "image/*")) },
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp))
            Text(if (vm.uploading > 0) "Uploading ${vm.uploading}…" else "Upload PDF / TXT / image")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.sharePublic, { vm.sharePublic = it })
            Spacer(Modifier.width(8.dp))
            Column {
                Text(if (vm.sharePublic) "New uploads are PUBLIC" else "New uploads are private")
                if (vm.sharePublic) Muted("Only share material you have the right to distribute.")
            }
        }
        if (vm.loading) Loading()
        else if (vm.docs.isEmpty()) EmptyState("No documents yet. Upload a trading PDF to build your library.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.docs, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Bold(d.title ?: d.filename ?: "Document")
                                Muted(listOfNotNull(d.category, d.page_count?.let { "$it pages" }, d.visibility).joinToString(" · "))
                            }
                            if (d.status == "failed") IconButton(onClick = { vm.retry(d.id) }) { Icon(Icons.Default.Refresh, "Retry") }
                            IconButton(onClick = { confirmDelete = d }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                        when (d.status) {
                            "processing" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Muted("Extracting and indexing…") }
                            "failed" -> Text(d.error ?: "Processing failed", color = MaterialTheme.colorScheme.error)
                            else -> if (!d.summary.isNullOrBlank()) Text(d.summary, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                        }
                        TextButton(onClick = { vm.toggleVisibility(d) }) {
                            Text(if (d.visibility == "public") "Make private" else "Make public")
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { d ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete document?") },
            text = { Text("This removes the file, its extracted text and its search index.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}
