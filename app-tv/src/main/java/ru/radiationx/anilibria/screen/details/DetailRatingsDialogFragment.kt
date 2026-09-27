package ru.radiationx.anilibria.screen.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.DetailRatingScore
import ru.radiationx.anilibria.common.DetailRatingStat
import ru.radiationx.anilibria.common.DetailRatings

/**
 * «★ Оценки»: рейтинги Shikimori / MyAnimeList (/ AniLiberty) и счётчики коллекций
 * пользователей. BACK закрывает; если не влезает — UP/DOWN прокручивают.
 */
class DetailRatingsDialogFragment : DetailOverlayDialogFragment() {

    companion object {
        const val TAG = "detail_ratings"
        private const val SCORE_COLUMNS = 3
        private const val STATS_COLUMNS = 3

        private const val ARG_TITLE = "title"
        private const val ARG_SCORE_SOURCES = "score_sources"
        private const val ARG_SCORE_VALUES = "score_values"
        private const val ARG_SCORE_VOTES = "score_votes"
        private const val ARG_SCORE_CAPTIONS = "score_captions"
        private const val ARG_STAT_VALUES = "stat_values"
        private const val ARG_STAT_LABELS = "stat_labels"

        fun newInstance(title: String, ratings: DetailRatings) = DetailRatingsDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putStringArrayList(ARG_SCORE_SOURCES, ArrayList(ratings.scores.map { it.source }))
                putStringArrayList(ARG_SCORE_VALUES, ArrayList(ratings.scores.map { it.value }))
                putStringArrayList(ARG_SCORE_VOTES, ArrayList(ratings.scores.map { it.votes }))
                putStringArrayList(ARG_SCORE_CAPTIONS, ArrayList(ratings.scores.map { it.caption }))
                putStringArrayList(ARG_STAT_VALUES, ArrayList(ratings.stats.map { it.value }))
                putStringArrayList(ARG_STAT_LABELS, ArrayList(ratings.stats.map { it.label }))
            }
        }

        private fun Bundle.readRatings(): DetailRatings {
            val sources = getStringArrayList(ARG_SCORE_SOURCES).orEmpty()
            val values = getStringArrayList(ARG_SCORE_VALUES).orEmpty()
            val votes = getStringArrayList(ARG_SCORE_VOTES).orEmpty()
            val captions = getStringArrayList(ARG_SCORE_CAPTIONS).orEmpty()
            val statValues = getStringArrayList(ARG_STAT_VALUES).orEmpty()
            val statLabels = getStringArrayList(ARG_STAT_LABELS).orEmpty()
            return DetailRatings(
                scores = sources.indices.map {
                    DetailRatingScore(
                        source = sources[it],
                        value = values.getOrElse(it) { "" },
                        votes = votes.getOrElse(it) { "" },
                        caption = captions.getOrElse(it) { "" },
                    )
                },
                stats = statValues.indices.map {
                    DetailRatingStat(statValues[it], statLabels.getOrElse(it) { "" })
                },
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_detail_ratings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val title = args.getString(ARG_TITLE).orEmpty()
        val ratings = args.readRatings()

        view.findViewById<TextView>(R.id.detailRatingsTitle).apply {
            text = title
            isVisible = title.isNotEmpty()
        }

        val inflater = LayoutInflater.from(view.context)

        val scoresRow = view.findViewById<LinearLayout>(R.id.detailRatingsScores)
        view.findViewById<View>(R.id.detailRatingsScoresHeader).isVisible = ratings.scores.isNotEmpty()
        scoresRow.isVisible = ratings.scores.isNotEmpty()
        // Одну-две карточки не растягиваем на всю ширину колонки.
        scoresRow.weightSum = maxOf(ratings.scores.size, SCORE_COLUMNS).toFloat()
        ratings.scores.forEachIndexed { index, score ->
            val card = inflater.inflate(R.layout.item_detail_rating_score, scoresRow, false)
            card.findViewById<TextView>(R.id.ratingScoreSource).text = score.source
            card.findViewById<TextView>(R.id.ratingScoreValue).text = score.value
            card.findViewById<TextView>(R.id.ratingScoreVotes).apply {
                text = score.votes
                isVisible = score.votes.isNotEmpty()
            }
            card.findViewById<TextView>(R.id.ratingScoreCaption).text = score.caption
            card.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (index > 0) marginStart = dp(12) }
            scoresRow.addView(card)
        }

        val statsGrid = view.findViewById<LinearLayout>(R.id.detailRatingsStats)
        view.findViewById<View>(R.id.detailRatingsStatsHeader).isVisible = ratings.stats.isNotEmpty()
        statsGrid.isVisible = ratings.stats.isNotEmpty()
        ratings.stats.chunked(STATS_COLUMNS).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(view.context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = STATS_COLUMNS.toFloat()
            }
            chunk.forEach { stat ->
                val cell = inflater.inflate(R.layout.item_detail_rating_stat, row, false)
                cell.findViewById<TextView>(R.id.ratingStatValue).text = stat.value
                cell.findViewById<TextView>(R.id.ratingStatLabel).text = stat.label
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(cell)
            }
            statsGrid.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (rowIndex > 0) topMargin = dp(20) }
            )
        }

        val scroll = view.findViewById<ScrollView>(R.id.detailRatingsScroll)
        scrollView = scroll
        scroll.requestFocus()
    }
}
