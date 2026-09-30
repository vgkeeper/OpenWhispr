package com.edib.openwhispr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkLinksTest {
    @Test
    fun `fork distribution links are separate from verified upstream`() {
        assertTrue(ForkLinks.FORK_REPOSITORY_URL.startsWith("https://github.com/vgkeeper/"))
        assertTrue(ForkLinks.UPSTREAM_REPOSITORY_URL.startsWith("https://github.com/EdiBianco/"))
        assertTrue(ForkLinks.LATEST_RELEASE_API_URL.contains("/repos/vgkeeper/OpenWhispr/"))
    }

    @Test
    fun `cached release URLs are accepted only from fork releases`() {
        assertTrue(ForkLinks.isForkReleasePage("https://github.com/vgkeeper/OpenWhispr/releases/tag/v3.11.0"))
        assertTrue(ForkLinks.isForkReleaseApk("https://github.com/vgkeeper/OpenWhispr/releases/download/v3.11.0/app.apk"))
        assertFalse(ForkLinks.isForkReleasePage("https://github.com/EdiBianco/OpenWhispr/releases/tag/v3.11.0"))
        assertFalse(ForkLinks.isForkReleaseApk("http://github.com/vgkeeper/OpenWhispr/releases/download/v3.11.0/app.apk"))
        assertFalse(ForkLinks.isForkReleaseApk("https://example.com/vgkeeper/OpenWhispr/releases/download/v3.11.0/app.apk"))
    }
}
