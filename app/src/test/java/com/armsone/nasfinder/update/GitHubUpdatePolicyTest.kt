package com.armsone.nasfinder.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL

class GitHubUpdatePolicyTest {
    @Test fun strictTagAndAssetContract() {
        assertEquals(12, GitHubUpdatePolicy.versionCode("android-v12"))
        assertNull(GitHubUpdatePolicy.versionCode("v12"))
        assertNull(GitHubUpdatePolicy.versionCode("android-v0"))
        assertNull(GitHubUpdatePolicy.versionCode("android-v999999999999999999999"))
        assertTrue(GitHubUpdatePolicy.isApprovedApkAsset(
            "NasFinder-Android-v12.apk",
            "https://github.com/armsone/NasFinder-Android/releases/download/android-v12/NasFinder-Android-v12.apk",
            12,
            1_024,
        ))
        assertFalse(GitHubUpdatePolicy.isApprovedApkAsset(
            "NasFinder-Android-v12.apk",
            "https://example.com/NasFinder-Android-v12.apk",
            12,
            1_024,
        ))
        assertFalse(GitHubUpdatePolicy.isApprovedApkAsset(
            "NasFinder-Android-v12.apk",
            "https://github.com/armsone/NasFinder-Android/releases/download/android-v12/nested/NasFinder-Android-v12.apk",
            12,
            1_024,
        ))
        assertFalse(GitHubUpdatePolicy.isApprovedApkAsset(
            "NasFinder-Android-v12.apk",
            "https://github.com/armsone/NasFinder-Android/releases/download/android-v12/NasFinder-Android-v12.apk?unexpected=1",
            12,
            1_024,
        ))
    }

    @Test fun decoderRejectsDraftPrereleaseAndWrongAsset() {
        assertNull(GitHubReleaseDecoder.decode("""{"draft":true,"prerelease":false,"tag_name":"android-v2","assets":[]}"""))
        assertNull(GitHubReleaseDecoder.decode("""{"draft":false,"prerelease":true,"tag_name":"android-v2","assets":[]}"""))
        assertNull(GitHubReleaseDecoder.decode("""{"draft":false,"prerelease":false,"tag_name":"android-v2","assets":[{"name":"other.apk","browser_download_url":"https://github.com/armsone/NasFinder-Android/releases/download/android-v2/other.apk","size":100}]}"""))
        val accepted = GitHubReleaseDecoder.decode("""{"draft":false,"prerelease":false,"tag_name":"android-v2","assets":[{"name":"NasFinder-Android-v2.apk","browser_download_url":"https://github.com/armsone/NasFinder-Android/releases/download/android-v2/NasFinder-Android-v2.apk","size":100}]}""")
        assertEquals(2, accepted?.versionCode)
        assertEquals(100L, accepted?.assetSizeBytes)
    }

    @Test fun downloadRedirectsAllowOnlyStandardHttpsGitHubReleaseCdnHosts() {
        assertTrue(GitHubUpdatePolicy.isApprovedDownloadRedirect(
            URL("https://release-assets.githubusercontent.com/github-production-release-asset/file.apk?signature=redacted")
        ))
        assertTrue(GitHubUpdatePolicy.isApprovedDownloadRedirect(
            URL("https://objects.githubusercontent.com/release/file.apk")
        ))
        assertFalse(GitHubUpdatePolicy.isApprovedDownloadRedirect(
            URL("http://release-assets.githubusercontent.com/file.apk")
        ))
        assertFalse(GitHubUpdatePolicy.isApprovedDownloadRedirect(
            URL("https://release-assets.githubusercontent.com.evil.example/file.apk")
        ))
        assertFalse(GitHubUpdatePolicy.isApprovedDownloadRedirect(
            URL("https://user@release-assets.githubusercontent.com/file.apk")
        ))
        assertFalse(GitHubUpdatePolicy.isApprovedDownloadRedirect(
            URL("https://release-assets.githubusercontent.com:444/file.apk")
        ))
    }

    @Test fun boundedReleaseReaderRejectsBeforeAppendingPastLimit() {
        assertEquals(
            "12345",
            readBoundedUtf8(ByteArrayInputStream("12345".toByteArray()), 5),
        )
        var rejected = false
        try {
            readBoundedUtf8(ByteArrayInputStream("123456".toByteArray()), 5)
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
