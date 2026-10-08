package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import com.music.bitchord.data.stats.NameEntry
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Base64
import java.util.Locale
import java.util.prefs.Preferences

/** One track's listening inside a month. */
data class DesktopReplayEntry(
    val song: Song,
    val listenedMs: Long,
    val plays: Int,
)

/** A charted artist, album or genre — counted by name, with whatever the credit carried. */
data class DesktopRankedEntry(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val browseId: String?,
    val ms: Long,
    val plays: Int,
)

/** The stretches a Replay can cover. Months and years, because that is how the listening is kept. */
enum class DesktopReplayPeriod(val chip: String) {
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    ALL_TIME("All time"),
    ;

    fun covers(month: String, today: LocalDate): Boolean = when (this) {
        // Anything migrated from the store that predates monthly buckets.
        THIS_MONTH -> month == YearMonth.from(today).toString()
        THIS_YEAR -> month.startsWith("${today.year}-")
        ALL_TIME -> true
    }
}

data class DesktopReplaySummary(
    val period: DesktopReplayPeriod = DesktopReplayPeriod.ALL_TIME,
    val label: String = "",
    val totalMs: Long = 0L,
    val totalPlays: Int = 0,
    val songs: List<DesktopReplayEntry> = emptyList(),
    val artists: List<DesktopRankedEntry> = emptyList(),
    val albums: List<DesktopRankedEntry> = emptyList(),
    val hourOfDay: List<Long> = List(24) { 0L },
    /** `YYYY-MM-DD` of the day with the most listening. */
    val busiestDay: String? = null,
    val busiestDayMs: Long = 0L,
    val distinctSongs: Int = 0,
    val distinctArtists: Int = 0,
    val distinctAlbums: Int = 0,
    /** The earliest month with anything in it, `YYYY-MM`. */
    val since: String? = null,
) {
    /** One track is enough: a small Replay is a small Replay, and it grows. */
    val isEmpty: Boolean get() = songs.isEmpty()

    val minutes: Long get() = totalMs / 60_000L
    val hours: Long get() = totalMs / 3_600_000L

    val peakHour: Int?
        get() = hourOfDay.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
}

/**
 * What has been listened to, kept in monthly buckets so a Replay can be asked for a period.
 *
 * Mirrors Android's `ListeningStats`: per month, the tracks heard with their time and play count,
 * the hour-of-day spread, and the per-day totals that name the busiest day.
 */
object DesktopListeningStats {

    private val preferences: Preferences = Preferences.userRoot().node("com.music.bitchord.desktop.stats")
    private val lock = Any()
    
