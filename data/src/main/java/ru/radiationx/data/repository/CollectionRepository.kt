package ru.radiationx.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.api.CollectionApi
import ru.radiationx.data.datasource.storage.CollectionIdEntry
import ru.radiationx.data.datasource.storage.CollectionIdsSnapshot
import ru.radiationx.data.datasource.storage.CollectionIdsStorage
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.Paginated
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.mapper.toDomain
import ru.radiationx.data.interactors.ReleaseUpdateMiddleware
import ru.radiationx.data.system.ApiUtils
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Коллекции пользователя.
 *
 * Карта «релиз → коллекция» дополнительно кэшируется в памяти и на диске
 * ([observeCollectionIds]): при старте публикуется снимок с диска, свежий грузится в фоне
 * не чаще раза в [REFRESH_TTL_MS]; [setReleaseCollection] обновляет кэш сразу;
 * при выходе из аккаунта кэш стирается.
 */
class CollectionRepository @Inject constructor(
    private val collectionApi: CollectionApi,
    private val updateMiddleware: ReleaseUpdateMiddleware,
    private val apiUtils: ApiUtils,
    private val apiConfig: ApiConfig,
    private val authRepository: AuthRepository,
    private val idsStorage: CollectionIdsStorage,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val restoreMutex = Mutex()

    /** null — данных ещё нет (не восстановлено с диска и не загружено). */
    private val idsState = MutableStateFlow<Map<ReleaseId, CollectionType>?>(null)

    /** Локальные изменения: релиз → (коллекция, когда изменили). Важнее ответа, запрошенного раньше. */
    private val localChanges = ConcurrentHashMap<ReleaseId, Pair<CollectionType?, Long>>()

    @Volatile
    private var restored = false

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var failedAt = 0L

    @Volatile
    private var refreshJob: Job? = null

    private var lastAuth: AuthState? = null

    init {
        authRepository
            .observeAuthState()
            .onEach { auth ->
                val wasAuth = lastAuth == AuthState.AUTH
                lastAuth = auth
                if (auth == AuthState.AUTH) {
                    if (!wasAuth) loadedAt = 0L
                } else if (wasAuth || idsState.value != null) {
                    clearIds()
                }
            }
            .launchIn(scope)
    }

    suspend fun getReleases(
        type: CollectionType,
        page: Int,
        limit: Int = 10,
    ): Paginated<Release> = withContext(Dispatchers.IO) {
        collectionApi
            .getCollectionReleases(type, page, limit)
            .toDomain(apiUtils, apiConfig)
            .also { updateMiddleware.handle(it.data) }
    }

    /** Свежая карта с сервера; заодно обновляет кэш [observeCollectionIds]. */
    suspend fun getCollectionIds(): Map<ReleaseId, CollectionType> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        loadIds().also { publish(it, startedAt) }
    }

    suspend fun getReleaseCollection(releaseId: ReleaseId): CollectionType? = withContext(Dispatchers.IO) {
        getCollectionIds()[releaseId]
    }

    suspend fun setReleaseCollection(
        releaseId: ReleaseId,
        type: CollectionType?,
    ) = withContext(Dispatchers.IO) {
        if (type == null) {
            collectionApi.deleteFromCollections(releaseId.id)
        } else {
            collectionApi.addToCollection(releaseId.id, type)
        }
        onLocalChange(releaseId, type)
    }

    /** Кэш «релиз → коллекция»: null — данных ещё нет, пусто — без авторизации или коллекции пусты. */
    fun observeCollectionIds(): Flow<Map<ReleaseId, CollectionType>?> = flow {
        ensureRestored()
        requestRefresh()
        emitAll(idsState)
    }.distinctUntilChanged()

    /** Обновляет кэш из сети, если он устарел ([force] — без учёта TTL). Не блокирует вызывающего. */
    fun requestRefresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - loadedAt < REFRESH_TTL_MS) return
        if (!force && now - failedAt < RETRY_BACKOFF_MS) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh(force) }
    }

    private fun clearIds() {
        idsState.value = null
        localChanges.clear()
        restored = false
        loadedAt = 0L
        failedAt = 0L
        idsStorage.clear()
    }

    private suspend fun onLocalChange(releaseId: ReleaseId, type: CollectionType?) {
        if (authRepository.getAuthState() != AuthState.AUTH) return
        ensureRestored()
        localChanges[releaseId] = type to System.currentTimeMillis()
        idsState.update { old -> applyChange(old.orEmpty(), releaseId, type) }
        idsState.value?.also { save(it) }
    }

    private suspend fun ensureRestored() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            try {
                restoreLocked()
            } finally {
                restored = true
            }
        }
    }

    private suspend fun restoreLocked() {
        if (authRepository.getAuthState() != AuthState.AUTH) return
        val snapshot = idsStorage.get() ?: return
        val fromDisk = snapshot.items.mapNotNull { entry ->
            val type = CollectionType.values().find { it.value == entry.type } ?: return@mapNotNull null
            ReleaseId(entry.id) to type
        }.toMap()
        // Уже известное в памяти (загрузка или локальное изменение) важнее дискового снимка.
        idsState.update { current -> current ?: fromDisk }
    }

    private suspend fun refresh(force: Boolean) {
        refreshMutex.withLock {
            if (!force && System.currentTimeMillis() - loadedAt < REFRESH_TTL_MS) return
            ensureRestored()
            if (authRepository.getAuthState() != AuthState.AUTH) return
            val startedAt = System.currentTimeMillis()
            coRunCatching { loadIds() }
                .onSuccess { publish(it, startedAt) }
                .onFailure {
                    failedAt = System.currentTimeMillis()
                    Timber.e(it)
                }
        }
    }

    /** Кладёт ответ сервера в кэш; изменения, сделанные после начала запроса, сохраняются. */
    private suspend fun publish(server: Map<ReleaseId, CollectionType>, startedAt: Long) {
        if (authRepository.getAuthState() != AuthState.AUTH) return
        var merged = server
        localChanges.entries.toList().forEach { (releaseId, change) ->
            if (change.second > startedAt) {
                merged = applyChange(merged, releaseId, change.first)
            } else {
                localChanges.remove(releaseId, change)
            }
        }
        idsState.value = merged
        loadedAt = System.currentTimeMillis()
        failedAt = 0L
        restored = true
        save(merged)
    }

    private fun save(ids: Map<ReleaseId, CollectionType>) {
        idsStorage.save(
            CollectionIdsSnapshot(
                items = ids.map { (id, type) -> CollectionIdEntry(id.id, type.value) },
                loadedAt = loadedAt,
            )
        )
    }

    private fun applyChange(
        ids: Map<ReleaseId, CollectionType>,
        releaseId: ReleaseId,
        type: CollectionType?,
    ): Map<ReleaseId, CollectionType> = if (type == null) ids - releaseId else ids + (releaseId to type)

    private suspend fun loadIds(): Map<ReleaseId, CollectionType> = collectionApi
        .getCollectionIds()
        .mapNotNull { item ->
            val releaseId = (item.getOrNull(0) as? Number)?.toInt() ?: return@mapNotNull null
            val typeValue = item.getOrNull(1) as? String ?: return@mapNotNull null
            val type = CollectionType.values().find { it.value == typeValue }
                ?: return@mapNotNull null
            ReleaseId(releaseId) to type
        }
        .toMap()

    private companion object {
        const val REFRESH_TTL_MS = 10 * 60 * 1_000L
        const val RETRY_BACKOFF_MS = 60 * 1_000L
    }
}
