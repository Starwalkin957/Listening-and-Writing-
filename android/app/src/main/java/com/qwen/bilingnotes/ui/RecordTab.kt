package com.qwen.bilingnotes.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qwen.bilingnotes.MainViewModel
import com.qwen.bilingnotes.Phase

@Composable
fun RecordTab(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.toggle() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
                if (state.error != null) {
                    Text(
                        "错误：${state.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) vm.toggle()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = state.phase != Phase.DOWNLOADING && state.phase != Phase.LOADING,
            ) { Text(if (state.phase == Phase.RECORDING) "停止并保存" else "开始记录") }
        }

        // 系列选择
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            vm.repo.series.forEach { s ->
                FilterChip(
                    selected = state.seriesId == s.id,
                    onClick = { vm.setSeries(s.id) },
                    label = { Text(s.name) },
                    enabled = state.phase != Phase.RECORDING,
                )
            }
        }

        // 同传开关
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("逐句同传（中↔英）", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.autoTranslate,
                onCheckedChange = { vm.setAutoTranslate(it) },
                enabled = state.llmConfigured,
            )
        }
        if (!state.llmConfigured) {
            Text(
                "提示：在「设置」中配置 LLM 服务后可启用同传与总结。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // 说话人命名
        val speakers = state.utterances.map { it.speaker }.filter { it.isNotEmpty() }.distinct()
        if (speakers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                speakers.forEach { sp ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            renameTarget = sp
                            renameText = vm.speakerName(sp)
                        },
                        label = { Text(vm.speakerName(sp)) },
                    )
                }
            }
            Text(
                "点击说话人标签可重命名（如「张工」）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        val listState = rememberLazyListState()
        val total = state.utterances.size + if (state.partial.isNotEmpty()) 1 else 0
        LaunchedEffect(total, state.partial) {
            if (total > 0) listState.animateScrollToItem(total - 1)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.utterances, key = { it.id }) { u ->
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
            if (state.partial.isNotEmpty()) {
                item {
                    Text(
                        state.partial + " ▌",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名说话人") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { vm.renameSpeaker(it, renameText.trim().ifEmpty { it }) }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }
}