    private val directory by lazy {
        File(System.getProperty("user.home"), ".bitchord/listening").apply { mkdirs() }
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** A month's listening. */
    internal data class Bucket(
        val month: String,
        val tracks: MutableMap<String, DesktopReplayEntry> = LinkedHashMap(),
        val hours: LongArray = LongArray(24),
        val days: MutableMap<String, Long> = LinkedHashMap(),
    )

    fun record(song: Song, playedMs: Long, countsAsPlay: Boolean) {
        if (song.videoId.isBlank() || (playedMs <= 0L && !countsAsPlay)) return
        val at = Instant.now().atZone(ZoneId.systemDefault())
        synchronized(lock) {
            val buckets = read().associateByTo(LinkedHashMap(), Bucket::month)
            val key = YearMonth.from(at).toString()
            val bucket = buckets.getOrPut(key) { Bucket(key) }
            val previous = bucket.tracks[song.videoId]
            bucket.tracks[song.videoId] = DesktopReplayEntry(
                song = mergeSong(previous?.song, song),
                listenedMs = (previous?.listenedMs ?: 0L) + playedMs.coerceAtLeast(0L),
                plays = (previous?.plays ?: 0) + if (countsAsPlay) 1 else 0,
            )
            val added = playedMs.coerceAtLeast(0L)
            bucket.hours[at.hour] += added
            val day = at.toLocalDate().toString()
            bucket.days[day] = (bucket.days[day] ?: 0L) + added
            write(buckets.values.toList())
        }
    }

    fun summary(period: DesktopReplayPeriod = DesktopReplayPeriod.ALL_TIME): DesktopReplaySummary =
        synchronized(lock) { summarise(read(), period, LocalDate.now()) }

    /** Everything recorded, thrown away. */
    fun clear() = synchronized(lock) {
        DesktopPreferenceChunks.write(preferences, KEY_BUCKETS, "")
        DesktopPreferenceChunks.write(preferences, KEY_ENTRIES, "")
    }

    /**
     * The aggregation, apart from where the buckets came from.
     *
     * Split out so it can be checked without a store: the real one is the listener's own play
     * history, and a test that wrote to it would be rewriting what it is meant to be reporting.
     */
    internal fun summarise(
        buckets: List<Bucket>,
        period: DesktopReplayPeriod,
        today: LocalDate,
    ): DesktopReplaySummary {
        val inPeriod = buckets.filter { period.covers(it.month, today) }
        val entries = inPeriod.flatMap { it.tracks.values }
            .groupBy { it.song.videoId }
            .map { (_, plays) ->
                DesktopReplayEntry(
                    song = plays.map(DesktopReplayEntry::song).reduce { a, b -> mergeSong(a, b) },
                    listenedMs = plays.sumOf(DesktopReplayEntry::listenedMs),
                    plays = plays.sumOf(DesktopReplayEntry::plays),
                )
            }
            .filter { it.listenedMs > 0L || it.plays > 0 }

        val hours = LongArray(24)
        inPeriod.forEach { bucket -> bucket.hours.forEachIndexed { hour, ms -> hours[hour] += ms } }
        val days = LinkedHashMap<String, Long>()
        inPeriod.forEach { bucket ->
            bucket.days.forEach { (day, ms) -> days[day] = (days[day] ?: 0L) + ms }
        }
        val busiest = days.maxByOrNull { it.value }

        return DesktopReplaySummary(
            period = period,
            label = labelFor(period, today),
            totalMs = entries.sumOf(DesktopReplayEntry::listenedMs),
            totalPlays = entries.sumOf(DesktopReplayEntry::plays),
            songs = entries.sortedByDescending(DesktopReplayEntry::listenedMs),
            artists = rank(entries) { it.song.artist.ifBlank { "Unknown Artist" } },
            albums = rank(entries) { it.song.albumName?.takeIf(String::isNotBlank) ?: "Unknown Album" },
            hourOfDay = hours.toList(),
            busiestDay = busiest?.key,
            busiestDayMs = busiest?.value ?: 0L,
            distinctSongs = entries.size,
            distinctArtists = entries.map { it.song.artist.ifBlank { "Unknown Artist" } }.distinct().size,
            distinctAlbums = entries.mapNotNull { it.song.albumName?.takeIf(String::isNotBlank) }.distinct().size,
            since = buckets.map(Bucket::month).filter { it != LEGACY_MONTH }.minOrNull(),
        )
    }

    /** The credit as the lead artist, not the whole billing — a guest verse is not a chart entry. */
    internal fun primaryArtist(credit: String): String =
        credit.split(",", "&", " feat.", " ft.", " x ", " × ")
            .firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: credit.trim()

    private fun rank(
        entries: List<DesktopReplayEntry>,
        by: (DesktopReplayEntry) -> String,
    ): List<DesktopRankedEntry> = entries
        .groupBy(by)
        .map { (name, plays) ->
            val lead = plays.maxByOrNull(DesktopReplayEntry::listenedMs)?.song
            DesktopRankedEntry(
                title = name,
                subtitle = null,
                artworkUrl = lead?.thumbnailUrl,
                browseId = null,
                ms = plays.sumOf(DesktopReplayEntry::listenedMs),
                plays = plays.sumOf(DesktopReplayEntry::plays),
            )
        }
        .sortedByDescending(DesktopRankedEntry::ms)

    private fun labelFor(period: DesktopReplayPeriod, today: LocalDate): String = when (period) {
        DesktopReplayPeriod.THIS_MONTH ->
            today.month.name.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase)
        DesktopReplayPeriod.THIS_YEAR -> today.year.toString()
        DesktopReplayPeriod.ALL_TIME -> "All time"
    }

