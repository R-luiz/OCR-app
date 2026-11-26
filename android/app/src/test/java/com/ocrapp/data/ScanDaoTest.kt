package com.ocrapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: OcrDatabase
    private lateinit var dao: ScanDao
    private lateinit var repository: ScanRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, OcrDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.scanDao()
        repository = ScanRepository(dao, ThumbnailStore(context))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert then read back round-trips every field`() = runTest {
        val id = dao.insert(scan(title = "Invoice", markdown = "| a | b |", notice = "page 2 empty"))

        val stored = dao.getById(id)!!

        assertEquals("Invoice", stored.title)
        assertEquals("| a | b |", stored.markdown)
        assertEquals(EngineType.DOCUMENT, stored.engineType)
        assertEquals(3, stored.pageCount)
        assertEquals("page 2 empty", stored.notice)
    }

    @Test
    fun `history lists newest first`() = runTest {
        dao.insert(scan(title = "older", createdAt = 1_000))
        dao.insert(scan(title = "newer", createdAt = 2_000))

        assertEquals(listOf("newer", "older"), titles(""))
    }

    @Test
    fun `search matches title and body`() = runTest {
        dao.insert(scan(title = "Receipt", plainText = "total 42 euros"))
        dao.insert(scan(title = "Notes", plainText = "grocery list"))

        assertEquals(listOf("Receipt"), titles("euros"))
        assertEquals(listOf("Notes"), titles("Note"))
        assertEquals(emptyList<String>(), titles("nothing here"))
    }

    // % and _ are LIKE wildcards. Typed into the search box they are just characters,
    // and "100%" must not match every scan that mentions 100.
    @Test
    fun `search takes wildcard characters literally`() = runTest {
        dao.insert(scan(title = "Discount", plainText = "100% off"))
        dao.insert(scan(title = "Invoice", plainText = "100 euros"))
        dao.insert(scan(title = "Snake", plainText = "ref a_b"))
        dao.insert(scan(title = "Plain", plainText = "ref axb"))

        assertEquals(listOf("Discount"), titles("100%"))
        assertEquals(listOf("Snake"), titles("a_b"))
    }

    // The list reads a short opening of each text, never the whole body.
    @Test
    fun `summary carries only the opening of the text`() = runTest {
        dao.insert(scan(plainText = "\n\n  Heading line\n" + "x".repeat(5_000)))

        val preview = repository.observe("").first().single().preview

        assertEquals(200, preview.length)
        assertEquals("Heading line", preview.lineSequence().first())
    }

    @Test
    fun `snippet goes on from where the title stops`() {
        val summary = summary(title = "Heading line", preview = "Heading line\nrest of\n the text")

        assertEquals("rest of the text", summary.snippet)
    }

    @Test
    fun `snippet of a renamed scan starts at the top of the text`() {
        val summary = summary(title = "My invoice", preview = "Heading line\nrest")

        assertEquals("Heading line rest", summary.snippet)
    }

    @Test
    fun `editing markdown regenerates the searchable text`() = runTest {
        val id = dao.insert(scan(plainText = "old", markdown = "# old"))

        repository.updateContent(id, "# New heading", isMarkdown = true)

        val stored = dao.getById(id)!!
        assertEquals("# New heading", stored.markdown)
        assertEquals("New heading", stored.plainText)
        assertEquals(listOf("Scan"), titles("New heading"))
    }

    @Test
    fun `editing a quick scan leaves it without markdown`() = runTest {
        val id = dao.insert(scan(plainText = "befor", markdown = null))

        repository.updateContent(id, "before", isMarkdown = false)

        val stored = dao.getById(id)!!
        assertEquals("before", stored.plainText)
        assertNull(stored.markdown)
    }

    @Test
    fun `rename changes only the title`() = runTest {
        val id = dao.insert(scan(title = "Scan", plainText = "body"))

        repository.rename(id, "Contract")

        val stored = dao.getById(id)!!
        assertEquals("Contract", stored.title)
        assertEquals("body", stored.plainText)
    }

    @Test
    fun `delete removes the row`() = runTest {
        val id = dao.insert(scan())

        repository.delete(id)

        assertNull(dao.getById(id))
        assertEquals(emptyList<String>(), titles(""))
    }

    @Test
    fun `unknown engine name falls back to quick`() {
        assertEquals(EngineType.QUICK, scan().copy(engine = "SOMETHING_ELSE").engineType)
    }

    // Upgrading the app must keep the history a user already has. Room refuses to open a
    // database whose migrated shape differs from the entities, so reading through it
    // afterwards checks the migration against the real schema as well as the data.
    @Test
    fun `a version 1 database survives the upgrade with its scans`() = runTest {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
            .use { v1 ->
                V1_SCHEMA.forEach(v1::execSQL)
                v1.execSQL(
                    "INSERT INTO scans (createdAt, title, engine, plainText, markdown, thumbnailPath, pageCount) " +
                        "VALUES (1700000000000, 'Old scan', 'QUICK', 'kept text', NULL, NULL, 2)",
                )
                v1.version = 1
            }

        val upgraded = Room.databaseBuilder(context, OcrDatabase::class.java, name)
            .addMigrations(OcrDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val stored = upgraded.scanDao().observeSummaries("").first().single()
            val full = upgraded.scanDao().getById(stored.id)!!

            assertEquals("Old scan", full.title)
            assertEquals("kept text", full.plainText)
            assertEquals(2, full.pageCount)
            assertNull(full.notice)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun titles(query: String): List<String> =
        repository.observe(query).first().map { it.title }

    private fun scan(
        title: String = "Scan",
        plainText: String = "recognized text",
        markdown: String? = null,
        createdAt: Long = 1_700_000_000_000,
        notice: String? = null,
    ) = ScanEntity(
        createdAt = createdAt,
        title = title,
        engine = EngineType.DOCUMENT.name,
        plainText = plainText,
        markdown = markdown,
        thumbnailPath = null,
        pageCount = 3,
        notice = notice,
    )

    private fun summary(title: String, preview: String) = ScanSummary(
        id = 1,
        createdAt = 0,
        title = title,
        engine = EngineType.QUICK.name,
        preview = preview,
        thumbnailPath = null,
        pageCount = 1,
    )

    private companion object {
        /** The statements Room 2.6 generated for schema version 1 (see schemas/…/1.json). */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `scans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `engine` TEXT NOT NULL, " +
                "`plainText` TEXT NOT NULL, `markdown` TEXT, `thumbnailPath` TEXT, " +
                "`pageCount` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                "VALUES(42, '5f0c0387389c77c36a635b564f1ca216')",
        )
    }
}
