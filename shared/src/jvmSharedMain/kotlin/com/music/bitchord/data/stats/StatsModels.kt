package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** One track's totals inside a bucket. */
@Serializable
data class TrackEntry(
    val id: String,
    val title: String = "",
    val artist: String = "",
    var album: String? = null,
    var albumId: String? = null,
    var artistId: String? = null,
    var art: String? = null,
    var ms: Long = 0L,
    var plays: Int = 0,
    var last: Long = 0L,
) {
    fun absorb(other: TrackEntry) {
        ms += other.ms
        plays += other.plays
        last = maxOf(last, other.last)
        if (album == null) album = other.album
        if (albumId == null) albumId = other.albumId
        if (artistId == null) artistId = other.artistId
        if (art == null) art = other.art
    }
}

/** One artist's or album's totals. */
@Serializable
data class NameEntry(
    val name: String = "",
    val sub: String? = null,
    var art: String? = null,
    var id: String? = null,
    var ms: Long = 0L,
    var plays: Int = 0,
    val key: String? = null,
) {
    fun absorb(other: NameEntry) {
        ms += other.ms
        plays += other.plays
        if (art == null) art = other.art
        if (id == null) id = other.id
    }
}

/** One calendar month on disk. */
@Serializable
data class StoredBucket(
    val version: Int = 1,
    val month: String,
    val tracks: List<TrackEntry> = emptyList(),
    val artists: List<NameEntry> = emptyList(),
    val albums: List<NameEntry> = emptyList(),
    /** Milliseconds played per hour of the day, 0..23. */
    val hours: List<Long> = List(24) { 0L },
    /** Milliseconds played per day of the month. */
    val days: Map<Int, Long> = emptyMap(),
)

/** A row on one of the four charts. */
data class RankedEntry(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val browseId: String?,
    val ms: Long,
    val plays: Int,
)

/** A song row, which keeps the whole [Song] so tapping it can play it. */
data class RankedSong(val song: Song, val ms: Long, val plays: Int)

/** How far back a Replay reaches. */
enum class ReplayPeriod(val chip: String) {
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    ALL_TIME("All time"),
    ;

    fun covers(month: YearMonth, today: LocalDate): Boolean = when (this) {
        THIS_MONTH -> month == YearMonth.from(today)
        THIS_YEAR -> month.year == today.year
        ALL_TIME -> true
    }

    fun label(today: LocalDate): String = when (this) {
        THIS_MONTH -> YearMonth.from(today).month.name.lowercase(Locale.ROOT)
            .replaceFirstChar { it.uppercase(Locale.ROOT) } + " ${today.year}"
        THIS_YEAR -> today.year.toString()
        ALL_TIME -> "All time"
    }
}

/** Everything the Replay page and the stories draw, worked out once. */
data class ReplaySummary(
    val period: ReplayPeriod,
    val label: String,
    val totalMs: Long,
    val totalPlays: Int,
    val songs: List<RankedSong>,
    val artists: List<RankedEntry>,
    val albums: List<RankedEntry>,
    val genres: List<RankedEntry>,
    val hourOfDay: List<Long>,
    /** `YYYY-MM-DD` of the day with the most listening, or null. */
    val busiestDay: String?,
    val busiestDayMs: Long,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val distinctAlbums: Int,
    /** The earliest month with anything in it, `YYYY-MM`. */
    val since: String?,
) {
    val minutes: Long get() = totalMs / 60_000
    val hours: Long get() = totalMs / 3_600_000
    val isEmpty: Boolean get() = songs.isEmpty()
    val peakHour: Int? get() = hourOfDay.withIndex()
        .filter { it.value > 0 }
        .maxByOrNull { it.value }
        ?.index
}
