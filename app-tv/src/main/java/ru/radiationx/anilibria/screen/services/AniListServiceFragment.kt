package ru.radiationx.anilibria.screen.services

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.androidbroadcast.vbpd.viewBinding
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.databinding.FragmentAnilistServiceBinding
import ru.radiationx.anilibria.screen.profile.SettingsSwitchView
import ru.radiationx.data.external.ExternalServiceOptions
import ru.radiationx.data.tracker.TrackerState
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Экран сервиса AniList: состояние входа, плитки, переключатели, «Обновить вход», «Отключить». */
class AniListServiceFragment : Fragment(R.layout.fragment_anilist_service) {

    private val binding by viewBinding<FragmentAnilistServiceBinding>()

    private val viewModel by viewModel<AniListServiceViewModel>()

    private var expiresValue: TextView? = null
    private var switchRows: List<Pair<View, (ExternalServiceOptions) -> Boolean>> = emptyList()
    private var refreshRow: View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        binding.serviceIcon.background = GradientDrawable().apply {
            cornerRadius = 14 * resources.displayMetrics.density
            setColor(0xFF02A9FF.toInt())
        }

        val inflater = LayoutInflater.from(requireContext())
        // «Синхронизировать сейчас» и «Журнал синхронизации» появятся на этапах 3–4.
        addTile(inflater, binding.serviceTilesTop, R.string.anilist_service_tile_last_sync).text = "—"
        addTile(inflater, binding.serviceTilesTop, R.string.anilist_service_tile_queue).text = "0"
        addTile(inflater, binding.serviceTilesBottom, R.string.anilist_service_tile_linked).text = "—"
        expiresValue = addTile(inflater, binding.serviceTilesBottom, R.string.anilist_service_tile_expires)

        switchRows = listOf(
            switchRow(inflater, R.string.anilist_service_opt_watched, { it.sendWatched }) {
                viewModel.update { o -> o.copy(sendWatched = !o.sendWatched) }
            },
            switchRow(inflater, R.string.anilist_service_opt_collection, { it.sendCollection }) {
                viewModel.update { o -> o.copy(sendCollection = !o.sendCollection) }
            },
            switchRow(inflater, R.string.anilist_service_opt_receive, { it.receiveChanges }) {
                viewModel.update { o -> o.copy(receiveChanges = !o.receiveChanges) }
            },
            switchRow(inflater, R.string.anilist_service_opt_notify, { it.notifyAfterEpisode }) {
                viewModel.update { o -> o.copy(notifyAfterEpisode = !o.notifyAfterEpisode) }
            },
        )
        refreshRow = arrowRow(inflater, R.string.anilist_service_refresh_login) { viewModel.refreshLogin() }
        arrowRow(inflater, R.string.anilist_service_disconnect) { confirmDisconnect() }

        subscribeTo(viewModel.ui) { render(it) }
        binding.serviceRows.getChildAt(0)?.requestFocus()
    }

    override fun onDestroyView() {
        expiresValue = null
        refreshRow = null
        switchRows = emptyList()
        super.onDestroyView()
    }

    private fun addTile(inflater: LayoutInflater, parent: ViewGroup, label: Int): TextView {
        val tile = inflater.inflate(R.layout.item_service_tile, parent, false)
        if (parent.childCount > 0) {
            (tile.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                (10 * resources.displayMetrics.density).toInt()
        }
        tile.findViewById<TextView>(R.id.tileLabel).setText(label)
        parent.addView(tile)
        return tile.findViewById(R.id.tileValue)
    }

    private fun baseRow(inflater: LayoutInflater, title: Int, onClick: () -> Unit): View {
        val row = inflater.inflate(R.layout.item_settings_row, binding.serviceRows, false)
        row.findViewById<TextView>(R.id.settingsRowTitle).setText(title)
        row.findViewById<View>(R.id.settingsRowSubtitle).visibility = View.GONE
        row.setOnClickListener { onClick() }
        binding.serviceRows.addView(row)
        return row
    }

    private fun switchRow(
        inflater: LayoutInflater,
        title: Int,
        get: (ExternalServiceOptions) -> Boolean,
        onClick: () -> Unit,
    ): Pair<View, (ExternalServiceOptions) -> Boolean> {
        val row = baseRow(inflater, title, onClick)
        row.findViewById<View>(R.id.settingsRowValue).visibility = View.GONE
        return row to get
    }

    private fun arrowRow(inflater: LayoutInflater, title: Int, onClick: () -> Unit): View {
        val row = baseRow(inflater, title, onClick)
        row.findViewById<TextView>(R.id.settingsRowValue).text = "›"
        row.findViewById<View>(R.id.settingsRowSwitch).visibility = View.GONE
        return row
    }

    private fun render(ui: ServiceUi) {
        val state = ui.state
        val account = when (state) {
            is TrackerState.Linked -> state.account
            is TrackerState.Expiring -> state.account
            is TrackerState.Expired -> state.account
            else -> null
        }
        if (account == null) {
            // отключили — назад на «Аккаунты и сервисы»
            viewModel.close()
            return
        }
        binding.serviceNick.text = account.nick
        val expiresAtMs = when (state) {
            is TrackerState.Linked -> state.expiresAtMs
            is TrackerState.Expiring -> state.expiresAtMs
            else -> null
        }
        var statusColor = 0x99FFFFFF.toInt()
        binding.serviceStatus.text = when (state) {
            is TrackerState.Linked -> getString(R.string.anilist_service_status_linked)
            is TrackerState.Expiring -> getString(
                R.string.anilist_service_status_expiring,
                daysText(state.daysLeft)
            )

            is TrackerState.Expired -> {
                statusColor = 0xFFFE3635.toInt()
                getString(R.string.anilist_service_status_expired)
            }

            else -> ""
        }
        binding.serviceStatus.setTextColor(statusColor)
        expiresValue?.text = expiresAtMs
            ?.let { SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(it)) }
            ?: "—"
        refreshRow?.findViewById<TextView>(R.id.settingsRowTitle)?.setText(
            if (state is TrackerState.Expired) R.string.anilist_service_relogin else R.string.anilist_service_refresh_login
        )
        switchRows.forEach { (row, get) ->
            row.findViewById<SettingsSwitchView>(R.id.settingsRowSwitch).isOn = get(ui.options)
        }
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.anilist_service_disconnect_title)
            .setMessage(R.string.anilist_service_disconnect_message)
            .setPositiveButton(R.string.anilist_service_disconnect_confirm) { _, _ -> viewModel.disconnect() }
            .setNegativeButton(R.string.anilist_link_cancel, null)
            .show()
            .also { it.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() } // по умолчанию «Отмена»
    }
}
