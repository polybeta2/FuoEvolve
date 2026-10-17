package org.feeluown.mobile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults

@OptIn(ExperimentalForeignApi::class)
class IosLocalMusicRepository(
    private val mediaLibrary: IosMediaLibraryOutput,
    private val fileMusicOutput: IosFileMusicOutput = EmptyIosFileMusicOutput,
    private val downloadTasks: () -> List<DownloadTask> = { emptyList() },
    private val fileExists: (String) -> Boolean = ::iosLocalFileExists,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : LocalMusicRepository {
    private var scanSettings = LocalMusicScanSettings()
    private var libraryTracks = emptyList<MusicTrack>()
    private var cachedTracks = emptyList<MusicTrack>()
    private var hasScanned = false
    private val mutex = Mutex()

    val hasPermission: Boolean
        get() = mediaLibrary.hasPermission()

    fun requestPermission(onGranted: () -> Unit) {
        mediaLibrary.requestPermission { granted ->
            if (granted) onGranted()
        }
    }

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) = mutex.withLock {
        scanSettings = settings
        cachedTracks = filterTracks(libraryTracks)
    }

    override suspend fun isDatabaseReady(): Boolean = true

    override suspend fun isDatabaseStale(): Boolean = mutex.withLock {
        !hasScanned || libraryTracks.isEmpty()
    }

    override suspend fun directories(): List<LocalMusicDirectory> = mutex.withLock {
        DIRECTORY_DEFINITIONS.mapNotNull { definition ->
            val tracks = cachedTracks.filter { it.localDirectoryId == definition.id }
            tracks.takeIf { it.isNotEmpty() }?.let {
                LocalMusicDirectory(
                    id = definition.id,
                    name = definition.name,
                    trackCount = it.size,
                    coverUrl = it.firstNotNullOfOrNull(MusicTrack::coverUrl),
                )
            }
        }
    }

    override suspend fun tracks(): List<MusicTrack> = mutex.withLock {
        if (!hasScanned) refreshDatabaseLocked() else cachedTracks
    }

    override suspend fun refreshDatabase(): List<MusicTrack> = mutex.withLock {
        refreshDatabaseLocked()
    }

    private suspend fun refreshDatabaseLocked(): List<MusicTrack> {
        val applePermission = hasPermission
        val taskSnapshot = downloadTasks()
        val refreshedTracks = withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val scannedFiles = isolatedSource { parseFileTracks(fileMusicOutput.tracksJson()) }.orEmpty()
            currentCoroutineContext().ensureActive()
            val downloads = buildDownloadTracks(taskSnapshot, scannedFiles)
            val downloadUris = downloads.mapNotNullTo(mutableSetOf()) {
                it.localUri?.let(::normalizeIosLocalUri)
            }
            val imports = scannedFiles.asSequence()
                .filterNot { it.relativePath.isFeelUOwnDownloadPath() }
                .filter { fileExists(it.uri) }
                .filterNot { normalizeIosLocalUri(it.uri) in downloadUris }
                .map { it.toImportTrack() }
                .toList()
            currentCoroutineContext().ensureActive()
            val appleTracks = if (applePermission) {
                isolatedSource { parseAppleTracks(mediaLibrary.tracksJson()) }.orEmpty()
            } else {
                emptyList()
            }

            (downloads + imports + appleTracks)
                .distinctBy { track -> track.localUri?.let(::normalizeIosLocalUri) ?: track.id }
        }
        currentCoroutineContext().ensureActive()
        libraryTracks = refreshedTracks.map(::applyOverrides)
        hasScanned = true
        cachedTracks = filterTracks(libraryTracks)
        return cachedTracks
    }

    override suspend fun search(keyword: String): List<MusicTrack> {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return tracks()
        return tracks().filter {
            it.title.contains(normalized, ignoreCase = true) ||
                it.artists.contains(normalized, ignoreCase = true) ||
                it.album.contains(normalized, ignoreCase = true)
        }
    }

    override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) = mutex.withLock {
        defaults.setObject(
            listOf(metadata.title, metadata.artists, metadata.album).joinToString(METADATA_SEPARATOR),
            metadataKey(track.id),
        )
        defaults.synchronize()
        libraryTracks = libraryTracks.map { item ->
            if (item.id == track.id) {
                item.copy(title = metadata.title, artists = metadata.artists, album = metadata.album)
            } else {
                item
            }
        }
        cachedTracks = filterTracks(libraryTracks)
    }

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) = mutex.withLock {
        defaults.setObject(lyrics, lyricsKey(track.id))
        defaults.synchronize()
        libraryTracks = libraryTracks.map { item ->
            if (item.id == track.id) item.copy(lyrics = lyrics) else item
        }
        cachedTracks = filterTracks(libraryTracks)
    }

    private fun buildDownloadTracks(tasks: List<DownloadTask>, scannedFiles: List<FileTrack>): List<MusicTrack> {
        val filesByUri = scannedFiles.associateBy { normalizeIosLocalUri(it.uri) }
        return tasks.asSequence()
            .filter { it.status == DownloadTaskStatus.Completed }
            .mapNotNull { task ->
                val uri = task.completedUri?.takeIf { it.isNotBlank() }?.let(::normalizeIosLocalUri)
                    ?: return@mapNotNull null
                if (!fileExists(uri)) return@mapNotNull null
                val file = filesByUri[uri]
                task.track.copy(
                    title = task.track.title.ifBlank { file?.title ?: "未知歌曲" },
                    artists = task.track.artists.ifBlank { file?.artists ?: "未知歌手" },
                    album = task.track.album.ifBlank { file?.album.orEmpty() },
                    sourceType = TrackSourceType.LocalMediaStore,
                    durationMs = task.track.durationMs ?: file?.durationMs,
                    localUri = uri,
                    localDirectoryId = DOWNLOADS_DIRECTORY_ID,
                )
            }
            .distinctBy { it.localUri?.let(::normalizeIosLocalUri) }
            .toList()
    }

    private fun parseAppleTracks(json: String): List<MusicTrack> {
        return parseTrackObjects(json).mapNotNull { item ->
            isolatedItem {
                val persistentId = item.string("id")
                if (persistentId.isBlank()) return@isolatedItem null
                MusicTrack(
                    id = "ios-media:$persistentId",
                    title = item.string("title").ifBlank { "未知歌曲" },
                    artists = item.string("artists").ifBlank { "未知歌手" },
                    album = item.string("album"),
                    source = "本地音乐",
                    sourceType = TrackSourceType.LocalMediaStore,
                    coverUrl = item.optionalString("artwork_url"),
                    durationMs = item.long("duration_ms"),
                    localUri = item.optionalString("local_uri"),
                    localDirectoryId = APPLE_DIRECTORY_ID,
                )
            }
        }
    }

    private fun parseFileTracks(json: String): List<FileTrack> {
        return parseTrackObjects(json).mapNotNull { item ->
            isolatedItem {
                val relativePath = item.string("relative_path").normalizeRelativePath()
                val uri = item.string("uri").let(::normalizeIosLocalUri)
                if (relativePath.isBlank() || uri.isBlank()) return@isolatedItem null
                FileTrack(
                    relativePath = relativePath,
                    uri = uri,
                    title = item.string("title").ifBlank {
                        relativePath.substringAfterLast('/').substringBeforeLast('.')
                    },
                    artists = item.string("artists").ifBlank { "未知歌手" },
                    album = item.string("album"),
                    durationMs = item.long("duration_ms"),
                )
            }
        }
    }

    private fun parseTrackObjects(json: String): List<JsonObject> {
        val root = Json.parseToJsonElement(json).jsonObject
        return root["tracks"]?.jsonArray.orEmpty().mapNotNull { element ->
            isolatedItem { element.jsonObject }
        }
    }

    private fun FileTrack.toImportTrack() = MusicTrack(
        id = "ios-file:$relativePath",
        title = title,
        artists = artists,
        album = album,
        source = "本地音乐",
        sourceType = TrackSourceType.LocalMediaStore,
        durationMs = durationMs,
        localUri = uri,
        localDirectoryId = IMPORTS_DIRECTORY_ID,
    )

    private fun applyOverrides(track: MusicTrack): MusicTrack {
        val metadata = defaults.stringForKey(metadataKey(track.id))?.split(METADATA_SEPARATOR, limit = 3)
        return track.copy(
            title = metadata?.getOrNull(0)?.takeIf { it.isNotBlank() } ?: track.title,
            artists = metadata?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: track.artists,
            album = metadata?.getOrNull(2) ?: track.album,
            lyrics = defaults.stringForKey(lyricsKey(track.id))?.takeIf { it.isNotBlank() } ?: track.lyrics,
        )
    }

    private fun filterTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val minDurationMs = scanSettings.minDurationSeconds.toLong() * 1_000L
        return tracks.filter { track ->
            val directoryId = track.localDirectoryId
            directoryId != null && isLocalMusicDirectoryEnabled(directoryId, scanSettings) &&
                (track.durationMs ?: 0L) >= minDurationMs
        }
    }

    private fun metadataKey(trackId: String) = "ios_local_metadata_$trackId"

    private fun lyricsKey(trackId: String) = "ios_local_lyrics_$trackId"

    private data class FileTrack(
        val relativePath: String,
        val uri: String,
        val title: String,
        val artists: String,
        val album: String,
        val durationMs: Long?,
    )

    private data class DirectoryDefinition(val id: String, val name: String)

    private companion object {
        private const val APPLE_DIRECTORY_ID = "ios-media-library"
        private const val DOWNLOADS_DIRECTORY_ID = "ios-file-downloads"
        private const val IMPORTS_DIRECTORY_ID = "ios-file-imports"
        private const val METADATA_SEPARATOR = "\u001f"
        private val DIRECTORY_DEFINITIONS = listOf(
            DirectoryDefinition(APPLE_DIRECTORY_ID, "歌曲"),
            DirectoryDefinition(DOWNLOADS_DIRECTORY_ID, "下载"),
            DirectoryDefinition(IMPORTS_DIRECTORY_ID, "导入文件"),
        )
    }
}

private inline fun <T> isolatedSource(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private inline fun <T> isolatedItem(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private fun JsonObject.string(key: String): String = optionalString(key).orEmpty()

private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun String.normalizeRelativePath(): String = replace('\\', '/').trim().trimStart('/')

private fun String.isFeelUOwnDownloadPath(): Boolean {
    val normalized = normalizeRelativePath()
    return normalized.equals("FeelUOwn", ignoreCase = true) ||
        normalized.startsWith("FeelUOwn/", ignoreCase = true)
}

internal fun normalizeIosLocalUri(uri: String): String {
    val trimmed = uri.trim().trimEnd('/')
    val url = NSURL.URLWithString(trimmed)
    if (url?.isFileURL() == true) {
        val path = url.path ?: return trimmed
        return NSURL.fileURLWithPath(path).standardizedURL?.absoluteString ?: trimmed
    }
    return url?.standardizedURL?.absoluteString ?: trimmed
}

@OptIn(ExperimentalForeignApi::class)
private fun iosLocalFileExists(uri: String): Boolean {
    val path = NSURL.URLWithString(normalizeIosLocalUri(uri))?.path ?: return false
    return NSFileManager.defaultManager.fileExistsAtPath(path)
}
