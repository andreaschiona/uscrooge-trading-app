package com.uscrooge.app.executor

import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class OrderExecutor(
    private val apiClient: KrakenApiClient,
    private val signalDao: TradingSignalDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val config: TradingConfig
) {

    suspend fun executeSignal(signal: TradingSignal): Result<Order> {
        return try {
            // Update signal status to executing
            signalDao.updateSignal(signal.copy(status = SignalStatus.EXECUTING))

            when (signal.type) {
                SignalType.BUY -> executeBuyOrder(signal)
                SignalType.SELL -> executeSellOrder(signal)
                SignalType.HOLD -> Result.failure(Exception("Cannot execute HOLD signal"))
            }
        } catch (e: Exception) {
            // Update signal status to failed
            signalDao.updateSignal(signal.copy(status = SignalStatus.FAILED))
            Result.failure(e)
        }
    }

    private suspend fun executeBuyOrder(signal: TradingSignal): Result<Order> {
        val pair = TradingPair.fromString(signal.pair)

        // Get current price to check slippage
        val tickerResult = apiClient.getTicker(pair)
        if (tickerResult.isFailure) {
            return Result.failure(tickerResult.exceptionOrNull()!!)
        }

        val currentPrice = tickerResult.getOrNull()!!.ask
        val slippage = abs((currentPrice - signal.suggestedPrice) / signal.suggestedPrice) * 100

        // Check slippage
        if (slippage > config.maxSlippagePercent) {
            return Result.failure(Exception("Slippage too high: ${String.format("%.2f", slippage)}%"))
        }

        // Calculate volume in base currency
        val volume = signal.suggestedAmount / currentPrice

        // Validate order
        val validateResult = apiClient.addOrder(
            pair = pair,
            type = OrderSide.BUY,
            volume = volume,
            orderType = OrderType.MARKET,
            validate = true
        )

        if (validateResult.isFailure) {
            return Result.failure(validateResult.exceptionOrNull()!!)
        }

        // Execute order
        val orderResult = apiClient.addOrder(
            pair = pair,
            type = OrderSide.BUY,
            volume = volume,
            orderType = OrderType.MARKET,
            validate = false
        )

        if (orderResult.isFailure) {
            return Result.failure(orderResult.exceptionOrNull()!!)
        }

        val orderId = orderResult.getOrNull()!!

        // Wait a bit for order to be processed
        delay(2000)

        // Create order record
        val order = Order(
            orderId = orderId,
            pair = signal.pair,
            type = OrderType.MARKET,
            side = OrderSide.BUY,
            price = currentPrice,
            amount = volume,
            cost = signal.suggestedAmount,
            fee = signal.suggestedAmount * 0.0026,  // Kraken's typical fee
            status = OrderStatus.OPEN,
            createdAt = System.currentTimeMillis(),
            executedAt = System.currentTimeMillis(),
            signalId = signal.id
        )

        orderDao.insertOrder(order)

        // Create or update position
        val existingPosition = positionDao.getOpenPositionByPair(signal.pair)

        if (existingPosition != null) {
            // Add to existing position
            val newAmount = existingPosition.amount + volume
            val newTotalInvested = existingPosition.totalInvested + signal.suggestedAmount
            val newAveragePrice = newTotalInvested / newAmount

            val updatedPosition = existingPosition.copy(
                amount = newAmount,
                averageEntryPrice = newAveragePrice,
                totalInvested = newTotalInvested,
                currentPrice = currentPrice,
                currentValue = newAmount * currentPrice,
                unrealizedPnL = (newAmount * currentPrice) - newTotalInvested,
                unrealizedPnLPercent = (((newAmount * currentPrice) - newTotalInvested) / newTotalInvested) * 100,
                updatedAt = System.currentTimeMillis()
            )

            positionDao.updatePosition(updatedPosition)
        } else {
            // Create new position
            val position = Position(
                pair = signal.pair,
                amount = volume,
                averageEntryPrice = currentPrice,
                currentPrice = currentPrice,
                totalInvested = signal.suggestedAmount,
                currentValue = volume * currentPrice,
                unrealizedPnL = 0.0,
                unrealizedPnLPercent = 0.0,
                openedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isOpen = true
            )

            positionDao.insertPosition(position)
        }

        // Update signal
        signalDao.updateSignal(
            signal.copy(
                status = SignalStatus.EXECUTED,
                executedAt = System.currentTimeMillis(),
                executedPrice = currentPrice,
                orderId = orderId
            )
        )

        return Result.success(order)
    }

    private suspend fun executeSellOrder(signal: TradingSignal): Result<Order> {
        val pair = TradingPair.fromString(signal.pair)

        // Get existing position
        val position = positionDao.getOpenPositionByPair(signal.pair)
            ?: return Result.failure(Exception("No open position for ${signal.pair}"))

        // Get current price
        val tickerResult = apiClient.getTicker(pair)
        if (tickerResult.isFailure) {
            return Result.failure(tickerResult.exceptionOrNull()!!)
        }

        val currentPrice = tickerResult.getOrNull()!!.bid

        // Sell entire position
        val volume = position.amount

        // Validate order
        val validateResult = apiClient.addOrder(
            pair = pair,
            type = OrderSide.SELL,
            volume = volume,
            orderType = OrderType.MARKET,
            validate = true
        )

        if (validateResult.isFailure) {
            return Result.failure(validateResult.exceptionOrNull()!!)
        }

        // Execute order
        val orderResult = apiClient.addOrder(
            pair = pair,
            type = OrderSide.SELL,
            volume = volume,
            orderType = OrderType.MARKET,
            validate = false
        )

        if (orderResult.isFailure) {
            return Result.failure(orderResult.exceptionOrNull()!!)
        }

        val orderId = orderResult.getOrNull()!!

        // Wait for order processing
        delay(2000)

        val totalValue = volume * currentPrice
        val fee = totalValue * 0.0026

        // Create order record
        val order = Order(
            orderId = orderId,
            pair = signal.pair,
            type = OrderType.MARKET,
            side = OrderSide.SELL,
            price = currentPrice,
            amount = volume,
            cost = totalValue,
            fee = fee,
            status = OrderStatus.CLOSED,
            createdAt = System.currentTimeMillis(),
            executedAt = System.currentTimeMillis(),
            signalId = signal.id
        )

        orderDao.insertOrder(order)

        // Close position
        val realizedPnL = totalValue - position.totalInvested - fee
        val closedPosition = position.copy(
            currentPrice = currentPrice,
            currentValue = totalValue,
            unrealizedPnL = realizedPnL,
            realizedPnL = realizedPnL,
            isOpen = false,
            closedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        positionDao.updatePosition(closedPosition)

        // Update signal
        signalDao.updateSignal(
            signal.copy(
                status = SignalStatus.EXECUTED,
                executedAt = System.currentTimeMillis(),
                executedPrice = currentPrice,
                orderId = orderId
            )
        )

        return Result.success(order)
    }

    suspend fun ignoreSignal(signal: TradingSignal) {
        signalDao.updateSignal(signal.copy(status = SignalStatus.IGNORED))
    }

    suspend fun cancelOrder(orderId: String): Result<Boolean> {
        return try {
            val result = apiClient.cancelOrder(orderId)
            if (result.isSuccess) {
                // Update order status
                val order = orderDao.getOrderById(orderId)
                order?.let {
                    orderDao.updateOrder(it.copy(status = OrderStatus.CANCELED))
                }
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePositionPrices() {
        val positions = positionDao.getOpenPositions().first()

        positions.forEach { position ->
            try {
                val pair = TradingPair.fromString(position.pair)
                val tickerResult = apiClient.getTicker(pair)

                if (tickerResult.isSuccess) {
                    val currentPrice = tickerResult.getOrNull()!!.lastTrade
                    val updatedPosition = position.calculateCurrentValue(currentPrice)
                    positionDao.updatePosition(updatedPosition)
                }
            } catch (e: Exception) {
                // Log error but continue with other positions
            }
        }
    }
}
