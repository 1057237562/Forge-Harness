package com.jarves.mh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NativeBuildDialog(state: AppUiState, onCancel: () -> Unit, onOpenDiagnostic: (BuildDiagnostic) -> Boolean, onInstallArtifact: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Android build") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.androidBuildMessage.orEmpty())
                state.androidArtifact?.let { artifact ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${artifact.packageName} · ${artifact.versionName}", style = MaterialTheme.typography.titleSmall)
                        Text("APK · ${artifact.bytes / 1024} KiB · build ${artifact.buildId.take(8)}", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text("SHA-256: ${artifact.sha256}", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                        TextButton(onClick = onInstallArtifact, enabled = !state.androidBuildRunning && !state.isRunning && !state.projectTerminalRunning && !state.fileDirty && !state.fileSaving) { Text("Install this APK") }
                    }
                }
                if (state.androidBuildRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.androidBuildDiagnostics.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        state.androidBuildDiagnostics.forEach { diagnostic ->
                            TextButton(onClick = { if (onOpenDiagnostic(diagnostic)) onDismiss() },
                                enabled = !state.androidBuildRunning && diagnostic.file.isNotBlank() && diagnostic.line > 0) {
                                Text("${diagnostic.file.substringAfterLast('/')}:${diagnostic.line} · ${diagnostic.message}", maxLines = 3)
                            }
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        state.androidBuildLogs.joinToString("\n").ifBlank { "Waiting for native compiler…" },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (state.androidBuildRunning) TextButton(onClick = onCancel) { Text("Cancel build") }
        },
    )
}
