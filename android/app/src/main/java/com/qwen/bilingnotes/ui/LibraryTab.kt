package com.qwen.bilingnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qwen.bilingnotes.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryTab(vm: LibraryViewModel) {
    val state by vm.state.collectAsState()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    var showNewSeries by remember { mutableStateOf(false) }
    var newSeriesName by remember { mutableStateOf("") }
    var renameId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var assignId by remember { mutableStateOf<Long?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) state.selected?.let { vm.importFile(it.id, uri) }
    }
    val exportMdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) state.selected?.let { vm.exportSessionMarkdown(it.id, uri) }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.busyText != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Spacer(Modifier.padding(4.dp))
                Text(state.busyText!!, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.message != null) {
            Text(
                state.message!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { vm.clearMessage() },
            )
        }

        val selected = state.selected
        if (selected == null) {
            // ---- 列表 ----
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filterSeriesId == 0L,
                    onClick = { vm.setFilter(0) },
                    label = { Text("全部") },
                )
                state.seriesOptions.forEach { (id, name) ->
                    FilterChip(
                        selected = state.filterSeriesId == id,
                        onClick = { vm.setFilter(id) },
                        label = { Text(name) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showNewSeries = true },
                    label = { Text("+ 新系列") },
                )
            }

            if (state.sessions.isEmpty()) {
                Text(
                    "还没有纪要。到「记录」页开始一段录音，停止后自动入库。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessions, key = { it.id }) { s ->
                    Card(
                        Modifier.fillMaxWidth().clickable { vm.select(s.id) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.title, fontWeight = FontWeight.Bold)
                            Text(
                                "${fmt.format(Date(s.createdAt))} · ${vm.repoSeriesName(s.seriesId)} · " +
                                    "${s.utterances.size} 句" +
                                    if (s.attachments.isNotEmpty()) " · ${s.attachments.size} 附件" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            if (s.summary.isNotEmpty()) {
                                Text(
                                    s.summary.take(80) + "…",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ---- 详情 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.select(null) }) { Text("← 返回") }
                Text(
                    selected.title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable {
                        renameId = selected.id
                        renameText = selected.title
                    },
                )
            }
            Text(
                "系列：${vm.repoSeriesName(selected.seriesId)}（点击更换）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { assignId = selected.id },
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { vm.summarize(selected.id) }) { Text("生成总结") }
                OutlinedButton(onClick = { vm.correct(selected.id) }) { Text("术语纠错") }
                OutlinedButton(onClick = { vm.translateAll(selected.id) }) { Text("全文翻译") }
                OutlinedButton(onClick = {
                    importFileLauncher.launch(arrayOf("*/*"))
                }) { Text("整合文件") }
                OutlinedButton(onClick = {
                    exportMdLauncher.launch(selected.title + ".md")
                }) { Text("导出MD") }
                OutlinedButton(onClick = { deleteId = selected.id }) { Text("删除") }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (selected.summary.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("内容总结", fontWeight = FontWeight.Bold)
                                Text(selected.summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                items(selected.utterances, key = { it.id }) { u ->
                    Column {
                        val sp = vm.speakerName(u.speaker)
                        if (sp.isNotEmpty()) {
                            Text(
                                sp,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(u.text, style = MaterialTheme.typography.bodyLarge)
                        if (u.translation.isNotEmpty()) {
                            Text(
                                u.translation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
                if (selected.corrected.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("术语纠错稿", fontWeight = FontWeight.Bold)
                                Text(selected.corrected, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                items(selected.attachments, key = { it.id }) { a ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("附件：${a.name}（${a.kind}）", fontWeight = FontWeight.Bold)
                            Text(
                                a.text.take(2000) + if (a.text.length > 2000) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // ---- 对话框 ----
    if (showNewSeries) {
        AlertDialog(
            onDismissRequest = { showNewSeries = false },
            title = { Text("新建系列") },
            text = {
                OutlinedTextField(
                    value = newSeriesName,
                    onValueChange = { newSeriesName = it },
                    label = { Text("系列名（如「操作系统课程」）") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.newSeries(newSeriesName)
                    newSeriesName = ""
                    showNewSeries = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewSeries = false }) { Text("取消") } },
        )
    }

    if (renameId != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("重命名纪要") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    renameId?.let { vm.renameSession(it, renameText.trim().ifEmpty { "未命名" }) }
                    renameId = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } },
        )
    }

    if (assignId != null) {
        AlertDialog(
            onDismissRequest = { assignId = null },
            title = { Text("移动到系列") },
            text = {
                Column {
                    state.seriesOptions.forEach { (id, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                assignId?.let { vm.assignSeries(it, id) }
                                assignId = null
                            },
                        ) {
                            RadioButton(
                                selected = state.selected?.seriesId == id,
                                onClick = {
                                    assignId?.let { vm.assignSeries(it, id) }
                                    assignId = null
                                },
                            )
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { assignId = null }) { Text("关闭") } },
        )
    }

    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("删除纪要") },
            text = { Text("删除后不可恢复（可先导出备份）。确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteId?.let { vm.deleteSession(it) }
                    deleteId = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
        )
    }
}
