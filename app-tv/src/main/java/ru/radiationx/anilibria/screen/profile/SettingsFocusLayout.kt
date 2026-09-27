package ru.radiationx.anilibria.screen.profile

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Корень страницы настроек. Направляет фокус между колонкой разделов и строками:
 * - RIGHT из разделов — на запомненную строку раздела ([paneEntry]), LEFT из строк — на выбранный раздел;
 * - UP/DOWN не перескакивают между колонками: у верхнего края фокус уходит на вкладки
 *   (это делает MainPagesFragment, когда внутри страницы кандидатов нет);
 * - вход на страницу (DOWN с вкладок) — на выбранный раздел, возврат из панели — на строку ([restoreEntry]).
 *
 * Работает через [addFocusables]: MainPagesFragment ищет соседа FocusFinder'ом по корню страницы.
 */
class SettingsFocusLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var navContainer: ViewGroup? = null
    var paneContainer: ViewGroup? = null
    var navEntry: (() -> View?)? = null
    var paneEntry: (() -> View?)? = null

    /** Строка, на которую вернуть фокус при возврате на страницу (например, после боковой панели). */
    var restoreEntry: (() -> View?)? = null

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val focused = findFocus()
        val nav = navContainer
        val pane = paneContainer
        if (focused == null || nav == null || pane == null) {
            super.addFocusables(views, direction, focusableMode)
            return
        }
        val inNav = nav.isAncestorOf(focused)
        val inPane = pane.isAncestorOf(focused)
        when {
            inNav && direction == View.FOCUS_RIGHT -> {
                val target = paneEntry?.invoke()
                if (target != null) views.add(target)
            }

            inPane && direction == View.FOCUS_LEFT -> {
                val target = navEntry?.invoke()
                if (target != null) views.add(target)
            }

            inNav && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) ->
                nav.addFocusables(views, direction, focusableMode)

            inPane && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) ->
                pane.addFocusables(views, direction, focusableMode)

            else -> super.addFocusables(views, direction, focusableMode)
        }
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val restore = restoreEntry?.invoke()
        if (restore != null && restore.requestFocus()) return true
        val entry = navEntry?.invoke()
        if (entry != null && entry.requestFocus()) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    private fun ViewGroup.isAncestorOf(child: View): Boolean {
        var parent = child.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}
