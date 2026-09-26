package ru.radiationx.anilibria.ui.presenter.cust

import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ListRowView
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.ui.widget.CardDescriptionView

class CustomListRowViewHolder(
    rootView: ListRowView,
    gridView: HorizontalGridView,
    presenter: ListRowPresenter,
    descriptionEnabled: Boolean = true,
) : ListRowPresenter.ViewHolder(rootView, gridView, presenter) {

    /** null — блок описания под рядом отключён (страницы с hero-блоком). */
    private val cardDescriptionView = if (descriptionEnabled) {
        CardDescriptionView(rootView.context, defStyleAttr = R.attr.rowHorizontalDescriptionStyle)
    } else {
        null
    }

    init {
        cardDescriptionView?.also {
            rootView.addView(
                it,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun setDescription(title: CharSequence, subtitle: CharSequence) {
        cardDescriptionView?.setTitle(title)
        cardDescriptionView?.setSubtitle(subtitle)
    }

    fun setExpanded(expanded: Boolean) {
        cardDescriptionView?.isVisible = expanded && isSelected
    }

    fun setSelected(selected: Boolean) {
        cardDescriptionView?.isVisible = selected && isExpanded
    }
}