package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.AppPermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsTest {
    private val pkg = "com.hiweb.hiwebremotedesk"
    private val cls = "com.hiweb.hiwebremotedesk.KeyCaptureService"

    @Test
    fun serviceFoundInFullAndShortForm() {
        assertTrue(AppPermissions.isServiceEnabled("$pkg/$cls", pkg, cls))
        assertTrue(AppPermissions.isServiceEnabled("$pkg/.KeyCaptureService", pkg, cls))
    }

    @Test
    fun serviceFoundAmongOthers() {
        val list = "com.other/com.other.Service:$pkg/$cls:com.third/.Svc"
        assertTrue(AppPermissions.isServiceEnabled(list, pkg, cls))
    }

    @Test
    fun otherServicesAndEmptyListDoNotMatch() {
        assertFalse(AppPermissions.isServiceEnabled(null, pkg, cls))
        assertFalse(AppPermissions.isServiceEnabled("", pkg, cls))
        assertFalse(AppPermissions.isServiceEnabled("com.other/com.other.Service", pkg, cls))
        // Тот же пакет, но другая служба.
        assertFalse(AppPermissions.isServiceEnabled("$pkg/.OtherService", pkg, cls))
        // Чужой пакет с таким же коротким именем класса.
        assertFalse(AppPermissions.isServiceEnabled("com.other/.KeyCaptureService", pkg, cls))
    }
}
