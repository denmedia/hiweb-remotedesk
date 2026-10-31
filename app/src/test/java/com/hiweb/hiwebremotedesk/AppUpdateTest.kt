package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.AppUpdate
import com.hiweb.hiwebremotedesk.data.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppUpdateTest {
    private val sha = "a".repeat(64)

    private fun release(tag: String, vararg assets: Pair<String, String>): String {
        val list = assets.joinToString(",") { (name, extra) ->
            """{"name":"$name","browser_download_url":"https://example.invalid/$name","size":1234$extra}"""
        }
        return """{"tag_name":"$tag","name":"x","assets":[$list]}"""
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(AppUpdate.compareVersions("1.10", "1.9") > 0)
        assertTrue(AppUpdate.compareVersions("v1.2", "1.1") > 0)
        assertEquals(0, AppUpdate.compareVersions("1.1", "1.1.0"))
        assertTrue(AppUpdate.compareVersions("1.1", "1.1.1") < 0)
        assertEquals(0, AppUpdate.compareVersions("1.2-beta", "1.2"))
        assertTrue(AppUpdate.compareVersions("2", "1.99") > 0)
    }

    @Test
    fun parsesApkAndDigest() {
        val r = AppUpdate.parseRelease(release("v1.2", "HiWebRemoteDesk-1.2.apk" to ""","digest":"sha256:$sha""""))!!
        assertEquals("1.2", r.version)
        assertEquals("HiWebRemoteDesk-1.2.apk", r.apkName)
        assertEquals(1234L, r.size)
        assertEquals(sha, r.sha256)
    }

    @Test
    fun prefersNamedApkOverOthers() {
        val r = AppUpdate.parseRelease(release("v1.2", "other.apk" to "", "HiWebRemoteDesk-1.2.apk" to ""))!!
        assertEquals("HiWebRemoteDesk-1.2.apk", r.apkName)
        assertNull(r.sha256)
    }

    @Test
    fun releaseWithoutApkIsNull() {
        assertNull(AppUpdate.parseRelease(release("v1.2", "notes.txt" to "")))
        assertNotNull(AppUpdate.parseRelease(release("v1.2", "x.apk" to "")))
    }

    @Test
    fun checkDecidesByVersion() {
        val json = release("v1.2", "HiWebRemoteDesk-1.2.apk" to "")
        assertTrue(AppUpdate.check("1.1") { json } is UpdateCheck.Available)
        assertEquals(UpdateCheck.UpToDate, AppUpdate.check("1.2") { json })
        assertEquals(UpdateCheck.UpToDate, AppUpdate.check("1.3") { json })
    }

    @Test
    fun checkReportsProblems() {
        assertTrue(AppUpdate.check("1.1") { throw IOException("boom") } is UpdateCheck.Failed)
        assertTrue(AppUpdate.check("1.1") { "не json" } is UpdateCheck.Failed)
        assertTrue(AppUpdate.check("1.1") { release("v1.2") } is UpdateCheck.Failed)
        val limited = AppUpdate.check("1.1") { throw AppUpdate.UpdateHttpException("лимит") }
        assertEquals("лимит", (limited as UpdateCheck.Failed).message)
    }
}
