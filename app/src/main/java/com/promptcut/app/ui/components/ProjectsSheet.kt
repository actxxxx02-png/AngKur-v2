package com.promptcut.app.ui.components

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promptcut.app.EditorViewModel
import com.promptcut.app.data.db.ProjectEntity
import com.promptcut.app.round1
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(vm: EditorViewModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    val ctx = LocalContext.current
    val active by vm.activeProjects.collectAsStateWithLifecycle()
    val trashed by vm.trashedProjects.collectAsStateWithLifecycle()
    var showTrash by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("projects_close_button")) {
                        Icon(Icons.Default.Close, contentDescription = "Close projects")
                    }
                },
                actions = {
                    TextButton(onClick = { showImportDialog = true }) { Text("Import") }
                    IconButton(onClick = { showNewDialog = true }, modifier = Modifier.testTag("new_project_button")) {
                        Icon(Icons.Default.Add, contentDescription = "New project")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showTrash, onClick = { showTrash = false }, label = { Text("Active (${active.size})") })
                FilterChip(selected = showTrash, onClick = { showTrash = true }, label = { Text("Trash (${trashed.size})") })
            }

            val list = if (showTrash) trashed else active
            if (list.isEmpty()) {
                Text(
                    if (showTrash) "Trash is empty." else "No saved projects yet. Tap + or start editing to autosave.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { p ->
                        OutlinedCard(
                            onClick = { if (!showTrash) vm.openSavedProject(p.id) },
                            modifier = Modifier.fillMaxWidth(),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${p.clipCount} clips · ${round1(p.durationSec)}s · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.updatedAtMs))}",
                                            style = MonoTimecodeStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (!showTrash) {
                                        IconButton(onClick = { renameTarget = p }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Rename project")
                                        }
                                        IconButton(onClick = { vm.duplicateSavedProject(p.id) }) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate project")
                                        }
                                        IconButton(onClick = {
                                            vm.exportProjectJsonString(p.id) { json ->
                                                ctx.startActivity(
                                                    Intent.createChooser(
                                                        Intent(Intent.ACTION_SEND).setType("application/json")
                                                            .putExtra(Intent.EXTRA_SUBJECT, "${p.name}.promptcut.json")
                                                            .putExtra(Intent.EXTRA_TEXT, json),
                                                        "Export Project JSON",
                                                    )
                                                )
                                            }
                                        }) {
                                            Icon(Icons.Default.Share, contentDescription = "Export project JSON")
                                        }
                                        IconButton(onClick = { vm.softDeleteSavedProject(p.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Move to trash")
                                        }
                                    } else {
                                        IconButton(onClick = { vm.restoreSavedProject(p.id) }) {
                                            Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore project")
                                        }
                                        TextButton(onClick = { vm.purgeSavedProject(p.id) }) { Text("Purge") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        var name by remember { mutableStateOf("New Edit") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("New Project") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { vm.createNewProject(name); showNewDialog = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("Cancel") } },
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { vm.renameProject(target.id, newName); renameTarget = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    if (showImportDialog) {
        var rawJson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Project / Legacy Plan JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste exported PromptCut v2 JSON or legacy v1 EditPlan JSON.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = rawJson, onValueChange = { rawJson = it }, minLines = 4, maxLines = 8)
                }
            },
            confirmButton = {
                Button(enabled = rawJson.isNotBlank(), onClick = {
                    vm.importProjectJsonString(rawJson)
                    showImportDialog = false
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Cancel") } },
        )
    }
}
