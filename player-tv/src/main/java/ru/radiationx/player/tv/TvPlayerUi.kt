package ru.radiationx.player.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs

data class TvPlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val cachedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

data class TvPlayerControlsState(
    val title: String = "",
    val subtitle: String = "",
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

data class TvQualityOption(
    val id: String,
    val title: String,
    val selected: Boolean,
)

data class TvPlayerSettingsState(
    val backBufferSeconds: Int = 0,
    val forwardBufferSeconds: Int = 50,
    val bufferMemoryLimitMb: Int = 128,
)

data class TvPlayerStatsState(
    val playbackStateLabel: String = "Нет данных",
    val bitrateLabel: String = "Нет данных",
    val videoSizeLabel: String = "Нет данных",
    val fpsLabel: String = "Нет данных",
    val codecLabel: String = "Нет данных",
    val bufferLabel: String = "Нет данных",
    val droppedFramesLabel: String = "0",
    val rebufferCountLabel: String = "0",
    val sourceDiagnostics: String = "",
)

data class TvPlayerMenuState(
    val qualityOptions: List<TvQualityOption> = emptyList(),
    val selectedQualityTitle: String = "Авто",
    val availableSpeeds: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f),
    val selectedSpeed: Float = 1f,
    val settings: TvPlayerSettingsState = TvPlayerSettingsState(),
)

enum class TvPlayerSubmenuType(val title: String) {
    QUALITY("Качество"),
    SPEED("Скорость"),
    SETTINGS("Настройки"),
    STATS("Статистика"),
}

