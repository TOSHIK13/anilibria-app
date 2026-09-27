package ru.radiationx.shared_app.imageloader

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import coil3.transform.Transformation

class ImageLoaderScope {

    private var config = ImageLoaderScopeConfig()

    fun onStart(listener: () -> Unit) {
        config = config.copy(onStart = listener)
    }

    fun onSuccess(listener: (Bitmap?) -> Unit) {
        config = config.copy(onSuccess = listener)
    }

    fun onError(listener: (Throwable) -> Unit) {
        config = config.copy(onError = listener)
    }

    fun onCancel(listener: () -> Unit) {
        config = config.copy(onCancel = listener)
    }

    fun onComplete(listener: () -> Unit) {
        config = config.copy(onComplete = listener)
    }

    /** Преобразования картинки (Coil); результат кэшируется в памяти отдельно от оригинала. */
    fun transformations(vararg items: Transformation) {
        config = config.copy(transformations = items.toList())
    }

    fun build() = config.copy()
}

data class ImageLoaderScopeConfig(
    val onStart: (() -> Unit)? = null,
    val onSuccess: ((Bitmap?) -> Unit)? = null,
    val onError: ((Throwable) -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
    val onComplete: (() -> Unit)? = null,
    val transformations: List<Transformation> = emptyList(),
)

fun ImageView.showImageUrl(url: String?, block: ImageLoaderScope.() -> Unit = {}) {
    val scope = ImageLoaderScope().apply(block)
    val config = scope.build()
    LibriaImageLoaderRoot.getImpl().showImage(this, url, config)
}

suspend fun Context.loadImageBitmap(url: String?): Bitmap? {
    return LibriaImageLoaderRoot.getImpl().loadImageBitmap(this, url)
}
