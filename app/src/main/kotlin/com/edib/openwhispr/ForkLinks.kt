package com.edib.openwhispr

import java.net.URI

/** Fork-specific distribution links, kept separate from upstream project metadata. */
object ForkLinks {
    const val FORK_OWNER = "vgkeeper"
    const val REPOSITORY = "OpenWhispr"
    const val FORK_REPOSITORY_URL = "https://github.com/$FORK_OWNER/$REPOSITORY"
    const val FORK_RELEASES_URL = "$FORK_REPOSITORY_URL/releases"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$FORK_OWNER/$REPOSITORY/releases/latest"

    // Verified from the repository's configured `upstream` Git remote.
    const val UPSTREAM_OWNER = "EdiBianco"
    const val UPSTREAM_REPOSITORY = "OpenWhispr"
    const val UPSTREAM_REPOSITORY_URL = "https://github.com/$UPSTREAM_OWNER/$UPSTREAM_REPOSITORY"

    const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

    fun isForkReleasePage(url: String): Boolean = hasForkPath(url, "/releases/tag/")

    fun isForkReleaseApk(url: String): Boolean = hasForkPath(url, "/releases/download/")

    private fun hasForkPath(url: String, suffix: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawPath.startsWith("/$FORK_OWNER/$REPOSITORY$suffix", ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}
