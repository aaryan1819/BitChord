package com.music.bitchord.data.stats

import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import okio.buffer
import okio.source
import java.util.Locale

object StatsSyncManager {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun sync(
        localDirectory: File,
        webDavUrl: String,
        username: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = WebDavConfig.normalizeUrl(webDavUrl)
            require(WebDavConfig.isConfigured(root)) { "WebDAV is not configured" }

            val syncDir = WebDavClient.joinUrl(root, "listening")
            WebDavClient.ensureCollection(syncDir, username, password)

            // 1. List remote JSON files
            val remoteEntries = WebDavClient.propfind(syncDir, username, password).getOrThrow()
            
            // 2. Local files
            localDirectory.mkdirs()
            val localFiles = localDirectory.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
            val localKeys = localFiles.map { it.name.removeSuffix(".json") }.toSet()

            // Map of month -> remote entry
            val remoteMap = remoteEntries.filter { !it.isCollection && it.url.endsWith(".json") }
                .associateBy { it.displayName.removeSuffix(".json") }

            val allKeys = localKeys + remoteMap.keys

            for (key in allKeys) {
                var localBucket: StoredBucket? = null
                val localFile = File(localDirectory, "$key.json")
                if (localFile.exists()) {
                    localBucket = runCatching { 
                        json.decodeFromString(StoredBucket.serializer(), localFile.readText()) 
                    }.getOrNull()
                }

                var remoteBucket: StoredBucket? = null
                val remoteEntry = remoteMap[key]
                if (remoteEntry != null) {
                    val request = okhttp3.Request.Builder().url(remoteEntry.url).get()
                    WebDavConfig.basicAuthHeader(username, password)?.let { request.header("Authorization", it) }
                    com.music.bitchord.data.Http.client.newCall(request.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val text = response.body?.string().orEmpty()
                            if (text.isNotBlank()) {
                                remoteBucket = runCatching {
                                    json.decodeFromString(StoredBucket.serializer(), text)
                                }.getOrNull()
                            }
                        }
                    }
                }

                if (localBucket == null && remoteBucket == null) continue

                // Merge
                val merged = mergeBuckets(key, localBucket, remoteBucket)

                // Write locally if different
                val mergedText = json.encodeToString(StoredBucket.serializer(), merged)
                if (localBucket == null || json.encodeToString(StoredBucket.serializer(), localBucket) != mergedText) {
                    val tmp = File(localDirectory, "$key.json.tmp")
                    tmp.writeText(mergedText)
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        localFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }

                // Write remotely if different
                if (remoteBucket == null || json.encodeToString(StoredBucket.serializer(), remoteBucket) != mergedText) {
                    val targetUrl = WebDavClient.joinUrl(syncDir, "$key.json")
                    WebDavClient.putFile(
                        fileUrl = targetUrl,
                        stream = mergedText.byteInputStream(),
                        contentLength = mergedText.toByteArray().size.toLong(),
                        mimeType = "application/json",
                        username = username,
                        password = password,
                        overwrite = true
                    )
                }
            }
        }
    }

    private fun TrackEntry.syncAbsorb(other: TrackEntry) {
        ms = maxOf(ms, other.ms)
        plays = maxOf(plays, other.plays)
        last = maxOf(last, other.last)
        if (album == null) album = other.album
        if (albumId == null) albumId = other.albumId
        if (artistId == null) artistId = other.artistId
        if (art == null) art = other.art
    }

    private fun NameEntry.syncAbsorb(other: NameEntry) {
        ms = maxOf(ms, other.ms)
        plays = maxOf(plays, other.plays)
        if (art == null) art = other.art
        if (id == null) id = other.id
    }

    private fun mergeBuckets(month: String, a: StoredBucket?, b: StoredBucket?): StoredBucket {
        if (a == null) return b!!
        if (b == null) return a

        val tracks = HashMap<String, TrackEntry>()
        a.tracks.forEach { tracks[it.id] = it.copy() }
        b.tracks.forEach { entry ->
            tracks.merge(entry.id, entry.copy()) { old, new -> old.apply { syncAbsorb(new) } }
        }

        val artists = HashMap<String, NameEntry>()
        a.artists.forEach { artists[it.name] = it.copy() }
        b.artists.forEach { entry ->
            artists.merge(entry.name, entry.copy()) { old, new -> old.apply { syncAbsorb(new) } }
        }

        val albums = HashMap<String, NameEntry>()
        a.albums.forEach { albums[albumKey(it.name, it.sub)] = it.copy() }
        b.albums.forEach { entry ->
            val key = albumKey(entry.name, entry.sub)
            albums.merge(key, entry.copy()) { old, new -> old.apply { syncAbsorb(new) } }
        }

        val hours = LongArray(24)
        repeat(24) { i ->
            hours[i] = maxOf(a.hours.getOrElse(i) { 0L }, b.hours.getOrElse(i) { 0L })
        }

        val days = HashMap<Int, Long>(a.days)
        b.days.forEach { (d, ms) ->
            days[d] = maxOf(days[d] ?: 0L, ms)
        }

        return StoredBucket(
            version = maxOf(a.version, b.version),
            month = month,
            tracks = tracks.values.toList(),
            artists = artists.values.toList(),
            albums = albums.values.toList(),
            hours = hours.toList(),
            days = days
        )
    }

    private fun albumKey(name: String, sub: String?): String = 
        name.lowercase(Locale.ROOT) + 31.toChar() + (sub ?: "").lowercase(Locale.ROOT)
}
