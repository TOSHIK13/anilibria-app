package ru.radiationx.anilibria.screen.player

import android.view.MotionEvent

interface PlayerMotionHandler {
    fun handleTouchpadEvent(event: MotionEvent): Boolean
    fun shouldBlockIdleDim(): Boolean
}
