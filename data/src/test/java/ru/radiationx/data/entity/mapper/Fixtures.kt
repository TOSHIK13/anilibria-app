package ru.radiationx.data.entity.mapper

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.datasource.remote.IApiUtils

object Fixtures {

    const val IMAGES_BASE = "https://www.anilibria.tv/"
    const val SITE = "https://www.anilibria.tv"

    val moshi: Moshi = Moshi.Builder().build()

    /** Без android html-парсера: для тестов достаточно вернуть текст как есть. */
    val apiUtils = object : IApiUtils {
        override fun toHtml(text: String?): CharSequence? = text
        override fun escapeHtml(text: String?): String? = text
    }

    fun read(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Fixture $name not found"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    inline fun <reified T> parse(name: String): T {
        return requireNotNull(moshi.adapter(T::class.java).fromJson(read(name)))
    }

    inline fun <reified T> parseList(name: String): List<T> {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return requireNotNull(moshi.adapter<List<T>>(type).fromJson(read(name)))
    }
}
