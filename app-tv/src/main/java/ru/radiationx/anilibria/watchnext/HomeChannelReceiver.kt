package ru.radiationx.anilibria.watchnext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.radiationx.quill.Quill

/**
 * Лаунчер присылает INITIALIZE_PROGRAMS после установки приложения —
 * создаём канал по умолчанию сразу, не дожидаясь первого запуска.
 */
class HomeChannelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Quill.getRootScope().get(HomeChannelPublisher::class).sync()
            } finally {
                pending.finish()
            }
        }
    }
}
