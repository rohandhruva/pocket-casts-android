package au.com.shiftyjelly.pocketcasts.appfunctions

import androidx.appfunctions.AppFunctionSerializable
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import java.time.Instant

/** A podcast episode in the user's library. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class EpisodeResult(
    /** The unique ID of the episode. */
    val episodeUuid: String,
    /** The title of the episode. */
    val title: String,
    /** The title of the podcast that published the episode. */
    val podcastTitle: String,
    /** When the podcast published the episode. */
    val publishedAt: Instant,
    /** The length of the episode in seconds. 0 if unknown. */
    val durationSeconds: Int,
    /** How far the user has listened: "NOT_PLAYED", "IN_PROGRESS", or "COMPLETED". */
    val playbackStatus: String,
    /** Whether the episode is downloaded and plays without a network connection. */
    val isDownloaded: Boolean,
)

/** An episode that was added to the Up Next queue. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class UpNextResult(
    /** The queued episode. */
    val episode: EpisodeResult,
    /** The position of the episode in Up Next. 0 means it is the current episode, 1 means it plays next. */
    val positionInQueue: Int,
    /** The number of episodes waiting in Up Next after the current episode. */
    val queueLength: Int,
)

/** A bookmark at a position in an episode. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class BookmarkResult(
    /** The unique ID of the bookmark. */
    val bookmarkUuid: String,
    /** The label of the bookmark. */
    val title: String,
    /** The title of the bookmarked episode. */
    val episodeTitle: String,
    /** The bookmarked position, in seconds from the start of the episode. */
    val timeSeconds: Int,
)

/** The running sleep timer. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SleepTimerResult(
    /** Minutes until playback pauses. 0 when the timer pauses at the end of the episode. */
    val minutes: Int,
    /** Whether playback pauses when the current episode ends. */
    val endOfEpisode: Boolean,
)

internal fun PodcastEpisode.toResult(podcastTitle: String) = EpisodeResult(
    episodeUuid = uuid,
    title = title,
    podcastTitle = podcastTitle,
    publishedAt = publishedDate.toInstant(),
    durationSeconds = duration.toInt(),
    playbackStatus = playingStatus.name,
    isDownloaded = isDownloaded,
)
