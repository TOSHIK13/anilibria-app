package ru.radiationx.anilibria.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import ru.radiationx.anilibria.R
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.domain.types.EpisodeId

enum class PlayerSubmenuType(val title: String) {
    QUALITY("Качество"),
    SPEED("Скорость"),
    EPISODES("Серии"),
    SETTINGS("Настройки"),
    STATS("Статистика"),
}

@Composable
fun PlayerSubmenuSheet(
    modifier: Modifier = Modifier,
    submenu: PlayerSubmenuType,
    state: PlayerComposeMenuState,
    stats: PlayerStatsState,
    scrollCommand: SubmenuScrollCommand,
    onQualitySelected: (PlayerQuality) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onEpisodeSelected: (EpisodeId) -> Unit,
    onSetSkipsEnabled: (Boolean) -> Unit,
    onSetAutoSkipEnabled: (Boolean) -> Unit,
    onSetAutoplayEnabled: (Boolean) -> Unit,
) {
    Box(
        modifier = modifier
            .width(360.dp)
            .heightIn(max = 380.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xF4171717))
            .border(
                width = 1.dp,
                brush = SolidColor(Color(0x2AFFFFFF)),
                shape = RoundedCornerShape(26.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = submenu.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            when (submenu) {
                PlayerSubmenuType.QUALITY -> {
                    QualitySubmenu(
                        state = state,
                        onQualitySelected = onQualitySelected,
                    )
                }

                PlayerSubmenuType.SPEED -> {
                    SpeedSubmenu(
                        state = state,
                        onSpeedSelected = onSpeedSelected,
                    )
                }

                PlayerSubmenuType.EPISODES -> {
                    EpisodesSubmenu(
                        state = state,
                        scrollCommand = scrollCommand,
                        onEpisodeSelected = onEpisodeSelected,
                    )
                }

                PlayerSubmenuType.SETTINGS -> {
                    SettingsSubmenu(
                        settings = state.settings,
                        onSetSkipsEnabled = onSetSkipsEnabled,
                        onSetAutoSkipEnabled = onSetAutoSkipEnabled,
                        onSetAutoplayEnabled = onSetAutoplayEnabled,
                    )
                }

                PlayerSubmenuType.STATS -> {
                    StatsSubmenu(
                        stats = stats,
                        scrollCommand = scrollCommand,
                    )
                }
            }
        }
    }
}

@Composable
private fun QualitySubmenu(
    state: PlayerComposeMenuState,
    onQualitySelected: (PlayerQuality) -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val selectedIndex = state.qualityOptions.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(state.selectedQuality, state.qualityOptions) {
        initialFocusRequester.requestFocusSafely()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.qualityOptions.forEachIndexed { index, option ->
            SubmenuOptionRow(
                modifier = if (index == selectedIndex) Modifier.focusRequester(initialFocusRequester) else Modifier,
                selected = option.selected,
                iconRes = option.quality.toSubmenuIconRes(),
                title = option.quality.toQualityLabel(),
                onClick = { onQualitySelected(option.quality) },
            )
        }
    }
}

@Composable
private fun SpeedSubmenu(
    state: PlayerComposeMenuState,
    onSpeedSelected: (Float) -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val selectedIndex = state.availableSpeeds.indexOf(state.selectedSpeed).coerceAtLeast(0)
    LaunchedEffect(state.selectedSpeed, state.availableSpeeds) {
        initialFocusRequester.requestFocusSafely()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.availableSpeeds.forEachIndexed { index, speed ->
            SubmenuOptionRow(
                modifier = if (index == selectedIndex) Modifier.focusRequester(initialFocusRequester) else Modifier,
                selected = speed == state.selectedSpeed,
                title = speed.toSpeedLabel(),
                subtitle = if (speed == 1f) "Стандартная" else null,
                onClick = { onSpeedSelected(speed) },
            )
        }
    }
}

