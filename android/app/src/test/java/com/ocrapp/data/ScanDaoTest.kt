package com.ocrapp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ocrapp.ocr.EngineType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScanDaoTest {

    private lateinit var database: OcrDatabase
    private lateinit var dao: ScanDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OcrDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.scanDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert then read back round-trips every field`() = runTest {
        val id = dao.insert(scan(title = "Invoice", markdown = "| a | b |"))

        val stored = dao.getById(id)!!

        assertEquals("Invoice", stored.title)
        assertEquals("| a | b |", stored.markdown)
        assertEquals(EngineType.DOCUMENT, stored.engineType)
        assertEquals(3, stored.pageCount)
    }

    @Test
    fun `observeAll returns newest first`() = runTest {
        dao.insert(scan(title = "older", createdAt = 1_000))
        dao.insert(scan(title = "newer", createdAt = 2_000))

        val titles = dao.observeAll().first().map { it.title }

        assertEquals(listOf("newer", "older"), titles)
    }

    @Test
    fun `search matches title and body`() = runTest {
        dao.insert(scan(title = "Receipt", plainText = "total 42 euros"))
        dao.insert(scan(title = "Notes", plainText = "grocery list"))

        assertEquals(listOf("Receipt"), dao.search("euros").first().map { it.title })
        assertEquals(listOf("Notes"), dao.search("Note").first().map { it.title })
        assertEquals(emptyList<String>(), dao.search("nothing here").first().map { it.title })
    }

    @Test
    fun `update replaces stored text`() = runTest {
        val id = dao.insert(scan(plainText = "befor"))

        dao.update(dao.getById(id)!!.copy(plainText = "before"))

        assertEquals("before", dao.getById(id)!!.plainText)
    }

    @Test
    fun `delete removes the row`() = runTest {
        val id = dao.insert(scan())

        dao.delete(dao.getById(id)!!)

        assertNull(dao.getById(id))
        assertEquals(emptyList<ScanEntity>(), dao.observeAll().first())
    }

    @Test
    fun `unknown engine name falls back to quick`() {
        val stored = scan().copy(engine = "SOMETHING_ELSE")

        assertEquals(EngineType.QUICK, stored.engineType)
    }

    @Test
    fun `snippet uses the first non-blank line`() {
        val stored = scan(plainText = "\n\n  Heading line\nrest")

        assertEquals("Heading line", stored.snippet)
    }

    private fun scan(
        title: String = "Scan",
        plainText: String = "recognized text",
        markdown: String? = null,
        createdAt: Long = 1_700_000_000_000,
    ) = ScanEntity(
        createdAt = createdAt,
        title = title,
        engine = EngineType.DOCUMENT.name,
        plainText = plainText,
        markdown = markdown,
        thumbnailPath = null,
        pageCount = 3,
    )
}
