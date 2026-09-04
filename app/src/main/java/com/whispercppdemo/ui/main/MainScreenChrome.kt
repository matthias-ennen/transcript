package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Navigation and top-bar composition for the app shell. */
internal enum class AppPage {
    MAIN,
    SETTINGS,
    AI_DIAGNOSTICS,
    AI_PERFORMANCE,
    WHISPER_SETTINGS,
    VAD_SETTINGS,
    SONG_ISOLATION_SETTINGS,
    ABOUT
}

private val advancedSettingsPages = listOf(
    AppPage.WHISPER_SETTINGS,
    AppPage.VAD_SETTINGS,
    AppPage.SONG_ISOLATION_SETTINGS,
    AppPage.AI_PERFORMANCE,
    AppPage.AI_DIAGNOSTICS
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptTopBar(
    page: AppPage,
    appLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    onNavigate: (AppPage) -> Unit
) {
    var pageMenuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(
                        enabled = page in advancedSettingsPages,
                        onClick = { pageMenuExpanded = true }
                    )
                ) {
                    Text(if (page == AppPage.MAIN) "Simple Transcript" else page.title)
                    if (page in advancedSettingsPages) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            "Einstellungsseite auswählen",
                            Modifier
                                .padding(start = 4.dp)
                                .size(18.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = pageMenuExpanded,
                    onDismissRequest = { pageMenuExpanded = false }
                ) {
                    advancedSettingsPages.forEach { destination ->
                        DropdownMenuItem(
                            text = { Text(destination.title) },
                            onClick = {
                                pageMenuExpanded = false
                                onNavigate(destination)
                            }
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (
                page != AppPage.MAIN &&
                page != AppPage.AI_DIAGNOSTICS &&
                page != AppPage.AI_PERFORMANCE &&
                page != AppPage.WHISPER_SETTINGS &&
                page != AppPage.VAD_SETTINGS &&
                page != AppPage.SONG_ISOLATION_SETTINGS
            ) {
                IconButton(onClick = { onNavigate(AppPage.MAIN) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                }
            }
        },
        actions = {
            when (page) {
                AppPage.MAIN -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        AppLanguageSelector(
                            selected = appLanguage,
                            onSelected = onAppLanguageSelected
                        )
                        IconButton(
                            onClick = { onNavigate(AppPage.SETTINGS) },
                            modifier = Modifier.width(42.dp).height(44.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                        }
                        IconButton(
                            onClick = { onNavigate(AppPage.ABOUT) },
                            modifier = Modifier.width(42.dp).height(44.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "Über die App")
                        }
                    }
                }
                AppPage.AI_DIAGNOSTICS, AppPage.AI_PERFORMANCE, AppPage.WHISPER_SETTINGS,
                AppPage.VAD_SETTINGS, AppPage.SONG_ISOLATION_SETTINGS -> {
                    OutlinedButton(
                        onClick = { onNavigate(AppPage.SETTINGS) },
                        modifier = Modifier.padding(end = 8.dp).height(40.dp),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Verlassen")
                    }
                }
                else -> Unit
            }
        }
    )
}

@Composable
private fun AppLanguageSelector(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            border = null,
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier.width(48.dp).height(44.dp)
        ) {
            Text(selected.flag)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "GUI-Sprache auswählen")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text("${language.flag}  ${language.displayName}") },
                    onClick = {
                        onSelected(language)
                        expanded = false
                    }
                )
            }
        }
    }
}

private val AppPage.title: String
    get() = when (this) {
        AppPage.MAIN -> "Simple Transcript"
        AppPage.SETTINGS -> "Einstellungen"
        AppPage.AI_DIAGNOSTICS -> "KI-Diagnose"
        AppPage.AI_PERFORMANCE -> "KI-Leistung und Hardware"
        AppPage.WHISPER_SETTINGS -> "Whisper-Einstellungen"
        AppPage.VAD_SETTINGS -> "VAD-Einstellungen"
        AppPage.SONG_ISOLATION_SETTINGS -> "Stimmisolierungs-Leistung"
        AppPage.ABOUT -> "Über die App"
    }