@Composable
fun TvPlayerScreen(
    player: Player?,
    controlsState: TvPlayerControlsState,
    snapshot: TvPlaybackSnapshot,
    menuState: TvPlayerMenuState,
    stats: TvPlayerStatsState,
    overlayVisible: Boolean,
    activeSubmenu: TvPlayerSubmenuType?,
    statsOverlayVisible: Boolean,
    playPauseHud: Boolean?,
    seekHud: Long?,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
    onCloseSubmenu: () -> Unit,
    onSetActiveSubmenu: (TvPlayerSubmenuType?) -> Unit,
    onToggleStatsOverlay: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQualitySelected: (String) -> Unit,
    onSpeedSelected: (Float) -> Unit,
) {
    val rootFocusRequester = remember { FocusRequester() }
    val primaryFocusRequester = remember { FocusRequester() }
    val timelineFocusRequester = remember { FocusRequester() }
    val qualityFocusRequester = remember { FocusRequester() }
    var currentZone by remember { mutableStateOf(OverlayZone.ROOT) }

    LaunchedEffect(overlayVisible, activeSubmenu) {
        if (overlayVisible && activeSubmenu == null) {
            delay(50L)
            primaryFocusRequester.requestFocus()
        } else if (!overlayVisible) {
            rootFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                handlePlayerKey(
                    event = event,
                    overlayVisible = overlayVisible,
                    activeSubmenu = activeSubmenu,
                    currentZone = currentZone,
                    onShowOverlay = onShowOverlay,
                    onHideOverlay = onHideOverlay,
                    onCloseSubmenu = onCloseSubmenu,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekBy = onSeekBy,
                    focusTimeline = { timelineFocusRequester.requestFocus() },
                    focusPrimary = { primaryFocusRequester.requestFocus() },
                    focusSecondary = { qualityFocusRequester.requestFocus() },
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onShowOverlay,
            )
    ) {
        if (statsOverlayVisible) {
            TvStatsOverlay(
                stats = stats,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 32.dp)
            )
        }

        PlaybackHudOverlay(
            playPauseHud = playPauseHud,
            seekHud = seekHud,
        )

        if (snapshot.isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Буферизация",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xAA101010))
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (activeSubmenu != null) {
                            onCloseSubmenu()
                        } else {
                            onHideOverlay()
                        }
                    }
            ) {
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically(initialOffsetY = { it / 3 }),
                    exit = slideOutVertically(targetOffsetY = { it / 3 }),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(Color(0xCC121212))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {}
                            .padding(horizontal = 28.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        HeaderRow(controlsState = controlsState)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (it.hasFocus) currentZone = OverlayZone.PRIMARY
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            PlayerActionButton(
                                label = "Назад 10",
                                iconRes = R.drawable.ic_player_previous,
                                compact = false,
                                onClick = { onSeekBy(-10_000L) }
                            )
                            PlayerActionButton(
                                label = if (snapshot.isPlaying) "Пауза" else "Пуск",
                                iconRes = if (snapshot.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                                modifier = Modifier.focusRequester(primaryFocusRequester),
                                compact = false,
                                onClick = onTogglePlayPause,
                            )
                            PlayerActionButton(
                                label = "Вперёд 10",
                                iconRes = R.drawable.ic_player_next,
                                compact = false,
                                onClick = { onSeekBy(10_000L) }
                            )
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                TimelineMeta(snapshot = snapshot)
                            }
                        }

                        TimelineControl(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(timelineFocusRequester)
                                .onFocusChanged {
                                    if (it.isFocused) currentZone = OverlayZone.TIMELINE
                                },
                            snapshot = snapshot,
                            onSeekTo = onSeekTo,
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (it.hasFocus) currentZone = OverlayZone.SECONDARY
                                },
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PlayerActionButton(
                                label = "Качество",
                                iconRes = R.drawable.ic_quality,
                                modifier = Modifier.focusRequester(qualityFocusRequester),
                                selected = activeSubmenu == TvPlayerSubmenuType.QUALITY,
                                onClick = { onSetActiveSubmenu(TvPlayerSubmenuType.QUALITY) }
                            )
                            PlayerActionButton(
                                label = "Скорость",
                                iconRes = R.drawable.ic_speed,
                                selected = activeSubmenu == TvPlayerSubmenuType.SPEED,
                                onClick = { onSetActiveSubmenu(TvPlayerSubmenuType.SPEED) }
                            )
                            PlayerActionButton(
                                label = "Настройки",
                                iconRes = R.drawable.ic_settings,
                                selected = activeSubmenu == TvPlayerSubmenuType.SETTINGS,
                                onClick = { onSetActiveSubmenu(TvPlayerSubmenuType.SETTINGS) }
                            )
                            PlayerActionButton(
                                label = "Статистика",
                                iconRes = R.drawable.ic_stats,
                                selected = statsOverlayVisible || activeSubmenu == TvPlayerSubmenuType.STATS,
                                onClick = onToggleStatsOverlay,
                            )
                        }
                    }
                }

                activeSubmenu?.let { submenu ->
                    TvPlayerSubmenuSheet(
                        submenu = submenu,
                        state = menuState,
                        stats = stats,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 92.dp, end = 28.dp),
                        onQualitySelected = {
                            onQualitySelected(it)
                            onCloseSubmenu()
                        },
                        onSpeedSelected = {
                            onSpeedSelected(it)
                            onCloseSubmenu()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(controlsState: TvPlayerControlsState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = controlsState.title.ifBlank { "Внешнее видео" },
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = controlsState.subtitle,
                fontSize = 15.sp,
                color = Color(0xFFBDBDBD),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = rememberCurrentTimeText(),
            color = Color(0xFFE0E0E0),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp)
        )
    }
}

