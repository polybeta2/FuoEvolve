package org.feeluown.mobile.persistence.listening

import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.ListeningHistoryRecord
import org.feeluown.mobile.ListeningResourceRelation
import org.feeluown.mobile.ListeningResourceRelationType
import org.feeluown.mobile.ListeningResourceSnapshot
import org.feeluown.mobile.ListeningResourceType
import org.feeluown.mobile.ListeningStartReason
import org.feeluown.mobile.ListeningTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosListeningHistoryDriverFactoryTest {
    @Test
    fun createsDatabaseAndStoreCanReadRecentEventsAndAggregateStatistics() = runBlocking {
        val store = SqlDelightListeningHistoryStore(IosListeningHistoryDriverFactory())
        store.clear()

        val sessionKey = "ios-test-session-${platform.posix.getpid()}"
        val resourceKey = "Track:test:ios-$sessionKey"
        val now = 1_760_000_000_000L

        store.upsert(
            ListeningHistoryRecord(
                sessionKey = sessionKey,
                primaryResourceKey = resourceKey,
                startedAtMillis = now,
                endedAtMillis = now + 30_000L,
                playedMs = 30_000L,
                durationMs = 180_000L,
                qualified = true,
                startReason = ListeningStartReason.UserSelection,
                resources = listOf(
                    ListeningResourceRelation(
                        resource = ListeningResourceSnapshot(
                            resourceKey = resourceKey,
                            type = ListeningResourceType.Track,
                            sourceId = "test",
                            sourceResourceId = "ios-test",
                            title = "iOS test track",
                        ),
                        relation = ListeningResourceRelationType.Primary,
                    ),
                ),
                updatedAtMillis = now + 30_000L,
            ),
        )

        assertEquals(1L, store.eventCount())
        val recent = store.recentEvents(range = ListeningTimeRange.All, limit = 50)
        assertTrue(recent.any { it.sessionKey == sessionKey }, "recent events must contain the written session")

        val insights = store.insights(range = ListeningTimeRange.All, trendBucketMs = 86_400_000L)
        assertEquals(1L, insights.eventCount)
        assertEquals(1L, insights.qualifiedPlayCount)
        assertEquals(30_000L, insights.totalPlayedMs)
        assertEquals(1L, insights.userSelectedPlayCount)
        assertEquals(0L, insights.automaticPlayCount)
        assertEquals(1L, insights.sourceShares.single { it.sourceId == "test" }.eventCount)
    }
}
