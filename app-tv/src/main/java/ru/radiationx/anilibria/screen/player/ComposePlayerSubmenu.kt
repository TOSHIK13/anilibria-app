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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
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
    val entries = remember(submenu, state, stats) {
        buildSubmenuEntries(
            submenu = submenu,
            state = state,
            stats = stats,
            onQualitySelected = onQualitySelected,
            onSpeedSelected = onSpeedSelected,
            onEpisodeSelected = onEpisodeSelected,
            onSetSkipsEnabled = onSetSkipsEnabled,
            onSetAutoSkipEnabled = onSetAutoSkipEnabled,
            onSetAutoplayEnabled = onSetAutoplayEnabled,
        )
    }
    val listState = rememberLazyListState()
    val panelFocusRequester = remember { FocusRequester() }
    var selectedIndex by remember(submenu) { mutableIntStateOf(entries.firstSelectableIndex()) }
    var panelFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(submenu) {
        panelFocusRequester.requestFocusSafely()
        selectedIndex = entries.firstSelectableIndex()
        if (selectedIndex in entries.indices) {
            listState.scrollToItem(selectedIndex)
        }
    }

    LaunchedEffect(entries) {
        if (entries.isEmpty()) {
            selectedIndex = 0
            return@LaunchedEffect
        }
        selectedIndex = when {
            selectedIndex !in entries.indices -> entries.firstSelectableIndex()
            entries[selectedIndex] is PlayerSubmenuEntry.Row -> selectedIndex
            else -> entries.findNextSelectable(selectedIndex).takeIf { it != selectedIndex }
                ?: entries.findPreviousSelectable(selectedIndex)
        }
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex in entries.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    LaunchedEffect(scrollCommand.token) {
        if (scrollCommand.token != 0L && scrollCommand.deltaPx != 0f) {
            listState.scrollBy(scrollCommand.deltaPx)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val panelWidth = entries.calculatePanelWidth(
            headerTitle = submenu.title,
            textMeasurer = textMeasurer,
            density = density,
            maxAvailableWidth = maxWidth,
        )
        val panelHeight = entries.calculatePanelHeight(maxAvailableHeight = maxHeight)
        val listHeight = (panelHeight - PANEL_CHROME_HEIGHT).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
            .width(panelWidth)
            .height(panelHeight)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xF4171717))
            .border(
                width = 1.dp,
                brush = SolidColor(Color(0x2AFFFFFF)),
                shape = RoundedCornerShape(26.dp),
            )
            .focusRequester(panelFocusRequester)
            .onFocusChanged { panelFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionUp -> {
                        selectedIndex = entries.findPreviousSelectable(selectedIndex)
                        true
                    }

                    Key.DirectionDown -> {
                        selectedIndex = entries.findNextSelectable(selectedIndex)
                        true
                    }

                    Key.DirectionLeft,
                    Key.DirectionRight -> true

                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter -> {
                        (entries.getOrNull(selectedIndex) as? PlayerSubmenuEntry.Row)?.onClick?.invoke()
                        true
                    }

                    else -> false
                }
            }
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(listHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(entries) { index, entry ->
                        when (entry) {
                            is PlayerSubmenuEntry.GroupHeader -> {
                                Text(
                                    text = entry.title,
                                    color = Color(0xFF8C8C8C),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp, start = 2.dp)
                                )
                            }

                            is PlayerSubmenuEntry.Row -> {
                                SubmenuOptionRow(
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    iconRes = entry.iconRes,
                                    selected = entry.selected,
                                    wrapTitle = entry.wrapTitle,
                                    highlighted = panelFocused && index == selectedIndex,
                                    showSelectedIndicator = entry.showSelectedIndicator,
                                    onClick = {
                                        selectedIndex = index
                                        entry.onClick?.invoke()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface PlayerSubmenuEntry {
    val selectedOrDefault: Boolean

    data class GroupHeader(
        val title: String,
    ) : PlayerSubmenuEntry {
        override val selectedOrDefault: Boolean = false
    }

    data class Row(
        val title: String,
        val subtitle: String? = null,
        val iconRes: Int? = null,
        val selected: Boolean = false,
        val wrapTitle: Boolean = false,
        val showSelectedIndicator: Boolean = true,
        val onClick: (() -> Unit)? = null,
    ) : PlayerSubmenuEntry {
        override val selectedOrDefault: Boolean = selected
    }
}

private fun buildSubmenuEntries(
    submenu: PlayerSubmenuType,
    state: PlayerComposeMenuState,
    stats: PlayerStatsState,
    onQualitySelected: (PlayerQuality) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onEpisodeSelected: (EpisodeId) -> Unit,
    onSetSkipsEnabled: (Boolean) -> Unit,
    onSetAutoSkipEnabled: (Boolean) -> Unit,
    onSetAutoplayEnabled: (Boolean) -> Unit,
): List<PlayerSubmenuEntry> {
    return when (submenu) {
        PlayerSubmenuType.QUALITY -> state.qualityOptions.map { option ->
            PlayerSubmenuEntry.Row(
                title = option.quality.toQualityLabel(),
                iconRes = option.quality.toSubmenuIconRes(),
                selected = option.selected,
                onClick = { onQualitySelected(option.quality) },
            )
        }

        PlayerSubmenuType.SPEED -> state.availableSpeeds.map { speed ->
            PlayerSubmenuEntry.Row(
                title = speed.toSpeedLabel(),
                subtitle = if (speed == 1f) "Стандартная" else null,
                selected = speed == state.selectedSpeed,
                onClick = { onSpeedSelected(speed) },
            )
        }

        PlayerSubmenuType.EPISODES -> buildList {
            state.episodeGroups.forEach { group ->
                add(PlayerSubmenuEntry.GroupHeader(group.title))
                group.episodes.forEach { episode ->
                    add(
                        PlayerSubmenuEntry.Row(
                            title = episode.title,
                            subtitle = episode.description,
                            selected = episode.selected,
                            wrapTitle = true,
                            onClick = { onEpisodeSelected(episode.episodeId) },
                        )
                    )
                }
            }
        }

        PlayerSubmenuType.SETTINGS -> listOf(
            PlayerSubmenuEntry.Row(
                title = "Пропуски",
                subtitle = if (state.settings.skipsEnabled) "Включено" else "Выключено",
                selected = state.settings.skipsEnabled,
                onClick = { onSetSkipsEnabled(!state.settings.skipsEnabled) },
            ),
            PlayerSubmenuEntry.Row(
                title = "Автопропуск",
                subtitle = if (state.settings.autoSkipEnabled) "Включено" else "Выключено",
                selected = state.settings.autoSkipEnabled,
                onClick = { onSetAutoSkipEnabled(!state.settings.autoSkipEnabled) },
            ),
            PlayerSubmenuEntry.Row(
                title = "Следующая серия",
                subtitle = if (state.settings.autoplayEnabled) "Включено" else "Выключено",
                selected = state.settings.autoplayEnabled,
                onClick = { onSetAutoplayEnabled(!state.settings.autoplayEnabled) },
            ),
        )

        PlayerSubmenuType.STATS -> listOf(
            PlayerSubmenuEntry.Row("Состояние", stats.playbackStateLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Битрейт", stats.bitrateLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Видео", stats.videoSizeLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("FPS", stats.fpsLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Кодек", stats.codecLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Буфер", stats.bufferLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Потеряно кадров", stats.droppedFramesLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Ребуферов", stats.rebufferCountLabel, showSelectedIndicator = false),
        )
    }
}

@Composable
private fun SubmenuOptionRow(
    title: String,
    subtitle: String? = null,
    iconRes: Int? = null,
    selected: Boolean,
    wrapTitle: Boolean,
    highlighted: Boolean,
    showSelectedIndicator: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        highlighted -> Color(0xFFFE3635)
        selected -> Color(0xFF2A2A2A)
        else -> Color(0xFF1F1F1F)
    }
    val borderColor = when {
        highlighted -> Color(0x66FFFFFF)
        selected -> Color(0x33FFFFFF)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(
                width = if (highlighted || selected) 1.dp else 0.dp,
                brush = SolidColor(borderColor),
                shape = RoundedCornerShape(18.dp),
            )
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
                maxLines = if (wrapTitle) 3 else 1,
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
        if (showSelectedIndicator && selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

private fun List<PlayerSubmenuEntry>.findPreviousSelectable(currentIndex: Int): Int {
    for (index in (currentIndex - 1) downTo 0) {
        if (this[index] is PlayerSubmenuEntry.Row) {
            return index
        }
    }
    return currentIndex
}

private fun List<PlayerSubmenuEntry>.findNextSelectable(currentIndex: Int): Int {
    for (index in (currentIndex + 1) until size) {
        if (this[index] is PlayerSubmenuEntry.Row) {
            return index
        }
    }
    return currentIndex
}

private fun List<PlayerSubmenuEntry>.firstSelectableIndex(): Int {
    return indexOfFirst { it is PlayerSubmenuEntry.Row }
        .takeIf { it >= 0 }
        ?: 0
}

private fun List<PlayerSubmenuEntry>.calculatePanelWidth(
    headerTitle: String,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    maxAvailableWidth: Dp,
): Dp {
    val headerWidth = density.run {
        textMeasurer.measure(
            text = headerTitle,
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        ).size.width.toDp()
    }
    val contentWidth = maxOfOrNull { entry ->
        when (entry) {
            is PlayerSubmenuEntry.GroupHeader -> {
                density.run {
                    textMeasurer.measure(
                        text = entry.title,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    ).size.width.toDp()
                } + 12.dp
            }

            is PlayerSubmenuEntry.Row -> {
                val titleWidth = density.run {
                    textMeasurer.measure(
                        text = entry.title,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    ).size.width.toDp()
                }
                val subtitleWidth = entry.subtitle?.let { subtitle ->
                    density.run {
                        textMeasurer.measure(
                            text = subtitle,
                            style = TextStyle(fontSize = 12.sp),
                        ).size.width.toDp()
                    }
                } ?: 0.dp
                val leadingWidth = if (entry.iconRes != null) 48.dp else 0.dp
                val trailingWidth = if (entry.showSelectedIndicator) 18.dp else 0.dp
                28.dp + leadingWidth + maxOf(titleWidth, subtitleWidth) + trailingWidth
            }
        }
    } ?: 0.dp
    val preferredWidth = maxOf(headerWidth, contentWidth) + 32.dp
    val boundedMaxWidth = maxAvailableWidth.coerceAtMost(520.dp)
    return preferredWidth.coerceIn(172.dp, boundedMaxWidth)
}

private fun List<PlayerSubmenuEntry>.calculatePanelHeight(
    maxAvailableHeight: Dp,
): Dp {
    val rowsHeight = fold(0.dp) { acc, entry ->
        acc + when (entry) {
            is PlayerSubmenuEntry.GroupHeader -> 20.dp
            is PlayerSubmenuEntry.Row -> when {
                entry.wrapTitle && entry.subtitle != null -> 88.dp
                entry.wrapTitle -> 76.dp
                entry.subtitle != null -> 64.dp
                else -> 56.dp
            }
        }
    }
    val spacingHeight = if (isEmpty()) 0.dp else ((size - 1) * 8).dp
    val preferredHeight = PANEL_CHROME_HEIGHT + rowsHeight + spacingHeight
    val boundedMaxHeight = maxAvailableHeight.coerceAtMost(430.dp)
    return preferredHeight.coerceIn(132.dp, boundedMaxHeight)
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

private val PANEL_CHROME_HEIGHT = 72.dp
