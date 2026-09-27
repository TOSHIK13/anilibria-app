package ru.radiationx.anilibria.screen.details

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
import ru.radiationx.anilibria.R

/**
 * Общая основа полноэкранных оверлеев экрана релиза (описание, оценки):
 * затемнение рисует layout, UP/DOWN прокручивают [scrollView], BACK закрывает.
 */
abstract class DetailOverlayDialogFragment : DialogFragment() {

    protected var scrollView: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AppTheme_DescriptionDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnKeyListener { _, keyCode, event -> handleKey(keyCode, event) }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scrollView = null
    }

    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val scroll = scrollView ?: return false
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_PAGE_DOWN -> 4
            KeyEvent.KEYCODE_PAGE_UP -> -4
            // Влево/вправо некуда — глотаем, чтобы фокус не «терялся».
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> 0
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && direction != 0) {
            // Шаг — три строки текста описания.
            scroll.smoothScrollBy(0, direction * dp(78))
        }
        return true
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
