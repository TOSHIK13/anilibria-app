package ru.radiationx.anilibria.screen.profile

import android.os.Bundle
import android.view.View
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.quill.inject

/** Настройки плеера, значение которых выбирается из списка в боковой панели. */
enum class SettingsChoice {
    SPEED,
    QUALITY,
    MEMORY_LIMIT,
    DISK_CACHE_SIZE,
}

/**
 * Выбор значения из списка (радио-отметки в общем тёмном стиле боковых меню).
 * Пишет прямо в [PreferencesHolder] — плеер и его меню видят изменение сразу.
 */
class SettingsChoiceGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val ARG_CHOICE = "settings_choice"
        private const val CHECK_SET_ID = 1

        private val MEMORY_LIMITS_MB = listOf(8, 16, 24, 32, 48, 64)
        private val DISK_CACHE_SIZES_MB = listOf(256, 512, 1024, 2048, 3072, 4096)

        fun newInstance(choice: SettingsChoice) = SettingsChoiceGuidedFragment().apply {
            arguments = Bundle().apply { putString(ARG_CHOICE, choice.name) }
        }
    }

    private val preferencesHolder by inject<PreferencesHolder>()
    private val guidedRouter by inject<GuidedRouter>()

    private val choice by lazy {
        SettingsChoice.valueOf(requireArguments().getString(ARG_CHOICE) ?: SettingsChoice.SPEED.name)
    }

    private val options: List<Option> by lazy { buildOptions() }

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = when (choice) {
        SettingsChoice.SPEED -> Guidance(
            "Скорость по умолчанию",
            "С какой скоростью начинать воспроизведение. Меняется и из меню плеера.",
            null,
            null
        )

        SettingsChoice.QUALITY -> Guidance(
            "Качество по умолчанию",
            "Если серии нет в выбранном качестве, включится ближайшее доступное. Меняется и из меню плеера.",
            null,
            null
        )

        SettingsChoice.MEMORY_LIMIT -> Guidance(
            "Лимит буфера в памяти",
            "Сколько памяти плеер может занять под загруженное видео. На слабых приставках лучше меньше.",
            null,
            null
        )

        SettingsChoice.DISK_CACHE_SIZE -> Guidance(
            "Размер кэша на диске",
            "Сколько места отдать под кэш видео. Старые данные удаляются автоматически.",
            null,
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        options.forEachIndexed { index, option ->
            actions += GuidedAction.Builder(requireContext())
                .id(index.toLong())
                .title(option.title)
                .checkSetId(CHECK_SET_ID)
                .checked(option.selected)
                .build()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val selected = options.indexOfFirst { it.selected }
        if (selected >= 0) {
            selectedActionPosition = selected
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val option = options.getOrNull(action.id.toInt()) ?: return
        option.apply()
        guidedRouter.close()
    }

    private fun buildOptions(): List<Option> = when (choice) {
        SettingsChoice.SPEED -> {
            val current = preferencesHolder.playSpeed.value
            preferencesHolder.availableSpeeds.value.map { speed ->
                val title = SettingsFormat.speed(speed)
                Option(
                    title = if (speed == 1f) "$title (обычная)" else title,
                    selected = speed == current,
                ) { preferencesHolder.playSpeed.value = speed }
            }
        }

        SettingsChoice.QUALITY -> {
            val current = preferencesHolder.playerQuality.value
            PlayerQuality.values().sortedByDescending { it.ordinal }.map { quality ->
                Option(
                    title = SettingsFormat.quality(quality),
                    selected = quality == current,
                ) { preferencesHolder.playerQuality.value = quality }
            }
        }

        SettingsChoice.MEMORY_LIMIT -> megabyteOptions(
            MEMORY_LIMITS_MB,
            preferencesHolder.playerBufferMemoryLimitMb.value,
        ) { preferencesHolder.playerBufferMemoryLimitMb.value = it }

        SettingsChoice.DISK_CACHE_SIZE -> megabyteOptions(
            DISK_CACHE_SIZES_MB,
            preferencesHolder.playerDiskCacheSizeMb.value,
        ) { preferencesHolder.playerDiskCacheSizeMb.value = it }
    }

    private fun megabyteOptions(values: List<Int>, current: Int, set: (Int) -> Unit): List<Option> {
        // Значение, которого нет в списке (задано раньше из меню плеера), тоже показываем.
        return (values + current).distinct().sorted().map { value ->
            Option(title = SettingsFormat.megabytes(value), selected = value == current) { set(value) }
        }
    }

    private class Option(
        val title: String,
        val selected: Boolean,
        val apply: () -> Unit,
    )
}
