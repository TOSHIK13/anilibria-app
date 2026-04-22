package ru.radiationx.anilibria.screen

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.github.terrakok.cicerone.androidx.FragmentScreen
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.common.fragment.GuidedAppScreen
import ru.radiationx.anilibria.screen.auth.credentials.AuthCredentialsGuidedFragment
import ru.radiationx.anilibria.screen.auth.main.AuthGuidedFragment
import ru.radiationx.anilibria.screen.auth.otp.AuthOtpGuidedFragment
import ru.radiationx.anilibria.screen.config.ConfigFragment
import ru.radiationx.anilibria.screen.details.collection.DetailCollectionGuidedFragment
import ru.radiationx.anilibria.screen.details.DetailFragment
import ru.radiationx.anilibria.screen.details.other.DetailOtherGuidedFragment
import ru.radiationx.anilibria.screen.mainpages.MainPagesFragment
import ru.radiationx.anilibria.screen.player.ComposePlayerFragment
import ru.radiationx.anilibria.screen.player.BasePlayerGuidedFragment.Companion.ARG_EPISODE_ID
import ru.radiationx.anilibria.screen.player.BasePlayerGuidedFragment.Companion.ARG_RELEASE_ID
import ru.radiationx.anilibria.screen.player.episodes.PlayerEpisodesGuidedFragment
import ru.radiationx.anilibria.screen.player.settings.PlayerBufferSettingsGuidedFragment
import ru.radiationx.anilibria.screen.player.settings.PlayerBufferTarget
import ru.radiationx.anilibria.screen.schedule.ScheduleFragment
import ru.radiationx.anilibria.screen.search.SearchFragment
import ru.radiationx.anilibria.screen.search.BaseSearchValuesGuidedFragment.Companion.ARG_VALUES
import ru.radiationx.anilibria.screen.search.completed.SearchCompletedGuidedFragment
import ru.radiationx.anilibria.screen.search.genre.SearchGenreGuidedFragment
import ru.radiationx.anilibria.screen.search.season.SearchSeasonGuidedFragment
import ru.radiationx.anilibria.screen.search.sort.SearchSortGuidedFragment
import ru.radiationx.anilibria.screen.search.year.SearchYearGuidedFragment
import ru.radiationx.anilibria.screen.suggestions.SuggestionsFragment
import ru.radiationx.anilibria.screen.trash.TestFragment
import ru.radiationx.anilibria.screen.update.UpdateFragment
import ru.radiationx.anilibria.screen.update.source.UpdateSourceGuidedFragment
import ru.radiationx.anilibria.screen.update.warning.UpdateWarningGuidedFragment
import ru.radiationx.data.entity.domain.search.SearchForm
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.domain.updater.UpdateData
import ru.radiationx.shared.ktx.android.putExtra

class ConfigScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return ConfigFragment()
    }
}

class MainPagesScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return MainPagesFragment()
    }
}

class DetailsScreen(private val releaseId: ReleaseId) : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return DetailFragment.newInstance(releaseId)
    }
}

class DetailOtherGuidedScreen(private val releaseId: ReleaseId) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return DetailOtherGuidedFragment.newInstance(releaseId)
    }
}

class DetailCollectionGuidedScreen(private val releaseId: ReleaseId) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return DetailCollectionGuidedFragment.newInstance(releaseId)
    }
}

class ScheduleScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return ScheduleFragment()
    }
}

class UpdateScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return UpdateFragment()
    }
}

class UpdateSourceScreen : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return UpdateSourceGuidedFragment()
    }
}

class UpdateWarningScreen(private val link: UpdateData.UpdateLink) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return UpdateWarningGuidedFragment.newInstance(link)
    }
}

class SuggestionsScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return SuggestionsFragment()
    }
}

class SearchScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return SearchFragment()
    }
}

class SearchYearGuidedScreen(private val values: List<String>) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SearchYearGuidedFragment().putExtra {
            putStringArrayList(ARG_VALUES, ArrayList(values))
        }
    }
}

class SearchSeasonGuidedScreen(private val values: List<String>) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SearchSeasonGuidedFragment().putExtra {
            putStringArrayList(ARG_VALUES, ArrayList(values))
        }
    }
}

class SearchGenreGuidedScreen(private val values: List<String>) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SearchGenreGuidedFragment().putExtra {
            putStringArrayList(ARG_VALUES, ArrayList(values))
        }
    }
}

class SearchSortGuidedScreen(private val sort: SearchForm.Sort) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SearchSortGuidedFragment.newInstance(sort)
    }
}

class SearchCompletedGuidedScreen(private val onlyCompleted: Boolean) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SearchCompletedGuidedFragment.newInstance(onlyCompleted)
    }
}

class TestScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return TestFragment()
    }
}

class AuthGuidedScreen : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return AuthGuidedFragment()
    }
}

class AuthCredentialsGuidedScreen : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return AuthCredentialsGuidedFragment()
    }
}

class AuthOtpGuidedScreen : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return AuthOtpGuidedFragment()
    }
}

class PlayerScreen(
    private val releaseId: ReleaseId,
    private val episodeId: EpisodeId?,
) : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return ComposePlayerFragment.newInstance(releaseId, episodeId)
    }
}

class PlayerEpisodesGuidedScreen(
    private val releaseId: ReleaseId,
    private val episodeId: EpisodeId?,
) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return PlayerEpisodesGuidedFragment().putExtra {
            putParcelable(ARG_RELEASE_ID, releaseId)
            putParcelable(ARG_EPISODE_ID, episodeId)
        }
    }
}

class TestGuidedStepScreen : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return DialogExampleFragment()
    }
}

class PlayerBufferSettingsGuidedScreen(
    private val target: PlayerBufferTarget,
) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return PlayerBufferSettingsGuidedFragment.newInstance(target)
    }
}
