package org.feeluown.mobile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosLocalMusicRepositoryTest {
    private val defaultsSuite = "org.feeluown.mobile.tests.local-music.${kotlin.random.Random.nextLong()}"
    private val defaults = NSUserDefaults(suiteName = defaultsSuite)

    @AfterTest
    fun cleanUpDefaults() {
        defaults.removePersistentDomainForName(defaultsSuite)
    }

    @Test
    fun refreshAggregatesAppleDownloadsAndImports() = runTest {
        val download = providerTrack(id = "netease:download", title = "Downloaded")
        val repository = repository(
            mediaJson = mediaJson(id = "apple", title = "Apple song"),
            fileJson = fileJson(
                fileTrack("FeelUOwn/download.mp3", "file:///Documents/FeelUOwn/download.mp3", "Scanned download"),
                fileTrack("Imports/imported.flac", "file:///Documents/Imports/imported.flac", "Imported"),
            ),
            downloads = listOf(completedDownload(download, "file:///Documents/FeelUOwn/download.mp3")),
        )

        val tracks = repository.refreshDatabase()

        assertEquals(
            setOf("netease:download", "ios-file:Imports/imported.flac", "ios-media:apple"),
            tracks.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("ios-media-library", "ios-file-downloads", "ios-file-imports"),
            repository.directories().map { it.id }.toSet(),
        )
        assertEquals("歌曲", repository.directories().single { it.id == "ios-media-library" }.name)
        assertEquals("下载", repository.directories().single { it.id == "ios-file-downloads" }.name)
        assertEquals("导入文件", repository.directories().single { it.id == "ios-file-imports" }.name)
    }

    @Test
    fun deniedApplePermissionDoesNotBlockSandboxSources() = runTest {
        val repository = repository(
            hasApplePermission = false,
            mediaJson = mediaJson(id = "apple", title = "Apple song"),
            fileJson = fileJson(fileTrack("imported.mp3", "file:///Documents/imported.mp3", "Imported")),
            downloads = listOf(completedDownload(providerTrack("provider-id", "Downloaded"), "file:///Documents/FeelUOwn/download.mp3")),
        )

        assertTrue(repository.isDatabaseReady())
        assertTrue(repository.isDatabaseStale())
        val tracks = repository.refreshDatabase()

        assertEquals(setOf("provider-id", "ios-file:imported.mp3"), tracks.map { it.id }.toSet())
        assertFalse(repository.isDatabaseStale())
    }

    @Test
    fun malformedOrFailedScansAreIsolatedBySource() = runTest {
        val malformedFiles = repository(
            mediaJson = mediaJson(id = "apple", title = "Apple song"),
            fileJson = "not-json",
        )
        assertEquals(listOf("Apple song"), malformedFiles.refreshDatabase().map { it.title })

        val failedApple = IosLocalMusicRepository(
            mediaLibrary = FakeMediaLibraryOutput(tracksError = IllegalStateException("media failed")),
            fileMusicOutput = FakeFileMusicOutput(fileJson(fileTrack("song.mp3", "file:///Documents/song.mp3", "Sandbox song"))),
            fileExists = { true },
            defaults = defaults,
        )
        assertEquals(listOf("Sandbox song"), failedApple.refreshDatabase().map { it.title })
    }

    @Test
    fun throwingFileScanIsIsolatedFromAppleSource() = runTest {
        val download = providerTrack("download", "Downloaded")
        val repository = IosLocalMusicRepository(
            mediaLibrary = FakeMediaLibraryOutput(json = mediaJson("apple", "Apple song")),
            fileMusicOutput = ThrowingFileMusicOutput(IllegalStateException("file scan failed")),
            downloadTasks = {
                listOf(completedDownload(download, "file:///Documents/FeelUOwn/download.mp3"))
            },
            defaults = defaults,
        )

        assertEquals(setOf("Apple song", "Downloaded"), repository.refreshDatabase().map { it.title }.toSet())
    }

    @Test
    fun fileScanCancellationIsPropagated() = runTest {
        val repository = IosLocalMusicRepository(
            mediaLibrary = FakeMediaLibraryOutput(hasPermission = false),
            fileMusicOutput = ThrowingFileMusicOutput(CancellationException("cancel scan")),
            defaults = defaults,
        )

        val failure = runCatching { repository.refreshDatabase() }.exceptionOrNull()

        assertIs<CancellationException>(failure)
    }

    @Test
    fun canonicalUriDeduplicationKeepsDownloadIdentityAndMetadata() = runTest {
        val download = providerTrack(
            id = "qqmusic:42",
            title = "Provider title",
            durationMs = null,
            providerId = "qqmusic",
            providerName = "QQ Music",
            coverUrl = "https://example.com/cover.jpg",
        )
        val repository = repository(
            fileJson = fileJson(
                fileTrack(
                    relativePath = "FeelUOwn/song.mp3",
                    uri = "file:///Documents/FeelUOwn/song.mp3",
                    title = "Scanned title",
                    durationMs = 180_000,
                ),
            ),
            downloads = listOf(completedDownload(download, " file:///Documents/FeelUOwn/./song%2Emp3 ")),
        )

        val track = repository.refreshDatabase().single()

        assertEquals("qqmusic:42", track.id)
        assertEquals("Provider title", track.title)
        assertEquals(180_000L, track.durationMs)
        assertEquals("qqmusic", track.providerId)
        assertEquals("QQ Music", track.providerName)
        assertEquals("https://example.com/cover.jpg", track.coverUrl)
        assertEquals(TrackSourceType.LocalMediaStore, track.sourceType)
        assertEquals("ios-file-downloads", track.localDirectoryId)
    }

    @Test
    fun scanSettingsFilterEachDirectoryAndCanonicalTrailingSlashIds() = runTest {
        val repository = repository(
            mediaJson = mediaJson(id = "apple", title = "Apple", durationMs = 120_000),
            fileJson = fileJson(
                fileTrack("FeelUOwn/download.mp3", "file:///Documents/FeelUOwn/download.mp3", "Download", durationMs = 120_000),
                fileTrack("short.mp3", "file:///Documents/short.mp3", "Short import", durationMs = 20_000),
                fileTrack("long.mp3", "file:///Documents/long.mp3", "Long import", durationMs = 120_000),
            ),
            downloads = listOf(completedDownload(providerTrack("download", "Download", durationMs = 120_000), "file:///Documents/FeelUOwn/download.mp3")),
        )
        repository.refreshDatabase()

        repository.updateScanSettings(
            LocalMusicScanSettings(
                excludedDirectoryIds = setOf("ios-file-downloads/"),
                minDurationSeconds = 60,
            ),
        )

        assertEquals(setOf("Apple", "Long import"), repository.tracks().map { it.title }.toSet())
        assertEquals(setOf("ios-media-library", "ios-file-imports"), repository.directories().map { it.id }.toSet())
    }

    @Test
    fun refreshDropsDeletedDownloadsAndMovedImports() = runTest {
        var fileJson = fileJson(fileTrack("imported.mp3", "file:///Documents/imported.mp3", "Imported"))
        val existingUris = mutableSetOf(
            "file:///Documents/imported.mp3",
            "file:///Documents/FeelUOwn/download.mp3",
        )
        val repository = IosLocalMusicRepository(
            mediaLibrary = FakeMediaLibraryOutput(hasPermission = false),
            fileMusicOutput = object : IosFileMusicOutput {
                override fun tracksJson(): String = fileJson
            },
            downloadTasks = {
                listOf(completedDownload(providerTrack("download", "Downloaded"), "file:///Documents/FeelUOwn/download.mp3"))
            },
            fileExists = { uri -> normalizeTestUri(uri) in existingUris },
            defaults = defaults,
        )
        assertEquals(2, repository.refreshDatabase().size)

        existingUris.clear()
        fileJson = fileJson()

        assertEquals(emptyList(), repository.refreshDatabase())
        assertEquals(emptyList(), repository.directories())
        assertTrue(repository.isDatabaseStale())
    }

    @Test
    fun metadataAndLyricsOverridesSurviveRefreshForAllLocalSources() = runTest {
        val repository = repository(
            fileJson = fileJson(fileTrack("imported.mp3", "file:///Documents/imported.mp3", "Imported")),
        )
        val original = repository.refreshDatabase().single()

        repository.updateMetadata(original, LocalTrackMetadata("Edited", "Editor", "Edited album"))
        repository.saveLyrics(original, "[00:00.00]Edited lyrics")
        val refreshed = repository.refreshDatabase().single()

        assertEquals("Edited", refreshed.title)
        assertEquals("Editor", refreshed.artists)
        assertEquals("Edited album", refreshed.album)
        assertEquals("[00:00.00]Edited lyrics", refreshed.lyrics)
    }

    @Test
    fun persistedDownloadMetadataRoundTripsMultilineLyrics() {
        val original = completedDownload(
            providerTrack(
                id = "netease:42",
                title = "Downloaded",
                durationMs = 234_000,
                providerId = "netease",
                providerName = "NetEase Cloud Music",
                coverUrl = "https://example.com/cover.jpg",
            ).copy(lyrics = "[00:00.00]First\n[00:10.00]Second"),
            "file:///Documents/FeelUOwn/song.mp3",
        )

        val decoded = requireNotNull(decodeIosDownloadTask(encodeIosDownloadTask(original)))

        assertEquals(original.track.durationMs, decoded.track.durationMs)
        assertEquals(original.track.providerId, decoded.track.providerId)
        assertEquals(original.track.providerName, decoded.track.providerName)
        assertEquals(original.track.coverUrl, decoded.track.coverUrl)
        assertEquals(original.track.lyrics, decoded.track.lyrics)
    }

    private fun repository(
        hasApplePermission: Boolean = true,
        mediaJson: String = fileJson(),
        fileJson: String = fileJson(),
        downloads: List<DownloadTask> = emptyList(),
    ) = IosLocalMusicRepository(
        mediaLibrary = FakeMediaLibraryOutput(hasApplePermission, mediaJson),
        fileMusicOutput = FakeFileMusicOutput(fileJson),
        downloadTasks = { downloads },
        fileExists = { true },
        defaults = defaults,
    )

    private class FakeMediaLibraryOutput(
        private val hasPermission: Boolean = true,
        private val json: String = fileJson(),
        private val tracksError: Throwable? = null,
    ) : IosMediaLibraryOutput {
        override fun hasPermission(): Boolean = hasPermission

        override fun requestPermission(completionHandler: (Boolean) -> Unit) = completionHandler(hasPermission)

        override fun tracksJson(): String = tracksError?.let { throw it } ?: json
    }

    private class FakeFileMusicOutput(private val json: String) : IosFileMusicOutput {
        override fun tracksJson(): String = json
    }

    private class ThrowingFileMusicOutput(private val failure: Exception) : IosFileMusicOutput {
        override fun tracksJson(): String = throw failure
    }

    private companion object {
        fun providerTrack(
            id: String,
            title: String,
            durationMs: Long? = 180_000,
            providerId: String? = "provider",
            providerName: String? = "Provider",
            coverUrl: String? = null,
        ) = MusicTrack(
            id = id,
            title = title,
            artists = "Artist",
            album = "Album",
            source = providerId.orEmpty(),
            sourceType = TrackSourceType.Provider,
            durationMs = durationMs,
            providerId = providerId,
            providerName = providerName,
            coverUrl = coverUrl,
            lyrics = "Provider lyrics",
        )

        fun completedDownload(track: MusicTrack, uri: String) = DownloadTask(
            id = track.id,
            track = track,
            status = DownloadTaskStatus.Completed,
            createdAt = 1,
            completedUri = uri,
        )

        fun mediaJson(id: String, title: String, durationMs: Long = 180_000) = """
            {"tracks":[{"id":"$id","title":"$title","artists":"Artist","album":"Album","duration_ms":$durationMs,"local_uri":"ipod-library://$id"}]}
        """.trimIndent()

        fun fileJson(vararg tracks: String) = """{"tracks":[${tracks.joinToString(",")}] }"""

        fun fileTrack(
            relativePath: String,
            uri: String,
            title: String,
            durationMs: Long = 180_000,
        ) = """
            {"relative_path":"$relativePath","uri":"$uri","title":"$title","artists":"Artist","album":"Album","duration_ms":$durationMs}
        """.trimIndent()

        fun normalizeTestUri(uri: String) = uri.trim().replaceFirst("FILE://", "file://")
    }
}
