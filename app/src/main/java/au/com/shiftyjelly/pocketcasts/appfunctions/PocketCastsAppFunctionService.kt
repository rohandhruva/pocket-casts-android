package au.com.shiftyjelly.pocketcasts.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionNotSupportedException
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.AppFunctionStringValueConstraint
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimer
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.automattic.eventhorizon.BookmarkSourceType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Exposes Pocket Casts tasks to on-device agents through Android AppFunctions.
 * KSP generates the concrete `PocketCastsAppFunctionServiceImpl` class registered in the manifest.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "PocketCastsAppFunctionServiceImpl",
    appFunctionXmlFileName = "pocket_casts_app_functions",
)
abstract class PocketCastsAppFunctionService : AppFunctionService() {

    @Inject internal lateinit var episodeManager: EpisodeManager

    @Inject internal lateinit var podcastManager: PodcastManager

    @Inject internal lateinit var playbackManager: PlaybackManager

    @Inject internal lateinit var bookmarkManager: BookmarkManager

    @Inject internal lateinit var sleepTimer: SleepTimer

    @Inject internal lateinit var settings: Settings

    /**
     * Searches episodes of the podcasts the user follows, newest first.
     *
     * Matches `query` against episode titles and podcast titles. If `query` is blank and `podcastTitle` is set,
     * returns the latest episodes of that podcast. If both are blank, returns the latest episodes across all followed
     * podcasts.
     *
     * @param query Words from the episode or podcast title, for example "AI agents". Blank matches every episode.
     * @param podcastTitle Words from the podcast title that limit results to that podcast, for example "Hard Fork".
     * Blank includes all followed podcasts.
     * @param limit Maximum number of episodes to return, from 1 to 25. Defaults to 10 when 0.
     * @return Matching episodes, newest first. Empty if nothing matches.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun searchEpisodes(
        query: String? = null,
        podcastTitle: String? = null,
        limit: Int = 0,
    ): List<EpisodeResult> = runAppFunction("searchEpisodes") {
        val trimmedQuery = query.orEmpty().trim()
        val trimmedPodcastTitle = podcastTitle.orEmpty().trim()
        if (limit !in 0..MAX_SEARCH_RESULTS) {
            throw AppFunctionInvalidArgumentException("limit must be between 1 and $MAX_SEARCH_RESULTS.")
        }
        val episodes = episodeManager.searchSubscribedEpisodes(
            query = trimmedQuery,
            podcastTitle = trimmedPodcastTitle,
            limit = if (limit == 0) DEFAULT_SEARCH_RESULTS else limit,
        )
        val podcastTitles = episodes.map { it.podcastUuid }.distinct().associateWith { podcastUuid ->
            podcastManager.findPodcastByUuid(podcastUuid)?.title.orEmpty()
        }
        episodes.map { it.toResult(podcastTitles[it.podcastUuid].orEmpty()) }
    }

    /**
     * Starts playing an episode immediately, replacing the episode that is currently playing.
     *
     * Fails if Android doesn't allow Pocket Casts to start playback from the background. Opening Pocket Casts
     * first resolves this.
     *
     * @param episodeUuid The `episodeUuid` of the episode to play.
     * @return The episode that is now playing.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun playEpisode(episodeUuid: String): EpisodeResult = runAppFunction("playEpisode") {
        val episode = findEpisode(episodeUuid)
        playbackManager.playNowSuspend(episode = episode, sourceView = SourceView.UNKNOWN)
        // Android can block starting the playback foreground service while the app is cached,
        // which fails silently, so confirm that playback started.
        val started = withTimeoutOrNull(PLAYBACK_START_TIMEOUT) {
            playbackManager.playbackStateFlow.first { state ->
                state.episodeUuid == episode.uuid && (state.isPlaying || state.isBuffering)
            }
        }
        if (started == null) {
            throw AppFunctionDeniedException(
                "Pocket Casts couldn't start playback in the background. Open Pocket Casts and try again.",
            )
        }
        episode.toResult(podcastTitleFor(episode))
    }

    /**
     * Adds an episode to the Up Next queue without interrupting current playback.
     *
     * If nothing is playing, the episode becomes the current episode but doesn't start playing.
     *
     * @param episodeUuid The `episodeUuid` of the episode to queue.
     * @param position "NEXT" plays the episode after the current one. "LAST" adds it to the end of the queue.
     * Defaults to "NEXT".
     * @return The queued episode and its position in Up Next.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun addToUpNext(
        episodeUuid: String,
        @AppFunctionStringValueConstraint(enumValues = [POSITION_NEXT, POSITION_LAST])
        position: String? = null,
    ): UpNextResult = runAppFunction("addToUpNext") {
        val episode = findEpisode(episodeUuid)
        if (playbackManager.upNextQueue.isCurrentEpisode(episode)) {
            throw AppFunctionInvalidArgumentException("This episode is already playing.")
        }
        when (position ?: POSITION_NEXT) {
            POSITION_NEXT -> playbackManager.playNext(episode = episode, source = SourceView.UNKNOWN)
            POSITION_LAST -> playbackManager.playLast(episode = episode, source = SourceView.UNKNOWN)
            else -> throw AppFunctionInvalidArgumentException("position must be \"$POSITION_NEXT\" or \"$POSITION_LAST\".")
        }
        val queue = playbackManager.upNextQueue
        val isCurrent = queue.isCurrentEpisode(episode)
        val queueIndex = queue.queueEpisodes.indexOfFirst { it.uuid == episode.uuid }
        UpNextResult(
            episode = episode.toResult(podcastTitleFor(episode)),
            positionInQueue = if (isCurrent) 0 else queueIndex + 1,
            queueLength = queue.queueEpisodes.size,
        )
    }

    /**
     * Bookmarks the current playback position of the episode that is playing or paused.
     *
     * Bookmarks require a Pocket Casts Plus or Patron subscription.
     *
     * @param title A short label for the bookmark, for example "Book recommendation". Defaults to "Bookmark".
     * @return The created bookmark.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun addBookmark(title: String? = null): BookmarkResult = runAppFunction("addBookmark") {
        if (settings.cachedSubscription.value == null) {
            throw AppFunctionNotSupportedException("Bookmarks require a Pocket Casts Plus or Patron subscription.")
        }
        val episode = playbackManager.getCurrentEpisode()
            ?: throw AppFunctionElementNotFoundException("No episode is playing or paused.")
        val timeSecs = playbackManager.getCurrentTimeMs(episode) / 1000
        val bookmark = bookmarkManager.findByEpisodeTime(episode = episode, timeSecs = timeSecs)
            ?: bookmarkManager.add(
                episode = episode,
                timeSecs = timeSecs,
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: getString(LR.string.bookmark),
                // TODO: Add an AppFunctions bookmark source type to the analytics schema
                creationSource = BookmarkSourceType.Player,
            )
        BookmarkResult(
            bookmarkUuid = bookmark.uuid,
            title = bookmark.title,
            episodeTitle = episode.title,
            timeSeconds = bookmark.timeSecs,
        )
    }

    /**
     * Sets the sleep timer that pauses playback, replacing any running sleep timer.
     *
     * Set exactly one of `minutes` or `endOfEpisode`.
     *
     * @param minutes Pauses playback after this many minutes, from 1 to 600.
     * @param endOfEpisode If true, pauses playback when the current episode ends.
     * @return The sleep timer that is now running.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun setSleepTimer(
        minutes: Int = 0,
        endOfEpisode: Boolean = false,
    ): SleepTimerResult = runAppFunction("setSleepTimer") {
        when {
            endOfEpisode && minutes != 0 -> {
                throw AppFunctionInvalidArgumentException("Set either minutes or endOfEpisode, not both.")
            }

            endOfEpisode -> {
                settings.setlastSleepEndOfEpisodes(1)
                sleepTimer.cancelTimer()
                sleepTimer.updateSleepTimerStatus(sleepTimeRunning = true, sleepAfterEpisodes = 1)
                SleepTimerResult(minutes = 0, endOfEpisode = true)
            }

            minutes in 1..MAX_SLEEP_MINUTES -> {
                sleepTimer.sleepAfter(minutes.minutes)
                SleepTimerResult(minutes = minutes, endOfEpisode = false)
            }

            else -> {
                throw AppFunctionInvalidArgumentException("minutes must be between 1 and $MAX_SLEEP_MINUTES.")
            }
        }
    }

    private suspend fun findEpisode(episodeUuid: String): PodcastEpisode {
        return episodeManager.findByUuid(episodeUuid.trim())
            ?: throw AppFunctionElementNotFoundException("No episode matches episodeUuid \"$episodeUuid\".")
    }

    private suspend fun podcastTitleFor(episode: PodcastEpisode): String {
        return podcastManager.findPodcastByUuid(episode.podcastUuid)?.title.orEmpty()
    }

    private suspend fun <T> runAppFunction(name: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "AppFunction called: $name")
        try {
            block()
        } catch (e: AppFunctionException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "AppFunction failed: $name")
            throw AppFunctionAppUnknownException("Pocket Casts couldn't complete $name.")
        }
    }

    private companion object {
        const val POSITION_NEXT = "NEXT"
        const val POSITION_LAST = "LAST"
        const val DEFAULT_SEARCH_RESULTS = 10
        const val MAX_SEARCH_RESULTS = 25
        const val MAX_SLEEP_MINUTES = 600
        val PLAYBACK_START_TIMEOUT = 5.seconds
    }
}
