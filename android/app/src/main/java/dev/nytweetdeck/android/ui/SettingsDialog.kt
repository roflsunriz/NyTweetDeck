package dev.nytweetdeck.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.BuildConfig
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.AccentColor
import dev.nytweetdeck.android.model.AppFontSize
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.DisplaySettings
import dev.nytweetdeck.android.model.NavigationPosition
import dev.nytweetdeck.android.model.ThemeMode
import dev.nytweetdeck.android.model.TranslationHealth
import dev.nytweetdeck.android.model.VideoQuality
import dev.nytweetdeck.android.update.ApkUpdateStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun SettingsDialog(
    state: DeckUiState,
    onDisplaySettingsChange: (DisplaySettings) -> Unit,
    selectedLanguageTag: String? = null,
    onLanguageChange: (String) -> Unit = {},
    selectedTranslationLanguageTag: String? = null,
    onTranslationLanguageChange: (String) -> Unit = {},
    onExport: () -> Unit,
    onImport: () -> Unit,
    transferStatus: TransferStatus,
    onDismiss: () -> Unit,
    onRefreshXApiMetadata: () -> Unit = {},
    apkUpdateStatus: ApkUpdateStatus = ApkUpdateStatus.NONE,
    onDownloadLatestApk: () -> Unit = {},
) {
    val settings = state.displaySettings()
    val currentLanguageTag = selectedLanguageTag?.substringBefore('-')
        ?: AppLocaleController.currentLanguageTag(LocalContext.current)
    val currentTranslationLanguageTag = selectedTranslationLanguageTag
        ?.substringBefore('-')
        ?: state.translationLanguageTag
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.settingsConflict) {
                    Text(
                        text = stringResource(R.string.settings_save_conflict),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("settings-save-conflict"),
                    )
                }
                SettingGroupTitle(stringResource(R.string.settings_display))
                SettingLanguagePicker(
                    label = stringResource(R.string.setting_language),
                    selectedTag = currentLanguageTag,
                    tag = "setting-language",
                    onSelect = onLanguageChange,
                )
                SettingSectionTitle(stringResource(R.string.setting_theme))
                SettingChoiceRow {
                    ThemeMode.entries.forEach { mode ->
                        SettingChoice(
                            label = themeModeLabel(mode),
                            selected = settings.themeMode == mode,
                            tag = "setting-theme-" + mode.name.lowercase(),
                            onClick = {
                                onDisplaySettingsChange(settings.copy(themeMode = mode))
                            },
                        )
                    }
                }
                SettingSectionTitle(stringResource(R.string.setting_font_size))
                SettingChoiceRow {
                    AppFontSize.entries.forEach { size ->
                        SettingChoice(
                            label = fontSizeLabel(size),
                            selected = settings.fontSize == size,
                            tag = "setting-font-" + size.name.lowercase(),
                            onClick = {
                                onDisplaySettingsChange(settings.copy(fontSize = size))
                            },
                        )
                    }
                }
                SettingSectionTitle(stringResource(R.string.setting_accent_color))
                SettingChoiceRow {
                    AccentColor.entries.forEach { accent ->
                        SettingChoice(
                            label = accentColorLabel(accent),
                            selected = settings.accentColor == accent,
                            tag = "setting-accent-" + accent.name.lowercase(),
                            color = accentColorPreview(accent),
                            onClick = {
                                onDisplaySettingsChange(settings.copy(accentColor = accent))
                            },
                        )
                    }
                }
                SettingCheckbox(
                    label = stringResource(R.string.compact_density),
                    checked = settings.compactDensity,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(compactDensity = it))
                    },
                    tag = "setting-compact-density",
                )
                SettingCheckbox(
                    label = stringResource(R.string.setting_reduce_motion),
                    checked = settings.reduceMotion,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(reduceMotion = it))
                    },
                    tag = "setting-reduce-motion",
                )
                SettingGroupTitle(stringResource(R.string.settings_feed_translation))
                SettingCheckbox(
                    label = stringResource(R.string.setting_auto_refresh),
                    checked = settings.autoRefreshTimelines,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(autoRefreshTimelines = it))
                    },
                    tag = "setting-auto-refresh",
                )
                SettingCheckbox(
                    label = stringResource(R.string.setting_auto_translate),
                    checked = settings.autoTranslatePosts,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(autoTranslatePosts = it))
                    },
                    tag = "setting-auto-translate",
                )
                SettingLanguagePicker(
                    label = stringResource(R.string.setting_translation_language),
                    selectedTag = currentTranslationLanguageTag,
                    tag = "setting-translation-language",
                    onSelect = onTranslationLanguageChange,
                )
                SettingGroupTitle(stringResource(R.string.settings_media))
                SettingCheckbox(
                    label = stringResource(R.string.setting_media_preview),
                    checked = settings.mediaPreview,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(mediaPreview = it))
                    },
                    tag = "setting-media-preview",
                )
                SettingCheckbox(
                    label = stringResource(R.string.setting_video_autoplay),
                    checked = settings.videoAutoplay,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(videoAutoplay = it))
                    },
                    tag = "setting-video-autoplay",
                )
                SettingCheckbox(
                    label = stringResource(R.string.setting_video_loop),
                    checked = settings.videoLoop,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(videoLoop = it))
                    },
                    tag = "setting-video-loop",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_video_volume),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.setting_video_volume_value,
                            settings.videoVolume,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = settings.videoVolume.toFloat(),
                    onValueChange = { volume ->
                        onDisplaySettingsChange(
                            settings.copy(videoVolume = volume.roundToInt().coerceIn(0, 100)),
                        )
                    },
                    valueRange = 0f..100f,
                    steps = 99,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setting-video-volume"),
                )
                SettingSectionTitle(stringResource(R.string.setting_video_quality))
                SettingChoiceRow {
                    VideoQuality.entries.forEach { quality ->
                        SettingChoice(
                            label = videoQualityLabel(quality),
                            selected = settings.videoQuality == quality,
                            tag = "setting-video-quality-" + quality.name.lowercase(),
                            onClick = {
                                onDisplaySettingsChange(settings.copy(videoQuality = quality))
                            },
                        )
                    }
                }
                SettingGroupTitle(stringResource(R.string.settings_navigation))
                SettingSectionTitle(stringResource(R.string.setting_navigation_position))
                SettingChoiceRow {
                    NavigationPosition.entries.forEach { position ->
                        SettingChoice(
                            label = stringResource(
                                when (position) {
                                    NavigationPosition.LEFT -> R.string.navigation_position_left
                                    NavigationPosition.BOTTOM -> R.string.navigation_position_bottom
                                },
                            ),
                            selected = settings.navigationPosition == position,
                            tag = "setting-navigation-" + position.name.lowercase(),
                            onClick = {
                                onDisplaySettingsChange(
                                    settings.copy(navigationPosition = position),
                                )
                            },
                        )
                    }
                }
                SettingCheckbox(
                    label = stringResource(R.string.show_main_navigation),
                    checked = settings.showMainNavigation,
                    onCheckedChange = {
                        onDisplaySettingsChange(settings.copy(showMainNavigation = it))
                    },
                    tag = "setting-show-main-navigation",
                )
                SettingGroupTitle(stringResource(R.string.apk_update_section))
                Text(
                    text = stringResource(R.string.apk_update_current_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onDownloadLatestApk,
                    enabled = apkUpdateStatus.canDownload,
                    modifier = Modifier.fillMaxWidth().testTag("download-latest-apk"),
                ) {
                    if (apkUpdateStatus == ApkUpdateStatus.CHECKING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (apkUpdateStatus == ApkUpdateStatus.CHECKING) {
                                R.string.apk_update_checking
                            } else {
                                R.string.apk_update_download
                            },
                        ),
                    )
                }
                when (apkUpdateStatus) {
                    ApkUpdateStatus.DOWNLOAD_STARTED -> Text(
                        text = stringResource(R.string.apk_update_started),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("apk-update-status"),
                    )
                    ApkUpdateStatus.FAILED -> Text(
                        text = stringResource(R.string.apk_update_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("apk-update-status"),
                    )
                    ApkUpdateStatus.UP_TO_DATE -> Text(
                        text = stringResource(R.string.apk_update_up_to_date),
                        modifier = Modifier.testTag("apk-update-status"),
                    )
                    ApkUpdateStatus.NONE, ApkUpdateStatus.CHECKING, ApkUpdateStatus.AVAILABLE -> Unit
                }
                SettingGroupTitle(stringResource(R.string.settings_transfer))
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().testTag("export-settings"),
                ) {
                    Text(stringResource(R.string.export_settings))
                }
                Button(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth().testTag("import-settings"),
                ) {
                    Text(stringResource(R.string.import_settings))
                }
                val statusText = when (transferStatus) {
                    TransferStatus.EXPORT_SUCCESS -> R.string.export_settings_success
                    TransferStatus.IMPORT_SUCCESS -> R.string.import_settings_success
                    TransferStatus.FAILED -> R.string.settings_transfer_failed
                    TransferStatus.NONE -> null
                }
                statusText?.let { resource ->
                    Text(
                        stringResource(resource),
                        color = if (transferStatus == TransferStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                SettingsDisclosure(
                    title = stringResource(R.string.settings_details),
                    tag = "settings-details",
                    expandOnError = state.liveError != null || state.xApiMetadataError,
                ) {
                    TranslationHealthSummary(state.translationHealth)
                    Text(
                        text = when {
                            state.liveError != null -> stringResource(R.string.live_pipeline_error)
                            state.liveConnected -> stringResource(R.string.live_pipeline_connected)
                            else -> stringResource(R.string.live_pipeline_stopped)
                        },
                        color = if (state.liveError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("live-pipeline-status"),
                    )
                    Text(
                        text = when {
                            state.xApiMetadataRefreshing -> stringResource(R.string.x_api_metadata_refreshing)
                            state.xApiMetadataError -> stringResource(R.string.x_api_metadata_error)
                            state.xApiMetadataLastSuccessAt != null -> stringResource(R.string.x_api_metadata_current)
                            else -> stringResource(R.string.x_api_metadata_bundled)
                        },
                        color = if (state.xApiMetadataError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("x-api-metadata-status"),
                    )
                    Button(
                        onClick = onRefreshXApiMetadata,
                        enabled = !state.xApiMetadataRefreshing,
                        modifier = Modifier.fillMaxWidth().testTag("refresh-x-api-metadata"),
                    ) {
                        Text(stringResource(R.string.x_api_metadata_refresh))
                    }
                }
                SettingsDisclosure(
                    title = stringResource(R.string.settings_help),
                    tag = "settings-help",
                ) {
                    SettingSectionTitle(stringResource(R.string.setting_feed_updates))
                    SettingsHelpText(R.string.setting_auto_refresh_desc, "settings-help-refresh")
                    SettingSectionTitle(stringResource(R.string.settings_navigation))
                    SettingsHelpText(R.string.show_main_navigation_desc, "settings-help-navigation")
                    SettingSectionTitle(stringResource(R.string.apk_update_section))
                    SettingsHelpText(R.string.apk_update_description, "settings-help-update")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-close")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun SettingsDialog(
    state: DeckUiState,
    onDarkThemeChange: (Boolean) -> Unit,
    onCompactDensityChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    transferStatus: TransferStatus,
    onDismiss: () -> Unit,
) {
    SettingsDialog(
        state = state,
        onDisplaySettingsChange = { settings ->
            onDarkThemeChange(settings.themeMode != ThemeMode.LIGHT)
            onCompactDensityChange(settings.compactDensity)
        },
        selectedLanguageTag = null,
        onLanguageChange = {},
        selectedTranslationLanguageTag = null,
        onTranslationLanguageChange = {},
        onExport = onExport,
        onImport = onImport,
        transferStatus = transferStatus,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SettingSectionTitle(label: String) {
    Text(
        text = label,
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SettingChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingChoice(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    color: Color? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                color?.let {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(it, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(label)
            }
        },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun SettingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.setting_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.setting_theme_light)
    ThemeMode.DARK -> stringResource(R.string.setting_theme_dark)
}

@Composable
private fun fontSizeLabel(size: AppFontSize): String = when (size) {
    AppFontSize.SMALL -> stringResource(R.string.setting_font_small)
    AppFontSize.DEFAULT -> stringResource(R.string.setting_font_default)
    AppFontSize.LARGE -> stringResource(R.string.setting_font_large)
}

@Composable
private fun accentColorLabel(accent: AccentColor): String = when (accent) {
    AccentColor.BLUE -> stringResource(R.string.setting_accent_blue)
    AccentColor.PURPLE -> stringResource(R.string.setting_accent_purple)
    AccentColor.PINK -> stringResource(R.string.setting_accent_pink)
    AccentColor.ORANGE -> stringResource(R.string.setting_accent_orange)
    AccentColor.GREEN -> stringResource(R.string.setting_accent_green)
    AccentColor.YELLOW -> stringResource(R.string.setting_accent_yellow)
}

private fun accentColorPreview(accent: AccentColor): Color = when (accent) {
    AccentColor.BLUE -> Color(0xFF1D9BF0)
    AccentColor.PURPLE -> Color(0xFF7856FF)
    AccentColor.PINK -> Color(0xFFE0245E)
    AccentColor.ORANGE -> Color(0xFFFF7A00)
    AccentColor.GREEN -> Color(0xFF00BA7C)
    AccentColor.YELLOW -> Color(0xFFFFC107)
}

@Composable
private fun videoQualityLabel(quality: VideoQuality): String = when (quality) {
    VideoQuality.AUTO -> stringResource(R.string.video_quality_auto)
    VideoQuality.LOW -> stringResource(R.string.video_quality_low)
    VideoQuality.MEDIUM -> stringResource(R.string.video_quality_medium)
    VideoQuality.HIGH -> stringResource(R.string.video_quality_high)
}

@Composable
private fun languageLabel(languageTag: String): String = when (languageTag) {
    "ja" -> stringResource(R.string.language_ja)
    "en" -> stringResource(R.string.language_en)
    "zh" -> stringResource(R.string.language_zh)
    "hi" -> stringResource(R.string.language_hi)
    "es" -> stringResource(R.string.language_es)
    "fr" -> stringResource(R.string.language_fr)
    "ar" -> stringResource(R.string.language_ar)
    "pt" -> stringResource(R.string.language_pt)
    "bn" -> stringResource(R.string.language_bn)
    "ru" -> stringResource(R.string.language_ru)
    "ur" -> stringResource(R.string.language_ur)
    else -> stringResource(R.string.language_en)
}

@Composable
private fun TranslationHealthSummary(health: TranslationHealth?) {
    val unavailable = stringResource(R.string.translation_health_unavailable)
    val successRate = health?.recentSuccessRate ?: health?.upstreamSuccessRate
    val successText = when {
        health == null -> unavailable
        successRate != null -> stringResource(R.string.translation_percent, successRate.roundToInt())
        health.requests == 0L -> stringResource(R.string.translation_percent, 0)
        else -> unavailable
    }
    val remaining = health?.rateLimitRemaining
    val limit = health?.rateLimit
    val remainingText = if (remaining != null && limit != null) {
        stringResource(
            R.string.translation_rate_remaining_value,
            remaining,
            limit,
        )
    } else {
        unavailable
    }
    val resetText = health?.rateLimitResetAt?.let {
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(it)
    } ?: unavailable
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("translation-health"),
    ) {
        Text(
            text = stringResource(R.string.translation_health_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(R.string.translation_health_success_rate, successText))
        Text(stringResource(R.string.translation_health_remaining, remainingText))
        Text(stringResource(R.string.translation_health_reset, resetText))
    }
}

@Composable
private fun SettingGroupTitle(label: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingLanguagePicker(
    label: String,
    selectedTag: String,
    tag: String,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = languageLabel(selectedTag)
    SettingSectionTitle(label)
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag("$tag-selector").semantics {
                contentDescription = "$label: $selectedLabel"
            },
        ) {
            Text(selectedLabel, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLocaleController.supportedLanguageTags.forEach { languageTag ->
                DropdownMenuItem(
                    text = { Text(languageLabel(languageTag)) },
                    trailingIcon = {
                        if (selectedTag == languageTag) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(languageTag)
                    },
                    modifier = Modifier.testTag("$tag-$languageTag").semantics {
                        selected = selectedTag == languageTag
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsDisclosure(
    title: String,
    tag: String,
    expandOnError: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expandOnError) {
        if (expandOnError) expanded = true
    }
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp))
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { selected = expanded },
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
        )
    }
    if (expanded) content()
}

@Composable
private fun SettingsHelpText(@androidx.annotation.StringRes resource: Int, tag: String) {
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

