package com.uscrooge.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uscrooge.app.data.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditLogDaoTest {

    private lateinit var db: TradingDatabase
    private lateinit var dao: AuditLogDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.auditLogDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndGetAllEntries() = runBlocking {
        dao.insertEntry(createTestEntry())
        val entries = dao.getAllEntries().first()
        assertEquals(1, entries.size)
    }

    @Test
    fun getEntriesByCategory() = runBlocking {
        dao.insertEntry(createTestEntry(category = "SIGNAL"))
        dao.insertEntry(createTestEntry(category = "ORDER"))
        val signal = dao.getEntriesByCategory("ORDER").first()
        assertEquals(1, signal.size)
    }

    @Test
    fun getEntriesBySeverity() = runBlocking {
        dao.insertEntry(createTestEntry(severity = "ERROR"))
        dao.insertEntry(createTestEntry(severity = "INFO"))
        val errors = dao.getEntriesBySeverity("ERROR").first()
        assertEquals(1, errors.size)
    }

    @Test
    fun getEntriesSince() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertEntry(createTestEntry(timestamp = now))
        dao.insertEntry(createTestEntry(timestamp = now - 100000))
        val recent = dao.getEntriesSince(now - 50000).first()
        assertEquals(1, recent.size)
    }

    @Test
    fun deleteOldEntries() = runBlocking {
        dao.insertEntry(createTestEntry(timestamp = 1000L))
        dao.insertEntry(createTestEntry(timestamp = System.currentTimeMillis()))
        dao.deleteOldEntries(50000L)
        val entries = dao.getAllEntries().first()
        assertEquals(1, entries.size)
    }

    @Test
    fun getEntryCountSince() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertEntry(createTestEntry(timestamp = now))
        dao.insertEntry(createTestEntry(timestamp = now - 100000))
        val count = dao.getEntryCountSince(now - 50000)
        assertEquals(1, count)
    }

    @Test
    fun multipleEntries() = runBlocking {
        repeat(5) { i ->
            dao.insertEntry(createTestEntry(category = "CAT_$i", timestamp = System.currentTimeMillis() + i))
        }
        val entries = dao.getAllEntries().first()
        assertEquals(5, entries.size)
    }

    private fun createTestEntry(
        timestamp: Long = System.currentTimeMillis(),
        category: String = "TEST",
        severity: String = "INFO"
    ): AuditLogEntry = AuditLogEntry(
        timestamp = timestamp,
        category = category,
        action = "test_action",
        details = "Test audit log entry",
        severity = severity
    )
}
