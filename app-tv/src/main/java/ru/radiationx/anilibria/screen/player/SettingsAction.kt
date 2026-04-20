package ru.radiationx.anilibria.screen.player

import android.content.Context
import androidx.leanback.widget.Action
import ru.radiationx.anilibria.R
import ru.radiationx.shared.ktx.android.getCompatDrawable

class SettingsAction(context: Context) : Action(R.id.player_action_settings.toLong()) {

    init {
        icon = context.getCompatDrawable(R.drawable.ic_settings_24)
        label1 = "Настройки плеера"
    }
}