@Composable
private fun EpisodesSubmenu(
    state: PlayerComposeMenuState,
    scrollCommand: SubmenuScrollCommand,
    onEpisodeSelected: (EpisodeId) -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val flatEpisodes = state.episodeGroups.flatMap { it.episodes }
    val firstSelectedIndex = flatEpisodes.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(state.selectedEpisodeId, state.episodeGroups) {
        initialFocusRequester.requestFocusSafely()
    }
    LaunchedEffect(scrollCommand.token) {
        if (scrollCommand.token != 0L && scrollCommand.deltaPx != 0f) {
            listState.scrollBy(scrollCommand.deltaPx)
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        state.episodeGroups.forEach { group ->
            item {
                Text(
                    text = group.title,
                    color = Color(0xFF8C8C8C),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp, start = 2.dp)
                )
            }
            itemsIndexed(group.episodes) { index, episode ->
                val globalIndex = flatEpisodes.indexOfFirst { it.episodeId == episode.episodeId }
                val isInitialFocus = if (firstSelectedIndex >= 0) {
                    globalIndex == firstSelectedIndex
                } else {
                    index == 0
                }
                SubmenuOptionRow(
                    modifier = if (isInitialFocus) Modifier.focusRequester(initialFocusRequester) else Modifier,
                    selected = episode.selected,
                    title = episode.title,
                    subtitle = episode.description,
                    onClick = { onEpisodeSelected(episode.episodeId) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSubmenu(
    settings: PlayerComposeSettingsState,
    onSetSkipsEnabled: (Boolean) -> Unit,
    onSetAutoSkipEnabled: (Boolean) -> Unit,
    onSetAutoplayEnabled: (Boolean) -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(settings) {
        initialFocusRequester.requestFocusSafely()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToggleOptionRow(
            modifier = Modifier.focusRequester(initialFocusRequester),
            title = "Пропуски",
            selected = settings.skipsEnabled,
            onClick = { onSetSkipsEnabled(!settings.skipsEnabled) },
        )
        ToggleOptionRow(
            title = "Автопропуск",
            selected = settings.autoSkipEnabled,
            onClick = { onSetAutoSkipEnabled(!settings.autoSkipEnabled) },
        )
        ToggleOptionRow(
            title = "Следующая серия",
            selected = settings.autoplayEnabled,
            onClick = { onSetAutoplayEnabled(!settings.autoplayEnabled) },
        )
    }
}

@Composable
private fun SubmenuOptionRow(
    title: String,
    modifier: Modifier = Modifier,
    selected: Boolean,
    iconRes: Int? = null,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> Color(0xFFFE3635)
        selected -> Color(0xFF2A2A2A)
        else -> Color(0xFF1F1F1F)
    }
    val borderColor = when {
        focused -> Color(0x66FFFFFF)
        selected -> Color(0x33FFFFFF)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(
                width = if (focused || selected) 1.dp else 0.dp,
                brush = SolidColor(borderColor),
                shape = RoundedCornerShape(18.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1AFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = Color(0xFFD6D6D6),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun ToggleOptionRow(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SubmenuOptionRow(
        modifier = modifier,
        title = title,
        subtitle = if (selected) "Включено" else "Выключено",
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun StatsSubmenu(
    stats: PlayerStatsState,
    scrollCommand: SubmenuScrollCommand,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollCommand.token) {
        if (scrollCommand.token != 0L && scrollCommand.deltaPx != 0f) {
            listState.scrollBy(scrollCommand.deltaPx)
        }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { StatsRow("Состояние", stats.playbackStateLabel) }
        item { StatsRow("Битрейт", stats.bitrateLabel) }
        item { StatsRow("Видео", stats.videoSizeLabel) }
        item { StatsRow("FPS", stats.fpsLabel) }
        item { StatsRow("Кодек", stats.codecLabel) }
        item { StatsRow("Буфер", stats.bufferLabel) }
        item { StatsRow("Потеряно кадров", stats.droppedFramesLabel) }
        item { StatsRow("Ребуферов", stats.rebufferCountLabel) }
    }
}

@Composable
private fun StatsRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1F1F1F))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFFBDBDBD),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun PlayerQuality.toSubmenuIconRes(): Int {
    return when (this) {
        PlayerQuality.SD -> R.drawable.ic_quality_sd_base
        PlayerQuality.HD -> R.drawable.ic_quality_hd_base
        PlayerQuality.FULLHD -> R.drawable.ic_quality_full_hd_base
    }
}

private fun PlayerQuality.toQualityLabel(): String {
    return when (this) {
        PlayerQuality.SD -> "SD"
        PlayerQuality.HD -> "HD"
        PlayerQuality.FULLHD -> "FHD"
    }
}

private fun Float.toSpeedLabel(): String {
    val normalized = if (this % 1f == 0f) {
        this.toInt().toString()
    } else {
        this.toString().replace(',', '.')
    }
    return "${normalized}x"
}

private fun FocusRequester.requestFocusSafely() {
    runCatching {
        requestFocus()
    }
}
