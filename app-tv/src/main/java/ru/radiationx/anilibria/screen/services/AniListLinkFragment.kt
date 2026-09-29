package ru.radiationx.anilibria.screen.services

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import dev.androidbroadcast.vbpd.viewBinding
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.QrCode
import ru.radiationx.anilibria.databinding.FragmentAnilistLinkBinding
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Вход в AniList: QR со ссылкой, поле для токена, проверка и подключение. */
class AniListLinkFragment : Fragment(R.layout.fragment_anilist_link) {

    private val binding by viewBinding<FragmentAnilistLinkBinding>()

    private val viewModel by viewModel<AniListLinkViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        subscribeTo(viewModel.lanUrl) { renderLink(it) }

        binding.linkToken.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = viewModel.onTokenChanged(s?.toString().orEmpty())
        })
        binding.linkToken.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.connect(binding.linkToken.text.toString())
                true
            } else {
                false
            }
        }
        binding.linkConnect.setOnClickListener { viewModel.connect(binding.linkToken.text.toString()) }
        binding.linkCancel.setOnClickListener { viewModel.cancel() }

        subscribeTo(viewModel.check) { render(it) }
        binding.linkToken.requestFocus()
    }

    /** QR и шаги: страница на ТВ в локальной сети, а без неё — прямая ссылка на AniList. */
    private fun renderLink(lanUrl: String?) {
        val url = lanUrl ?: viewModel.authorizeUrl
        binding.linkUrl.text = url
        val qr = QrCode.toBitmap(url)
        if (qr != null) {
            binding.linkQr.isVisible = true
            binding.linkQrFallback.isVisible = false
            binding.linkQr.setImageDrawable(BitmapDrawable(resources, qr).apply { paint.isFilterBitmap = false })
        } else {
            binding.linkQr.isVisible = false
            binding.linkQrFallback.isVisible = true
        }
        if (lanUrl != null) {
            binding.linkStep1.setText(R.string.anilist_link_lan_step1)
            binding.linkStep2.setText(R.string.anilist_link_lan_step2)
            binding.linkStep3.setText(R.string.anilist_link_lan_step3)
            binding.linkFieldLabel.setText(R.string.anilist_link_lan_fallback_label)
        } else {
            binding.linkStep1.setText(R.string.anilist_link_step1)
            binding.linkStep2.setText(R.string.anilist_link_step2)
            binding.linkStep3.setText(R.string.anilist_link_step3)
            binding.linkFieldLabel.setText(R.string.anilist_link_field_label)
        }
    }

    private fun render(check: TokenCheck) {
        val status = binding.linkStatus
        val grey = 0x99FFFFFF.toInt()
        when (check) {
            TokenCheck.Idle -> {
                status.text = ""
                status.setTextColor(grey)
            }

            TokenCheck.FromPhone -> {
                status.setText(R.string.anilist_link_from_phone)
                status.setTextColor(grey)
            }

            TokenCheck.Checking -> {
                status.setText(R.string.anilist_link_checking)
                status.setTextColor(grey)
            }

            is TokenCheck.Ok -> {
                val date = SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(check.expiresAtSec * 1000))
                status.text = getString(R.string.anilist_link_ok, check.name, date)
                status.setTextColor(0xFF4CD964.toInt())
            }

            is TokenCheck.Invalid -> {
                status.text = getString(R.string.anilist_link_invalid, check.reason)
                status.setTextColor(0xFFFE3635.toInt())
            }

            is TokenCheck.Network -> {
                status.text = getString(R.string.anilist_link_network, check.message)
                status.setTextColor(0xFFFE3635.toInt())
            }
        }
        binding.linkConnect.alpha = if (check is TokenCheck.Ok) 1f else 0.45f
    }
}
