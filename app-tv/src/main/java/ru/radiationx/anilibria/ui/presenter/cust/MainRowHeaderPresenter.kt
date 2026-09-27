package ru.radiationx.anilibria.ui.presenter.cust

import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
import ru.radiationx.anilibria.R
import ru.radiationx.shared.ktx.android.getCompatColor

/**
 * Заголовок ряда на страницах с hero-блоком: 16sp medium, белый 92%, высота по тексту.
 * [dimUnselected] = false — без затемнения заголовков невыбранных рядов (экран деталей).
 */
class MainRowHeaderPresenter(
    private val dimUnselected: Boolean = true,
) : RowHeaderPresenter() {

    override fun onSelectLevelChanged(holder: RowHeaderPresenter.ViewHolder) {
        if (dimUnselected) {
            super.onSelectLevelChanged(holder)
        } else {
            holder.view.alpha = 1f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val holder = super.onCreateViewHolder(parent)
        val context = parent.context
        holder.view.findViewById<TextView>(androidx.leanback.R.id.row_header)?.apply {
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                context.resources.getDimension(R.dimen.main_row_header_text_size)
            )
            setTextColor(context.getCompatColor(R.color.main_row_header_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            // Без запаса Leanback (24dp) под 16sp: заголовок ближе к карточкам, как в макете.
            minHeight = 0
            minimumHeight = 0
        }
        // Остаток до макета (заголовок на 224dp при карточках на 258dp).
        holder.view.translationY = context.resources.getDimension(R.dimen.main_row_header_shift)
        return holder
    }
}
