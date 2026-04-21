package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.team.TeamResponse
import ru.radiationx.data.entity.response.team.TeamRoleResponse
import ru.radiationx.data.entity.response.team.TeamUserResponse
import ru.radiationx.data.entity.response.team.TeamsResponse
import ru.radiationx.data.entity.response.team.V1TeamResponse
import ru.radiationx.data.entity.response.team.V1TeamRoleResponse
import ru.radiationx.data.entity.response.team.V1TeamUserResponse
import javax.inject.Inject

class TeamsApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi
) {

    suspend fun getTeams(): TeamsResponse {
        val roles = client
            .get("${apiConfig.animeBaseUrl}/api/v1/teams/roles", emptyMap())
            .fetchList<V1TeamRoleResponse>()
            .sortedBy { it.sortOrder ?: Int.MAX_VALUE }

        val teams = client
            .get("${apiConfig.animeBaseUrl}/api/v1/teams/", emptyMap())
            .fetchList<V1TeamResponse>()
            .sortedBy { it.sortOrder ?: Int.MAX_VALUE }

        val usersByTeamId = client
            .get("${apiConfig.animeBaseUrl}/api/v1/teams/users", emptyMap())
            .fetchList<V1TeamUserResponse>()
            .groupBy { it.team.id }

        return TeamsResponse(
            headerRoles = roles.map { role ->
                TeamRoleResponse(
                    title = role.title,
                    color = role.color
                )
            },
            teams = teams.map { team ->
                TeamResponse(
                    title = team.title,
                    description = team.description,
                    users = usersByTeamId[team.id]
                        .orEmpty()
                        .sortedBy { it.sortOrder ?: Int.MAX_VALUE }
                        .map { user ->
                            TeamUserResponse(
                                nickname = user.nickname,
                                roles = user.roles
                                    .sortedBy { it.sortOrder ?: Int.MAX_VALUE }
                                    .map { role ->
                                        TeamRoleResponse(
                                            title = role.title,
                                            color = role.color
                                        )
                                    },
                                isIntern = user.isIntern,
                                isVacation = user.isVacation
                            )
                        }
                )
            }
        )
    }

    private suspend inline fun <reified T> String.fetchList(): List<T> {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return fetchResponse(moshi, type)
    }
}
