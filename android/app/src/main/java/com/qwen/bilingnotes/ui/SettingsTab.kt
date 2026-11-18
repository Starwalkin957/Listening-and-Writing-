package com.qwen.bilingnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qwen.bilingnotes.LibraryViewModel
import com.qwen.bilingnotes.SettingsViewModel

@Composable
fun SettingsTab(vm: SettingsViewModel, libraryVm: LibraryViewModel) {
    val state by vm.state.collectAsState()
    val libState by libraryVm.state.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) libraryVm.exportBackup(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) libraryVm.importBackup(uri) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LLM 服务（翻译 / 总结 / 纠错）", style = MaterialTheme.typography.titleMedium)
        Text(
            "识别与声纹始终在本机离线运行。此处的 LLM 仅用于翻译、总结和术语纠错，" +
                "可填云端兼容接口（如阿里百炼 compatible-mode），也可填局域网自建服务" +
                "（ollama / llama.cpp server / vLLM 的 OpenAI 兼容地址）。不配置则这些功能禁用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        OutlinedTextField(
            value = state.settings.baseUrl,
            onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
            label = { Text("服务地址（…/v1）") },
            placeholder = { Text("https://dashscope.aliyuncs.com/compatible-mode/v1") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.settings.apiKey,
            onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
            label = { Text("API Key（局域网服务可留空）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = state.settings.model,
            onValueChange = { v -> vm.update { it.copy(model = v) } },
            label = { Text("模型名") },
            placeholder = { Text("qwen-plus / qwen2.5:7b …") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记录时逐句自动同传")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.settings.autoTranslate,
                onCheckedChange = { v -> vm.update { it.copy(autoTranslate = v) } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.save() }) { Text("保存") }
            OutlinedButton(onClick = { vm.testConnection() }, enabled = !state.testing) {
                Text(if (state.testing) "测试中…" else "测试连接")
            }
        }
        if (state.message != null) {
            Text(
                state.message!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("历史备份", style = MaterialTheme.typography.titleMedium)
        Text(
            "导出整个纪要库（含系列、说话人命名、附件文本）为备份包；换机或重装后导入即可恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("双语语音纪要备份.zip") }) {
                Text("导出备份")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Text("导入备份")
            }
        }
        if (libState.message != null) {
            Text(
                libState.message!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("计算机专业知识库", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("术语词条：${state.glossarySize} 条")
                Text(
                    "双层术语保障：识别端热词增强（约200词）+ 理解端术语知识库。" +
                        "总结与纠错时自动注入文本中出现的术语释义。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("关于准确率", style = MaterialTheme.typography.titleMedium)
        Text(
            "安静环境、标准口音下单人识别典型准确率约 90%～95%；中英混说、噪声、多人抢话会下降。" +
                "热词、自动标点、术语纠错可进一步提升可用性。建议用自建测试集验收，" +
                "不要相信任何「全场景 99%」的承诺。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(24.dp))
    }
}
