package com.uscrooge.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TradingDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate7to8_preservesData() {
        var db = helper.createDatabase(TEST_DB, 7)

        db.execSQL("""
            INSERT INTO positions (pair, amount, averageEntryPrice, currentPrice, peakPrice,
                totalInvested, currentValue, unrealizedPnL, unrealizedPnLPercent,
                openedAt, updatedAt, isOpen, broker)
            VALUES ('XBT/EUR', 0.5, 50000.0, 51000.0, 51000.0,
                25000.0, 25500.0, 500.0, 2.0,
                1000000, 1000000, 1, 'Kraken')
        """.trimIndent())

        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        val cursor = db.query("SELECT pyramidLevel FROM positions WHERE pair = 'XBT/EUR'")
        assert(cursor.count == 1) { "Expected 1 row" }
        cursor.moveToFirst()
        val pyramidLevel = cursor.getInt(0)
        assert(pyramidLevel == 0) { "Expected pyramidLevel=0, got $pyramidLevel" }
        cursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate6to7_noSchemaChange() {
        var db = helper.createDatabase(TEST_DB, 6)

        db.execSQL("""
            INSERT INTO positions (pair, amount, averageEntryPrice, currentPrice, peakPrice,
                totalInvested, currentValue, unrealizedPnL, unrealizedPnLPercent,
                openedAt, updatedAt, isOpen, broker)
            VALUES ('ETH/EUR', 10.0, 3000.0, 3100.0, 3100.0,
                30000.0, 31000.0, 1000.0, 3.33,
                2000000, 2000000, 1, 'Kraken')
        """.trimIndent())

        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        val cursor = db.query("SELECT broker FROM positions WHERE pair = 'ETH/EUR'")
        assert(cursor.count == 1) { "Expected 1 row" }
        cursor.moveToFirst()
        val broker = cursor.getString(0)
        assert(broker == "Kraken") { "Expected broker=Kraken, got $broker" }
        cursor.close()

        db.close()
    }
}
