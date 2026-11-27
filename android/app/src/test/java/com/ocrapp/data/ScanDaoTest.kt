package com.ocrapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ocrapp.ocr.EngineType
import com.ocrapp.ocr.OcrOutput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

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
        repository = ScanRepository(dao, ThumbnailStore(context), KeptPageStore(context))
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

    // SQLite's LIKE folds neither accents nor the case of non-ASCII letters, and OCR
    // drops or adds accents, so "ecole" has to find "École" and "état" has to find "ÉTAT".
    @Test
    fun `search ignores accents and the case of accented letters`() = runTest {
        dao.insert(scan(title = "École", plainText = "inscription"))
        dao.insert(scan(title = "Bail", plainText = "ÉTAT DES LIEUX"))

        assertEquals(listOf("École"), titles("ecole"))
        assertEquals(listOf("École"), titles("ÉCOLE"))
        assertEquals(listOf("Bail"), titles("état"))
        assertEquals(listOf("Bail"), titles("Etat des"))
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
        assertEquals("New heading", repository.observe("").first().single().preview)
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
    fun `saving the text drops the draft it came from`() = runTest {
        val id = dao.insert(scan(plainText = "old"))
        repository.saveDraft(id, "half typed")
        assertEquals("half typed", dao.getById(id)!!.draftText)

        repository.updateContent(id, "final", isMarkdown = false)

        assertNull(dao.getById(id)!!.draftText)
    }

    @Test
    fun `a retry replaces the result, keeps the title and drops the draft`() = runTest {
        val id = dao.insert(
            scan(title = "Invoice", plainText = "quick text", notice = "fell back")
                .copy(engine = EngineType.QUICK.name),
        )
        repository.saveDraft(id, "corrected quick text")

        repository.replaceContent(
            id,
            OcrOutput(plainText = "Total 42", markdown = "**Total** 42", engine = EngineType.DOCUMENT, pageCount = 2),
            notice = null,
        )

        val stored = dao.getById(id)!!
        assertEquals("Invoice", stored.title)
        assertEquals(EngineType.DOCUMENT, stored.engineType)
        assertEquals("**Total** 42", stored.markdown)
        assertEquals("Total 42", stored.plainText)
        assertEquals(2, stored.pageCount)
        assertNull(stored.notice)
        assertNull(stored.draftText)
        assertEquals(listOf("Invoice"), titles("total"))
        assertEquals("Total 42", repository.observe("").first().single().preview)
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
    fun `a renamed scan is found by its new title`() = runTest {
        val id = dao.insert(scan(title = "Scan", plainText = "body"))

        repository.rename(id, "Contrat signé")

        assertEquals(listOf("Contrat signé"), titles("signe"))
        assertEquals(emptyList<String>(), titles("Scan"))
    }

    @Test
    fun `delete removes the row`() = runTest {
        val id = dao.insert(scan())

        repository.delete(id)

        assertNull(dao.getById(id))
        assertEquals(emptyList<String>(), titles(""))
    }

    @Test
    fun `kept pages come back in page order with the extension the loader goes by`() = runTest {
        val id = dao.insert(scan())
        val sources = listOf(
            source("photo.PNG", "first"),
            source("statement.pdf", "second"),
            source("noextension", "third"),
        ) + (4..11).map { source("page$it.jpg", "page $it") }

        assertTrue(repository.keepPages(id, sources))

        val kept = repository.keptPages(id)
        assertEquals(sources.size, kept.size)
        assertEquals(listOf("0.png", "1.pdf", "2.jpg"), kept.take(3).map { File(it.path!!).name })
        assertEquals(
            listOf("first", "second", "third") + (4..11).map { "page $it" },
            kept.map { File(it.path!!).readText() },
        )
    }

    @Test
    fun `observing kept pages follows keep and drop`() = runTest {
        val id = dao.insert(scan())
        assertFalse(repository.observeHasKeptPages(id).first())

        repository.keepPages(id, listOf(source("a.jpg", "a")))
        assertTrue(repository.observeHasKeptPages(id).first())

        repository.dropKeptPages(id)
        assertFalse(repository.observeHasKeptPages(id).first())
        assertEquals(emptyList<Uri>(), repository.keptPages(id))
    }

    @Test
    fun `a page that cannot be read keeps nothing`() = runTest {
        val id = dao.insert(scan())
        val missing = Uri.fromFile(File(context.cacheDir, "gone.jpg"))

        assertFalse(repository.keepPages(id, listOf(source("a.jpg", "a"), missing)))

        assertEquals(emptyList<Uri>(), repository.keptPages(id))
        assertFalse(repository.observeHasKeptPages(id).first())
    }

    @Test
    fun `deleting a scan drops its kept pages`() = runTest {
        val id = dao.insert(scan())
        repository.keepPages(id, listOf(source("a.jpg", "a")))

        repository.delete(id)

        assertEquals(emptyList<Uri>(), repository.keptPages(id))
    }

    @Test
    fun `sweep drops only pages kept longer than the limit`() = runTest {
        val old = dao.insert(scan(title = "old"))
        val recent = dao.insert(scan(title = "recent"))
        repository.keepPages(old, listOf(source("old.jpg", "old")))
        repository.keepPages(recent, listOf(source("recent.jpg", "recent")))
        val eightDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)
        assertTrue(File(context.filesDir, "kept_pages/$old").setLastModified(eightDaysAgo))

        repository.sweepKeptPages()

        assertEquals(emptyList<Uri>(), repository.keptPages(old))
        assertEquals(1, repository.keptPages(recent).size)
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
        createDatabase(name, version = 1, schema = V1_SCHEMA) { v1 ->
            v1.execSQL(
                "INSERT INTO scans (createdAt, title, engine, plainText, markdown, thumbnailPath, pageCount) " +
                    "VALUES (1700000000000, 'Old scan', 'QUICK', 'kept text', NULL, NULL, 2)",
            )
        }

        openUpgraded(name) { upgraded ->
            val stored = upgraded.scanDao().observeSummaries().first().single()
            val full = upgraded.scanDao().getById(stored.id)!!

            assertEquals("Old scan", full.title)
            assertEquals("kept text", full.plainText)
            assertEquals(2, full.pageCount)
            assertNull(full.notice)
            assertEquals("kept text", stored.preview)
        }
    }

    // Version 3 adds the list preview and the accent-folded search columns; rows from
    // before have to get them filled in, or the history shows blank snippets and search
    // finds nothing until each scan is edited.
    @Test
    fun `a version 2 database gets its previews and search columns filled in`() = runTest {
        val name = "migration-v2-test.db"
        val body = "\n\t  ÉTAT des lieux\n" + "é".repeat(300)
        createDatabase(name, version = 2, schema = V2_SCHEMA) { v2 ->
            v2.execSQL(
                "INSERT INTO scans (createdAt, title, engine, plainText, markdown, thumbnailPath, pageCount, notice) " +
                    "VALUES (1700000000000, 'École', 'DOCUMENT', ?, '# ÉTAT', NULL, 1, 'kept notice')",
                arrayOf(body),
            )
            v2.execSQL(
                "INSERT INTO scans (createdAt, title, engine, plainText, markdown, thumbnailPath, pageCount, notice) " +
                    "VALUES (1700000001000, 'Receipt', 'QUICK', 'total', NULL, NULL, 1, NULL)",
            )
        }

        openUpgraded(name) { upgraded ->
            val scans = upgraded.scanDao()
            assertEquals(listOf("Receipt", "École"), scans.observeSummaries().first().map { it.title })

            val school = scans.searchSummaries("%ecole%").first().single()
            assertEquals(previewOf(body), school.preview)
            assertEquals(listOf("École"), scans.searchSummaries("%etat des%").first().map { it.title })

            val full = scans.getById(school.id)!!
            assertEquals(searchKey("École"), full.searchTitle)
            assertEquals(searchKey(body), full.searchText)
            assertEquals("kept notice", full.notice)
            assertNull(full.draftText)
        }
    }

    private fun createDatabase(
        name: String,
        version: Int,
        schema: List<String>,
        fill: (SQLiteDatabase) -> Unit,
    ) {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
            .use { old ->
                schema.forEach(old::execSQL)
                fill(old)
                old.version = version
            }
    }

    private suspend fun openUpgraded(name: String, check: suspend (OcrDatabase) -> Unit) {
        val upgraded = Room.databaseBuilder(context, OcrDatabase::class.java, name)
            .addMigrations(OcrDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            check(upgraded)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun titles(query: String): List<String> =
        repository.observe(query).first().map { it.title }

    private fun source(name: String, content: String): Uri =
        Uri.fromFile(File(context.cacheDir, name).apply { writeText(content) })

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

        /** The statements Room 2.6 generated for schema version 2 (see schemas/…/2.json). */
        val V2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `scans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `engine` TEXT NOT NULL, " +
                "`plainText` TEXT NOT NULL, `markdown` TEXT, `thumbnailPath` TEXT, " +
                "`pageCount` INTEGER NOT NULL, `notice` TEXT)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                "VALUES(42, 'a9ecbc1fd48ad4516c16c0648a60fd95')",
        )
    }
}
