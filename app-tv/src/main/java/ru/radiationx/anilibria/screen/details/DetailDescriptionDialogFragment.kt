package ru.radiationx.anilibria.screen.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import ru.radiationx.anilibria.R

/**
 * Полное описание релиза поверх экрана деталей.
 * UP/DOWN прокручивают текст, BACK закрывает.
 */
class DetailDescriptionDialogFragment : DetailOverlayDialogFragment() {

    companion object {
        const val TAG = "detail_description"
        private const val ARG_TITLE = "title"
        private const val ARG_TEXT = "text"

        fun newInstance(title: String, text: String) = DetailDescriptionDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_TEXT, text)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_detail_description, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val title = args.getString(ARG_TITLE).orEmpty()
        val titleView = view.findViewById<TextView>(R.id.detailDescriptionTitle)
        titleView.text = title
        titleView.isVisible = title.isNotEmpty()

        val textView = view.findViewById<TextView>(R.id.detailDescriptionText)
        TextViewCompat.setLineHeight(textView, dp(26))
        textView.text = args.getString(ARG_TEXT).orEmpty()

        val scroll = view.findViewById<ScrollView>(R.id.detailDescriptionScroll)
        scrollView = scroll
        scroll.requestFocus()
    }
}
