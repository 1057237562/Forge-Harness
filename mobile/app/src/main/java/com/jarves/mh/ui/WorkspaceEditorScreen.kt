package com.jarves.mh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceEditorScreen(filePath: String, content: String?, loading: Boolean, editable: Boolean,
    dirty: Boolean, saving: Boolean, message: String?, targetLine: Int = 1, onEdit: (String) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    var editorValue by remember(filePath) { mutableStateOf(TextFieldValue(content.orEmpty())) }
    val focus = remember(filePath) { FocusRequester() }
    LaunchedEffect(content) {
        if (content != null && editorValue.text != content) editorValue = editorValue.copy(text = content)
    }
    LaunchedEffect(filePath, loading, targetLine) {
        if (!loading && content != null) {
            val start = dev.forge.build.DiagnosticLocation.lineOffset(content, targetLine)
            val end = content.indexOf('\n', start).let { if (it < 0) content.length else it }
            editorValue = TextFieldValue(content, if (targetLine > 1) TextRange(start, end) else TextRange(0))
            if (targetLine > 1) focus.requestFocus()
        }
    }
    var confirmDiscard by remember(filePath) { mutableStateOf(false) }
    val close = { if (!saving) { if (dirty) confirmDiscard = true else onClose() } }
    BackHandler(onBack = close)
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard unsaved changes?") },
        text = { Text("Your edits to ${filePath.substringAfterLast('/')} have not been saved.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
    Scaffold(topBar = {
        TopAppBar(title = { Text(filePath.substringAfterLast('/') + if (dirty) " *" else "") },
            navigationIcon = { TextButton(onClick = close, enabled = !saving) { Text("Back") } },
            actions = { TextButton(onClick = onSave, enabled = editable && dirty && !saving) { Text(if (saving) "Saving…" else "Save") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(filePath, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
            if (message != null) Text(message, modifier = Modifier.padding(12.dp), color = if (message == "Saved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (content != null) {
                if (!editable) Text("Editing paused while a build, Agent or terminal is running.", modifier = Modifier.padding(12.dp))
                OutlinedTextField(value = editorValue, onValueChange = { editorValue = it; onEdit(it.text) }, readOnly = !editable || saving,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp).focusRequester(focus),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}