@Composable
private fun TvPlayerSubmenuSheet(
    modifier: Modifier = Modifier,
    submenu: TvPlayerSubmenuType,
    state: TvPlayerMenuState,
    stats: TvPlayerStatsState,
    onQualitySelected: (String) -> Unit,
    onSpeedSelected: (Float) -> Unit,
) {
    val entries = remember(submenu, state, stats) {
        buildSubmenuEntries(submenu, state, stats, onQualitySelected, onSpeedSelected)
    }
    val listState = rememberLazyListState()
    val panelFocusRequester = remember { FocusRequester() }
    var selectedIndex by remember(submenu) { mutableIntStateOf(entries.firstSelectableIndex()) }
    var panelFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(submenu) {
        panelFocusRequester.requestFocus()
        selectedIndex = entries.firstSelectableIndex()
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex in entries.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val panelWidth = entries.calculatePanelWidth(submenu.title, textMeasurer, density, maxWidth)
        val panelHeight = entries.calculatePanelHeight(maxHeight)
        Box(
            modifier = Modifier
                .width(panelWidth)
                .height(panelHeight)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF4171717))
                .border(1.dp, SolidColor(Color(0x2AFFFFFF)), RoundedCornerShape(26.dp))
                .focusRequester(panelFocusRequester)
                .onFocusChanged { panelFocused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            selectedIndex = entries.findPreviousSelectable(selectedIndex)
                            true
                        }
                        Key.DirectionDown -> {
                            selectedIndex = entries.findNextSelectable(selectedIndex)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            (entries.getOrNull(selectedIndex) as? PlayerSubmenuEntry.Row)?.onClick?.invoke()
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = submenu.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height((panelHeight - 56.dp).coerceAtLeast(0.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(entries) { index, entry ->
                        when (entry) {
                            is PlayerSubmenuEntry.GroupHeader -> Text(
                                text = entry.title,
                                color = Color(0xFF8C8C8C),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp, start = 2.dp)
                            )
                            is PlayerSubmenuEntry.Row -> SubmenuOptionRow(
                                title = entry.title,
                                subtitle = entry.subtitle,
                                selected = entry.selected,
                                highlighted = panelFocused && index == selectedIndex,
                                showSelectedIndicator = entry.showSelectedIndicator,
                                onClick = {
                                    selectedIndex = index
                                    entry.onClick?.invoke()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface PlayerSubmenuEntry {
    data class GroupHeader(val title: String) : PlayerSubmenuEntry
    data class Row(
        val title: String,
        val subtitle: String? = null,
        val selected: Boolean = false,
        val showSelectedIndicator: Boolean = true,
        val onClick: (() -> Unit)? = null,
    ) : PlayerSubmenuEntry
}

private fun buildSubmenuEntries(
    submenu: TvPlayerSubmenuType,
    state: TvPlayerMenuState,
    stats: TvPlayerStatsState,
    onQualitySelected: (String) -> Unit,
    onSpeedSelected: (Float) -> Unit,
): List<PlayerSubmenuEntry> {
    return when (submenu) {
        TvPlayerSubmenuType.QUALITY -> {
            val options = state.qualityOptions.ifEmpty {
                listOf(TvQualityOption("auto", "Авто", true))
            }
            options.map {
                PlayerSubmenuEntry.Row(
                    title = it.title,
                    selected = it.selected,
                    onClick = { onQualitySelected(it.id) },
                )
            }
        }
        TvPlayerSubmenuType.SPEED -> state.availableSpeeds.map { speed ->
            PlayerSubmenuEntry.Row(
                title = speed.toSpeedLabel(),
                subtitle = if (speed == 1f) "Стандартная" else null,
                selected = speed == state.selectedSpeed,
                onClick = { onSpeedSelected(speed) },
            )
        }
        TvPlayerSubmenuType.SETTINGS -> listOf(
            PlayerSubmenuEntry.Row("Буфер назад", "${state.settings.backBufferSeconds} с", false, false),
            PlayerSubmenuEntry.Row("Буфер вперёд", "${state.settings.forwardBufferSeconds} с", false, false),
            PlayerSubmenuEntry.Row("Лимит буфера", "${state.settings.bufferMemoryLimitMb} MB", false, false),
        )
        TvPlayerSubmenuType.STATS -> listOf(
            PlayerSubmenuEntry.Row("Состояние", stats.playbackStateLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Битрейт", stats.bitrateLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Видео", stats.videoSizeLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("FPS", stats.fpsLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Кодек", stats.codecLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.Row("Буфер", stats.bufferLabel, showSelectedIndicator = false),
            PlayerSubmenuEntry.GroupHeader("Источник"),
            PlayerSubmenuEntry.Row(stats.sourceDiagnostics.ifBlank { "Нет данных" }, showSelectedIndicator = false),
        )
    }
}

@Composable
private fun SubmenuOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    highlighted: Boolean,
    showSelectedIndicator: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        highlighted -> Color(0xFFFE3635)
        selected -> Color(0xFF2A2A2A)
        else -> Color(0xFF1F1F1F)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(
                width = if (highlighted || selected) 1.dp else 0.dp,
                brush = SolidColor(if (highlighted) Color(0x66FFFFFF) else Color(0x33FFFFFF)),
                shape = RoundedCornerShape(18.dp),
            )
            .onPreviewKeyEvent { event ->
                if (isConfirmKey(event)) {
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
        if (showSelectedIndicator) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .border(1.dp, SolidColor(Color.White), CircleShape)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (showSelectedIndicator) 2 else 8,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = Color(0xFFBDBDBD),
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    label: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    compact: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        !enabled -> Color(0x331A1A1A)
        focused -> Color(0xFFFE3635)
        selected -> Color(0xFF303030)
        else -> Color(0xFF1D1D1D)
    }
    Row(
        modifier = modifier
            .height(if (compact) 48.dp else 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(
                width = if (focused || selected) 1.dp else 0.dp,
                brush = SolidColor(Color(0x44FFFFFF)),
                shape = RoundedCornerShape(16.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .onPreviewKeyEvent { event ->
                if (enabled && isConfirmKey(event)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 14.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = if (enabled) Color.White else Color(0x77FFFFFF),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = if (enabled) Color.White else Color(0x77FFFFFF),
            fontSize = if (compact) 14.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimelineControl(
    modifier: Modifier,
    snapshot: TvPlaybackSnapshot,
    onSeekTo: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val duration = snapshot.durationMs.takeIf { it > 0 } ?: 1L
    val playedFraction = (snapshot.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (snapshot.bufferedPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val cachedFraction = (snapshot.cachedPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .height(16.dp)
            .clickable {
                onSeekTo(snapshot.positionMs)
            }
    ) {
        val totalWidth = maxWidth
        val trackHeight = if (focused) 7.dp else 5.dp
        TimelineFill(totalWidth, trackHeight, if (focused) Color(0xFF2B2B2B) else Color(0xFF1F1F1F))
        TimelineFill(totalWidth * cachedFraction, trackHeight, Color(0xFF2F3D47))
        TimelineFill(totalWidth * bufferedFraction, trackHeight, Color(0xFF4B4B4B))
        TimelineFill(totalWidth * playedFraction, trackHeight, Color(0xFFFE3635))
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (totalWidth * playedFraction) - 6.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(if (focused) Color.White else Color(0xFFE0E0E0))
        )
    }
}

@Composable
private fun BoxScope.TimelineFill(width: Dp, height: Dp, color: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
    )
}

@Composable
private fun TimelineMeta(snapshot: TvPlaybackSnapshot) {
    Text(
        text = "${formatDuration(snapshot.positionMs)} / ${formatDuration(snapshot.durationMs)}",
        fontSize = 14.sp,
        color = Color(0xFFCACACA),
    )
}

@Composable
private fun PlaybackHudOverlay(
    playPauseHud: Boolean?,
    seekHud: Long?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = playPauseHud != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            HudBadge(
                iconRes = if (playPauseHud == true) R.drawable.ic_player_play else R.drawable.ic_player_pause,
                iconOnly = true,
                contentDescription = if (playPauseHud == true) "Пуск" else "Пауза",
            )
        }
        AnimatedVisibility(
            visible = seekHud != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(if ((seekHud ?: 0L) < 0) Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            seekHud?.let {
                Box(modifier = Modifier.padding(horizontal = 32.dp)) {
                    HudBadge(
                        title = formatDelta(it),
                        subtitle = "перемотка",
                    )
                }
            }
        }
    }
}

@Composable
private fun HudBadge(
    title: String = "",
    subtitle: String? = null,
    iconRes: Int? = null,
    contentDescription: String = title,
    iconOnly: Boolean = false,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xD9111111))
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        iconRes?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(if (iconOnly) 34.dp else 24.dp),
            )
        }
        if (!iconOnly && title.isNotBlank()) {
            Text(text = title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        subtitle?.let {
            Text(text = it, fontSize = 18.sp, color = Color(0xFFD0D0D0))
        }
    }
}

@Composable
private fun TvStatsOverlay(
    stats: TvPlayerStatsState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(340.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xC8121212))
            .border(1.dp, SolidColor(Color(0x26FFFFFF)), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Статистика", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PlayerStatsRow("Состояние", stats.playbackStateLabel)
        PlayerStatsRow("Битрейт", stats.bitrateLabel)
        PlayerStatsRow("Видео", stats.videoSizeLabel)
        PlayerStatsRow("FPS", stats.fpsLabel)
        PlayerStatsRow("Кодек", stats.codecLabel)
        PlayerStatsRow("Буфер", stats.bufferLabel)
        PlayerStatsRow("Потеряно кадров", stats.droppedFramesLabel)
        PlayerStatsRow("Ребуферов", stats.rebufferCountLabel)
    }
}

@Composable
private fun PlayerStatsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color(0xFF9E9E9E), fontSize = 13.sp, maxLines = 1)
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberCurrentTimeText(): String {
    var currentTimeText by remember { mutableStateOf(formatCurrentClockTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeText = formatCurrentClockTime()
            delay(30_000L)
        }
    }
    return currentTimeText
}

private enum class OverlayZone {
    ROOT,
    PRIMARY,
    TIMELINE,
    SECONDARY,
}

private fun handlePlayerKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    overlayVisible: Boolean,
    activeSubmenu: TvPlayerSubmenuType?,
    currentZone: OverlayZone,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
    onCloseSubmenu: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    focusTimeline: () -> Unit,
    focusPrimary: () -> Unit,
    focusSecondary: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (activeSubmenu != null) {
        if (event.key == Key.Back) {
            onCloseSubmenu()
            return true
        }
        return false
    }
    if (!overlayVisible) {
        return when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                onTogglePlayPause()
                true
            }
            Key.DirectionLeft, Key.MediaRewind -> {
                onSeekBy(-10_000L)
                true
            }
            Key.DirectionRight, Key.MediaFastForward -> {
                onSeekBy(10_000L)
                true
            }
            Key.DirectionDown, Key.DirectionUp -> {
                onShowOverlay()
                true
            }
            else -> false
        }
    }
    return when (event.key) {
        Key.Back -> {
            onHideOverlay()
            true
        }
        Key.DirectionUp -> {
            when (currentZone) {
                OverlayZone.SECONDARY -> focusTimeline()
                OverlayZone.TIMELINE -> focusPrimary()
                else -> Unit
            }
            true
        }
        Key.DirectionDown -> {
            when (currentZone) {
                OverlayZone.PRIMARY -> focusTimeline()
                OverlayZone.TIMELINE -> focusSecondary()
                else -> Unit
            }
            true
        }
        Key.DirectionLeft -> {
            if (currentZone == OverlayZone.TIMELINE) {
                onSeekBy(-10_000L)
                true
            } else {
                false
            }
        }
        Key.DirectionRight -> {
            if (currentZone == OverlayZone.TIMELINE) {
                onSeekBy(10_000L)
                true
            } else {
                false
            }
        }
        Key.MediaPlayPause -> {
            onTogglePlayPause()
            true
        }
        else -> false
    }
}

private fun isConfirmKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    return event.type == KeyEventType.KeyDown &&
            (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
}

private fun List<PlayerSubmenuEntry>.firstSelectableIndex(): Int {
    return indexOfFirst { it is PlayerSubmenuEntry.Row }.takeIf { it >= 0 } ?: 0
}

private fun List<PlayerSubmenuEntry>.findPreviousSelectable(currentIndex: Int): Int {
    for (index in (currentIndex - 1) downTo 0) {
        if (this[index] is PlayerSubmenuEntry.Row) return index
    }
    return currentIndex
}

private fun List<PlayerSubmenuEntry>.findNextSelectable(currentIndex: Int): Int {
    for (index in (currentIndex + 1) until size) {
        if (this[index] is PlayerSubmenuEntry.Row) return index
    }
    return currentIndex
}

private fun List<PlayerSubmenuEntry>.calculatePanelWidth(
    headerTitle: String,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    maxAvailableWidth: Dp,
): Dp {
    val maxTextWidthPx = maxOf(
        textMeasurer.measure(headerTitle).size.width,
        maxOfOrNull { entry ->
            when (entry) {
                is PlayerSubmenuEntry.GroupHeader -> textMeasurer.measure(entry.title).size.width
                is PlayerSubmenuEntry.Row -> textMeasurer.measure(entry.title).size.width
            }
        } ?: 0
    )
    val width = with(density) { maxTextWidthPx.toDp() + 118.dp }
    return width.coerceIn(300.dp, maxAvailableWidth.coerceAtMost(620.dp))
}

private fun List<PlayerSubmenuEntry>.calculatePanelHeight(maxAvailableHeight: Dp): Dp {
    val rowsHeight = sumOf {
        when (it) {
            is PlayerSubmenuEntry.GroupHeader -> 28
            is PlayerSubmenuEntry.Row -> 64
        }
    }.dp
    return (rowsHeight + 64.dp).coerceAtMost(maxAvailableHeight - 32.dp)
}

private fun Float.toSpeedLabel(): String {
    return if (this == 1f) "1x" else "${this}x"
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatDelta(durationMs: Long): String {
    val seconds = abs(durationMs) / 1000
    return "${seconds}с"
}

private fun formatCurrentClockTime(): String {
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
}
