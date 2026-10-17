package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleMusicPermissionPolicyTest {
    @Test
    fun refreshesOnlyWhenAppleMusicPermissionTransitionsFromDeniedToGranted() {
        assertFalse(shouldRefreshAfterAppleMusicPermissionChange(previous = null, current = true))
        assertFalse(shouldRefreshAfterAppleMusicPermissionChange(previous = false, current = false))
        assertTrue(shouldRefreshAfterAppleMusicPermissionChange(previous = false, current = true))
        assertFalse(shouldRefreshAfterAppleMusicPermissionChange(previous = true, current = true))
        assertFalse(shouldRefreshAfterAppleMusicPermissionChange(previous = true, current = false))
    }

    @Test
    fun optionalAppleMusicPermissionRowCountsAsInitialLocalMusicContent() {
        assertTrue(
            hasInitialLocalMusicContent(
                hasAudioPermission = true,
                showAppleMusicPermission = true,
                hasAppleMusicPermission = false,
                hasTracks = false,
                hasDirectories = false,
            ),
        )
        assertFalse(
            hasInitialLocalMusicContent(
                hasAudioPermission = true,
                showAppleMusicPermission = false,
                hasAppleMusicPermission = true,
                hasTracks = false,
                hasDirectories = false,
            ),
        )
    }
}
