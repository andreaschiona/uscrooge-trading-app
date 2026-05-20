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
