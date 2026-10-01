package com.jarves.mh.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forge.build.NativeCompatibility

@Composable
internal fun CompatibilityDialog(report: NativeCompatibility.Report, message: String?, busy: Boolean, onApply: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Android compatibility") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(report.summary)
            report.notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it) }
            report.migrationJson?.let { json ->
                Text("Preview: .forge/project.json")
                Text("Applying this file makes Forge configuration authoritative. Existing Gradle files remain, but Forge will no longer read their settings.")
                SelectionContainer { Text(json, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = {
        if (report.migrationJson != null && report.profileMatches) TextButton(onClick = onApply, enabled = !busy) { Text(if (busy) "Applying…" else "Apply Forge config") }
    }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Close") } })
}
