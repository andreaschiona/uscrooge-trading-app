package com.uscrooge.app.data.local

import androidx.room.*
import com.uscrooge.app.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TradingSignalDao {
    @Query("SELECT * FROM trading_signals ORDER BY timestamp DESC")
    fun getAllSignals(): Flow<List<TradingSignal>>

    @Query("SELECT * FROM trading_signals WHERE status = :status ORDER BY timestamp DESC")
    fun getSignalsByStatus(status: SignalStatus): Flow<List<TradingSignal>>

    @Query("SELECT * FROM trading_signals WHERE status = :status ORDER BY timestamp DESC")
    suspend fun getSignalsByStatusList(status: SignalStatus): List<TradingSignal>

    @Query("SELECT * FROM trading_signals WHERE pair = :pair AND status = :status ORDER BY timestamp DESC LIMIT 1")
    suspend fun getPendingSignalByPair(pair: String, status: SignalStatus): TradingSignal?

    @Query("SELECT * FROM trading_signals WHERE id = :id")
    suspend fun getSignalById(id: Long): TradingSignal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignal(signal: TradingSignal): Long

    @Update
    suspend fun updateSignal(signal: TradingSignal)

    @Delete
    suspend fun deleteSignal(signal: TradingSignal)

    @Query("DELETE FROM trading_signals WHERE timestamp < :cutoffTime AND status IN ('EXECUTED', 'IGNORED', 'EXPIRED')")
    suspend fun deleteOldSignals(cutoffTime: Long)
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    fun getAllOrders(): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE status = :status ORDER BY createdAt DESC")
    fun getOrdersByStatus(status: OrderStatus): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE orderId = :orderId")
    suspend fun getOrderById(orderId: String): Order?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: Order)

    @Update
    suspend fun updateOrder(order: Order)

    @Delete
    suspend fun deleteOrder(order: Order)

    @Query("SELECT * FROM orders WHERE pair = :pair ORDER BY createdAt DESC LIMIT 10")
    fun getOrdersByPair(pair: String): Flow<List<Order>>

    @Query("SELECT COUNT(*) FROM orders WHERE createdAt >= :sinceTimestamp AND status IN ('OPEN', 'CLOSED')")
    suspend fun getTradeCountSince(sinceTimestamp: Long): Int
}

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions WHERE isOpen = 1 ORDER BY openedAt DESC")
    fun getOpenPositions(): Flow<List<Position>>

    @Query("SELECT * FROM positions ORDER BY openedAt DESC")
    fun getAllPositions(): Flow<List<Position>>

    @Query("SELECT * FROM positions WHERE pair = :pair AND isOpen = 1 LIMIT 1")
    suspend fun getOpenPositionByPair(pair: String): Position?

    @Query("SELECT * FROM positions WHERE id = :id")
    suspend fun getPositionById(id: Long): Position?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPosition(position: Position): Long

    @Update
    suspend fun updatePosition(position: Position)

    @Delete
    suspend fun deletePosition(position: Position)

    @Query("SELECT COUNT(*) FROM positions WHERE isOpen = 1")
    suspend fun getOpenPositionsCount(): Int
}

@Dao
interface TradeJournalDao {
    @Query("SELECT * FROM trade_journal ORDER BY exitTime DESC")
    fun getAllEntries(): Flow<List<TradeJournalEntry>>

    @Query("SELECT * FROM trade_journal WHERE pair = :pair ORDER BY exitTime DESC")
    fun getEntriesByPair(pair: String): Flow<List<TradeJournalEntry>>

    @Query("SELECT * FROM trade_journal WHERE side = :side ORDER BY exitTime DESC")
    fun getEntriesBySide(side: OrderSide): Flow<List<TradeJournalEntry>>

    @Query("SELECT * FROM trade_journal WHERE id = :id")
    suspend fun getEntryById(id: Long): TradeJournalEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: TradeJournalEntry): Long

    @Update
    suspend fun updateEntry(entry: TradeJournalEntry)

    @Delete
    suspend fun deleteEntry(entry: TradeJournalEntry)

    @Query("SELECT COUNT(*) FROM trade_journal WHERE exitTime >= :sinceTimestamp")
    suspend fun getEntryCountSince(sinceTimestamp: Long): Int

    @Query("SELECT SUM(profitLoss) FROM trade_journal WHERE exitTime >= :sinceTimestamp")
    suspend fun getTotalPnLSince(sinceTimestamp: Long): Double?

    @Query("SELECT * FROM trade_journal ORDER BY profitLoss DESC LIMIT 10")
    fun getTopWinners(): Flow<List<TradeJournalEntry>>

    @Query("SELECT * FROM trade_journal ORDER BY profitLoss ASC LIMIT 10")
    fun getTopLosers(): Flow<List<TradeJournalEntry>>

    @Query("SELECT pair, COUNT(*) as tradeCount, AVG(profitLossPercent) as avgPnLPercent FROM trade_journal GROUP BY pair ORDER BY avgPnLPercent DESC")
    fun getPairStatistics(): Flow<List<PairTradeStats>>
}

data class PairTradeStats(
    val pair: String,
    val tradeCount: Int,
    val avgPnLPercent: Double
)
