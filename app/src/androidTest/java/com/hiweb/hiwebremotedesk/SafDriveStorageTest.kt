package com.hiweb.hiwebremotedesk

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hiweb.hiwebremotedesk.data.SafDriveStorage
import com.hiweb.hiwebremotedesk.data.SafTree
import com.hiweb.hiwebremotedesk.debug.TestDocumentsProvider
import com.hiweb.hiwebremotedesk.rdp.DriveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Проверка SAF-хранилища через настоящий DocumentsContract и тестовый провайдер документов. */
@RunWith(AndroidJUnit4::class)
class SafDriveStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var base: File
    private lateinit var storage: SafDriveStorage

    @Before
    fun setUp() {
        base = File(context.cacheDir, TestDocumentsProvider.DIR_NAME)
        base.deleteRecursively()
        base.mkdirs()
        val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
        storage = SafDriveStorage(context, tree, readOnly = false)
    }

    private fun status(block: () -> Unit): Long = try {
        block()
        fail("ожидалась ошибка")
        0L
    } catch (e: DriveException) {
        e.status
    }

    @Test
    fun root_is_accessible_and_empty() {
        val tree = SafTree(context, DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID))
        assertTrue(tree.isAccessible())
        assertEquals("root", tree.displayName())
        assertTrue(storage.stat(emptyList())!!.isDirectory)
        assertTrue(storage.list(emptyList()).isEmpty())
        assertNull(storage.stat(listOf("missing")))
    }

    @Test
    fun create_write_read_with_offsets_and_resize() {
        storage.createFile(listOf("a.txt"))
        assertEquals(listOf("a.txt"), storage.list(emptyList()).map { it.name })

        val h = storage.open(listOf("a.txt"), write = true, truncate = false)
        h.write(0, "hello world".toByteArray())
        h.write(6, "WORLD".toByteArray())
        assertEquals(11L, h.size())
        assertEquals("hello WORLD", String(h.read(0, 100)))
        assertEquals("WORLD", String(h.read(6, 5)))
        assertEquals(0, h.read(50, 10).size)
        h.setSize(5)
        assertEquals("hello", String(h.read(0, 100)))
        h.setSize(8)
        assertEquals(8, h.read(0, 100).size)
        h.close()
        assertEquals(8L, File(base, "a.txt").length())
        assertEquals(8L, storage.stat(listOf("a.txt"))!!.size) // кэш сброшен после записи

        storage.open(listOf("a.txt"), write = true, truncate = true).close()
        assertEquals(0L, File(base, "a.txt").length())
    }

    @Test
    fun created_file_keeps_requested_name_with_extension() {
        storage.createFile(listOf("Отчёт.final.pdf"))
        storage.createFile(listOf("noext"))
        storage.createFile(listOf("data.unknownext"))
        assertEquals(setOf("Отчёт.final.pdf", "noext", "data.unknownext"), storage.list(emptyList()).map { it.name }.toSet())
    }

    @Test
    fun directories_nested_listing_and_case_insensitive_lookup() {
        storage.createDirectory(listOf("Docs"))
        storage.createFile(listOf("Docs", "Readme.TXT"))
        assertTrue(storage.stat(listOf("docs"))!!.isDirectory)
        assertEquals("Readme.TXT", storage.stat(listOf("DOCS", "readme.txt"))!!.name)
        assertEquals(listOf("Readme.TXT"), storage.list(listOf("Docs")).map { it.name })
        assertNull(storage.stat(listOf("Docs", "Readme.TXT", "x")))
    }

    @Test
    fun creating_in_missing_folder_fails() {
        assertEquals(0xC000003AL, status { storage.createFile(listOf("nope", "f.txt")) })
    }

    @Test
    fun rename_in_place_and_move_between_folders() {
        storage.createDirectory(listOf("src"))
        storage.createDirectory(listOf("dst"))
        storage.createFile(listOf("src", "a.txt"))
        storage.open(listOf("src", "a.txt"), write = true, truncate = false).also { it.write(0, "x".toByteArray()) }.close()

        storage.rename(listOf("src", "a.txt"), listOf("src", "b.txt"), replace = false)
        assertNull(storage.stat(listOf("src", "a.txt")))
        assertNotNull(storage.stat(listOf("src", "b.txt")))

        storage.rename(listOf("src", "b.txt"), listOf("dst", "c.txt"), replace = false)
        assertFalse(File(base, "src/b.txt").exists())
        assertEquals("x", File(base, "dst/c.txt").readText())

        // Тот же каталог, другой регистр
        storage.rename(listOf("dst", "c.txt"), listOf("dst", "C.TXT"), replace = false)
        assertEquals(listOf("C.TXT"), storage.list(listOf("dst")).map { it.name })
    }

    @Test
    fun rename_onto_existing_requires_replace() {
        storage.createFile(listOf("a.txt"))
        storage.createFile(listOf("b.txt"))
        storage.open(listOf("a.txt"), write = true, truncate = false).also { it.write(0, "A".toByteArray()) }.close()
        assertEquals(0xC0000035L, status { storage.rename(listOf("a.txt"), listOf("b.txt"), replace = false) })
        storage.rename(listOf("a.txt"), listOf("b.txt"), replace = true)
        assertEquals("A", File(base, "b.txt").readText())
        assertEquals(listOf("b.txt"), storage.list(emptyList()).map { it.name })
    }

    @Test
    fun delete_file_and_empty_directory_but_not_a_full_one() {
        storage.createDirectory(listOf("d"))
        storage.createFile(listOf("d", "f"))
        assertEquals(0xC0000101L, status { storage.delete(listOf("d")) })
        storage.delete(listOf("d", "f"))
        storage.delete(listOf("d"))
        assertTrue(storage.list(emptyList()).isEmpty())
        assertEquals(0xC0000034L, status { storage.delete(listOf("d")) })
    }

    @Test
    fun opening_a_directory_or_missing_file_fails() {
        storage.createDirectory(listOf("d"))
        assertEquals(0xC00000BAL, status { storage.open(listOf("d"), write = false, truncate = false) })
        assertEquals(0xC0000034L, status { storage.open(listOf("missing"), write = false, truncate = false) })
    }

    @Test
    fun space_is_reported() {
        val (total, free) = storage.space()
        assertTrue(total > 0 && free in 1..total)
    }

    @Test
    fun large_file_roundtrip() {
        storage.createFile(listOf("big.bin"))
        val data = ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }
        val h = storage.open(listOf("big.bin"), write = true, truncate = false)
        var pos = 0L
        while (pos < data.size) {
            val n = minOf(65536, (data.size - pos).toInt())
            h.write(pos, data.copyOfRange(pos.toInt(), pos.toInt() + n))
            pos += n
        }
        h.close()
        val r = storage.open(listOf("big.bin"), write = false, truncate = false)
        assertTrue(data.contentEquals(r.read(0, data.size)))
        r.close()
    }
}
