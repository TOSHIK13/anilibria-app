package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.media.V1VideoResponse
import javax.inject.Inject

class YoutubeApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi,
) {

    companion object {
        const val MAX_VIDEOS_LIMIT = 50
        private const val VIDEO_FIELDS = "id,url,title,views,comments,image,video_id,created_at"
    }

    /** V1: последние видео, голый массив, limit 1..50, пагинации нет. */
    suspend fun getVideos(limit: Int): List<V1VideoResponse> {
        val args = mapOf(
            "limit" to limit.coerceIn(1, MAX_VIDEOS_LIMIT).toString(),
            "include" to VIDEO_FIELDS,
        )
        val type = Types.newParameterizedType(List::class.java, V1VideoResponse::class.java)
        return client
            .get("${apiConfig.animeBaseUrl}/api/v1/media/videos", args)
            .fetchResponse(moshi, type)
    }
}