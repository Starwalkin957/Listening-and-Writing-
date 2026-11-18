package com.qwen.bilingnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qwen.bilingnotes.ui.LibraryTab
import com.qwen.bilingnotes.ui.RecordTab
import com.qwen.bilingnotes.ui.SettingsTab
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

@Composable
fun App(
    recordVm: MainViewModel = viewModel(),
    libraryVm: LibraryViewModel = viewModel(),
    settingsVm: SettingsViewModel = viewModel(),
) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("记录", "纪要库", "设置")

    Scaffold { padding ->
        androidx.compose.foundation.layout.Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = {
                            tab = i
                            if (i == 1) libraryVm.refresh()
                            if (i == 0) recordVm.refreshLlmState()
                        },
                        text = { Text(t) },
                    )
                }
            }
            when (tab) {
                0 -> RecordTab(recordVm)
                1 -> LibraryTab(libraryVm)
                else -> SettingsTab(settingsVm, libraryVm)
            }
        }
    }
}