    internal fun mergeSong(old: Song?, current: Song): Song = old?.copy(
        title = current.title.ifBlank { old.title },
        artist = current.artist.ifBlank { old.artist },
        thumbnailUrl = current.thumbnailUrl ?: old.thumbnailUrl,
        albumId = current.albumId ?: old.albumId,
        albumName = current.albumName ?: old.albumName,
    ) ?: current

    // ── Storage ──────────────────────────────────────────────────────────

    private fun read(): List<Bucket> {
        val files = directory.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        val buckets = files.mapNotNull { file ->
            runCatching {
                val stored = json.decodeFromString(StoredBucket.serializer(), file.readText())
                val bucket = Bucket(stored.month)
                stored.tracks.forEach { track ->
                    bucket.tracks[track.id] = DesktopReplayEntry(
                        song = Song(
                            videoId = track.id,
                            title = track.title,
                            artist = track.artist,
                            albumName = track.album,
                            albumId = track.albumId,
                            artistId = track.artistId,
                            thumbnailUrl = track.art
                        ),
                        listenedMs = track.ms,
                        plays = track.plays
                    )
                }
                stored.hours.forEachIndexed { i, ms ->
                    if (i < bucket.hours.size) bucket.hours[i] = ms
                }
                stored.days.forEach { (d, ms) ->
                    val dayStr = "${stored.month}-%02d".format(d)
                    bucket.days[dayStr] = ms
                }
                bucket
            }.getOrNull()
        }.toMutableList()

        if (buckets.isEmpty()) {
            val legacy = migrateLegacy()
            if (legacy.isNotEmpty()) {
                write(legacy)
                buckets.addAll(legacy)
            }
        }
        return buckets
    }

    private fun migrateLegacy(): List<Bucket> {
        val legacy = DesktopPreferenceChunks.read(preferences, KEY_ENTRIES).orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull(::decodeLegacy)
            .toList()
        if (legacy.isEmpty()) return emptyList()
        val bucket = Bucket(LEGACY_MONTH)
        legacy.forEach { bucket.tracks[it.song.videoId] = it }
        return listOf(bucket)
    }

    private fun write(buckets: List<Bucket>) {
        buckets.forEach { bucket ->
            val stored = StoredBucket(
                month = bucket.month,
                tracks = bucket.tracks.values.map { entry ->
                    TrackEntry(
                        id = entry.song.videoId,
                        title = entry.song.title,
                        artist = entry.song.artist,
                        album = entry.song.albumName,
                        albumId = entry.song.albumId,
                        artistId = entry.song.artistId,
                        art = entry.song.thumbnailUrl,
                        ms = entry.listenedMs,
                        plays = entry.plays,
                        last = System.currentTimeMillis()
                    )
                },
                hours = bucket.hours.toList(),
                days = bucket.days.mapKeys { (k, _) -> 
                    // k is "YYYY-MM-DD", we want "DD" as Int
                    runCatching { k.substringAfterLast("-").toInt() }.getOrDefault(1)
                }
            )
            val file = File(directory, "${bucket.month}.json")
            val tmp = File(directory, "${bucket.month}.json.tmp")
            runCatching {
                tmp.writeText(json.encodeToString(StoredBucket.serializer(), stored))
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }

    private fun decodeLegacy(value: String): DesktopReplayEntry? {
        val fields = value.split('|').map(::decodeField)
        if (fields.size < 9 || fields[0].isBlank()) return null
        return DesktopReplayEntry(
            song = Song(
                videoId = fields[0],
                title = fields[1],
                artist = fields[2],
                thumbnailUrl = fields[3].ifBlank { null },
                albumName = fields[4].ifBlank { null },
                albumId = fields[5].ifBlank { null },
                durationText = fields[6].ifBlank { null },
            ),
            listenedMs = fields[7].toLongOrNull()?.coerceAtLeast(0L) ?: return null,
            plays = fields[8].toIntOrNull()?.coerceAtLeast(0) ?: return null,
        )
    }

    private fun decodeField(value: String): String = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrDefault("")

    /** Plays carried over from the undated store; no dated period matches it. */
    internal const val LEGACY_MONTH = "0000-00"

    private const val KEY_BUCKETS = "buckets"
    private const val KEY_ENTRIES = "entries"
}
