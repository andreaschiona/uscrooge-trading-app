package com.uscrooge.app.executor

import android.util.Log
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.api.retryWithBackoff
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.strategy.ExitSignal
import com.uscrooge.app.strategy.ExitUrgency
import com.uscrooge.app.strategy.TradingStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@Singleton
class OrderExecutor @Inject constructor(
    private val apiClient: KrakenApiClient,
    private val signalDao: TradingSignalDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val circuitBreaker: CircuitBreaker
) {

    @Volatile
    private var config: TradingConfig = TradingConfig()

    companion object {
        private const val TAG = "OrderExecutor"
        private const val ORDER_POLL_INTERVAL_MS = 2000L
        private const val ORDER_POLL_TIMEOUT_MS = 30000L
        private const val MAX_FILL_RETRIES = 5
    }

    /**
     * Updates the active [TradingConfig] used for slippage limits, fees and
     * sizing. Called by [com.uscrooge.app.di.BrokerRegistry] whenever the user
     * changes Settings. Must remain cheap and side-effect free.
     */
    fun updateConfig(newConfig: TradingConfig) {
        this.config = newConfig
    }

    /**
     * Checks whether a new trade is allowed by the circuit breaker.
     * Returns null if allowed, or a reason string if blocked.
     */
    suspend fun checkTradingAllowed(): String? {
        return circuitBreaker.checkTradingAllowed(config)
    }

    suspend fun executeSignal(signal: TradingSignal): Result<Order> {
        // Check circuit breaker before execution
        val blocked = circuitBreaker.checkTradingAllowed(config)
        if (blocked != null) {
            signalDao.updateSignal(signal.copy(status = SignalStatus.FAILED))
            return Result.failure(Exception("Trading blocked: $blocked"))
        }

        return try {
            // Update signal status to executing
            signalDao.updateSignal(signal.copy(status = SignalStatus.EXECUTING))

            val result = when (signal.type) {
                SignalType.BUY -> executeBuyOrder(signal)
                SignalType.SELL -> executeSellOrder(signal)
                SignalType.HOLD -> Result.failure(Exception("Cannot execute HOLD signal"))
            }

            if (result.isSuccess) {
                circuitBreaker.recordSuccess()
            } else {
                circuitBreaker.recordFailure()
            }

            result
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
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

        // Determine order type: MARKET for strong/urgent signals, LIMIT for moderate signals
        val useLimit = config.useLimitOrders && signal.strength < config.strongSignalThreshold
        val orderType = if (useLimit) OrderType.LIMIT else OrderType.MARKET
        val limitPrice = if (useLimit) {
            // Place limit slightly below current ask for better fill
            currentPrice * 0.999
        } else null

        // Validate order
        val validateResult = apiClient.addOrder(
            pair = pair,
            type = OrderSide.BUY,
            volume = volume,
            orderType = orderType,
            price = limitPrice,
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
            orderType = orderType,
            price = limitPrice,
            validate = false
        )

        if (orderResult.isFailure) {
            return Result.failure(orderResult.exceptionOrNull()!!)
        }

        val orderId = orderResult.getOrNull()!!

        val fillResult = waitForOrderFill(orderId, pair)

        val (executionPrice, actualVolume, actualFee, finalStatus) = if (fillResult.isSuccess) {
            val fillInfo = fillResult.getOrNull()!!
            if (fillInfo.status == OrderStatus.CLOSED) {
                Quadruple(fillInfo.executedPrice, fillInfo.executedVolume, fillInfo.fee, fillInfo.status)
            } else {
                Quadruple(limitPrice ?: currentPrice, volume, signal.suggestedAmount * 0.0026, OrderStatus.OPEN)
            }
        } else {
            Log.w(TAG, "Using fallback price for order $orderId: ${fillResult.exceptionOrNull()?.message}")
            Quadruple(limitPrice ?: currentPrice, volume, signal.suggestedAmount * 0.0026, OrderStatus.OPEN)
        }

        // Create order record
        val order = Order(
            orderId = orderId,
            pair = signal.pair,
            type = orderType,
            side = OrderSide.BUY,
            price = executionPrice,
            amount = actualVolume,
            cost = actualVolume * executionPrice,
            fee = actualFee,
            status = finalStatus,
            createdAt = System.currentTimeMillis(),
            executedAt = if (finalStatus == OrderStatus.CLOSED) System.currentTimeMillis() else null,
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
                currentPrice = executionPrice,
                peakPrice = maxOf(existingPosition.peakPrice, executionPrice),
                currentValue = newAmount * executionPrice,
                unrealizedPnL = (newAmount * executionPrice) - newTotalInvested,
                unrealizedPnLPercent = (((newAmount * executionPrice) - newTotalInvested) / newTotalInvested) * 100,
                updatedAt = System.currentTimeMillis()
            )

            positionDao.updatePosition(updatedPosition)

            // Update exchange stop orders for new position size
            placeProtectiveOrders(updatedPosition, signal.stopLoss, signal.takeProfit)
        } else {
            // Create new position
            val position = Position(
                pair = signal.pair,
                amount = volume,
                averageEntryPrice = executionPrice,
                currentPrice = executionPrice,
                peakPrice = executionPrice,
                totalInvested = signal.suggestedAmount,
                currentValue = volume * executionPrice,
                unrealizedPnL = 0.0,
                unrealizedPnLPercent = 0.0,
                openedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isOpen = true
            )

            val positionId = positionDao.insertPosition(position)
            val savedPosition = position.copy(id = positionId)

            // Place stop-loss and take-profit orders on exchange
            placeProtectiveOrders(savedPosition, signal.stopLoss, signal.takeProfit)
        }

        // Update signal
        signalDao.updateSignal(
            signal.copy(
                status = SignalStatus.EXECUTED,
                executedAt = System.currentTimeMillis(),
                executedPrice = executionPrice,
                orderId = orderId
            )
        )

        return Result.success(order)
    }

    /**
     * Places stop-loss and take-profit orders on Kraken for the given position.
     * These act as a safety net independent of the polling cycle.
     */
    private suspend fun placeProtectiveOrders(position: Position, stopLossPrice: Double, takeProfitPrice: Double) {
        val pair = TradingPair.fromString(position.pair)

        // Cancel existing protective orders if any
        position.exchangeStopOrderId?.let { orderId ->
            try { apiClient.cancelOrder(orderId) } catch (_: Exception) {}
        }
        position.exchangeTakeProfitOrderId?.let { orderId ->
            try { apiClient.cancelOrder(orderId) } catch (_: Exception) {}
        }

        var stopOrderId: String? = null
        var takeProfitOrderId: String? = null

        // Place stop-loss order
        try {
            val stopResult = apiClient.addOrder(
                pair = pair,
                type = OrderSide.SELL,
                volume = position.amount,
                orderType = OrderType.STOP_LOSS,
                price = stopLossPrice,
                validate = false
            )
            if (stopResult.isSuccess) {
                stopOrderId = stopResult.getOrNull()
                Log.i(TAG, "Stop-loss order placed for ${position.pair} at $stopLossPrice: $stopOrderId")
            } else {
                Log.w(TAG, "Failed to place stop-loss for ${position.pair}: ${stopResult.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error placing stop-loss for ${position.pair}", e)
        }

        // Place take-profit order
        try {
            val tpResult = apiClient.addOrder(
                pair = pair,
                type = OrderSide.SELL,
                volume = position.amount,
                orderType = OrderType.TAKE_PROFIT,
                price = takeProfitPrice,
                validate = false
            )
            if (tpResult.isSuccess) {
                takeProfitOrderId = tpResult.getOrNull()
                Log.i(TAG, "Take-profit order placed for ${position.pair} at $takeProfitPrice: $takeProfitOrderId")
            } else {
                Log.w(TAG, "Failed to place take-profit for ${position.pair}: ${tpResult.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error placing take-profit for ${position.pair}", e)
        }

        // Save order IDs to position
        positionDao.updatePosition(
            position.copy(
                exchangeStopOrderId = stopOrderId ?: position.exchangeStopOrderId,
                exchangeTakeProfitOrderId = takeProfitOrderId ?: position.exchangeTakeProfitOrderId
            )
        )
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

        // Cancel any outstanding protective orders
        position.exchangeStopOrderId?.let { id ->
            try { apiClient.cancelOrder(id) } catch (_: Exception) {}
        }
        position.exchangeTakeProfitOrderId?.let { id ->
            try { apiClient.cancelOrder(id) } catch (_: Exception) {}
        }

        val fillResult = waitForOrderFill(orderId, pair)

        val fallbackFee = (volume * currentPrice) * 0.0026
        val (executionPrice, actualVolume, actualFee, finalStatus) = if (fillResult.isSuccess) {
            val fillInfo = fillResult.getOrNull()!!
            if (fillInfo.status == OrderStatus.CLOSED) {
                Quadruple(fillInfo.executedPrice, fillInfo.executedVolume, fillInfo.fee, fillInfo.status)
            } else {
                Quadruple(currentPrice, volume, fallbackFee, OrderStatus.OPEN)
            }
        } else {
            Log.w(TAG, "Using fallback price for sell order $orderId: ${fillResult.exceptionOrNull()?.message}")
            Quadruple(currentPrice, volume, fallbackFee, OrderStatus.OPEN)
        }

        val totalValue = actualVolume * executionPrice

        // Create order record
        val order = Order(
            orderId = orderId,
            pair = signal.pair,
            type = OrderType.MARKET,
            side = OrderSide.SELL,
            price = executionPrice,
            amount = actualVolume,
            cost = totalValue,
            fee = actualFee,
            status = finalStatus,
            createdAt = System.currentTimeMillis(),
            executedAt = if (finalStatus == OrderStatus.CLOSED) System.currentTimeMillis() else null,
            signalId = signal.id
        )

        orderDao.insertOrder(order)

        // Close position
        val realizedPnL = totalValue - position.totalInvested - actualFee
        val closedPosition = position.copy(
            currentPrice = executionPrice,
            currentValue = totalValue,
            unrealizedPnL = realizedPnL,
            realizedPnL = realizedPnL,
            isOpen = false,
            closedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            exchangeStopOrderId = null,
            exchangeTakeProfitOrderId = null
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

    /**
     * Monitors all open positions for exit conditions (stop-loss, take-profit, trailing stop).
     * Should be called each Worker cycle. This is a software-level check in addition to
     * the exchange-level protective orders (belt and suspenders approach).
     *
     * Returns the list of positions that were closed.
     */
    suspend fun monitorExitConditions(strategy: TradingStrategy): List<Position> {
        val positions = positionDao.getOpenPositions().first()
        val closedPositions = mutableListOf<Position>()

        for (position in positions) {
            try {
                val pair = TradingPair.fromString(position.pair)
                val tickerResult = apiClient.getTicker(pair)
                if (tickerResult.isFailure) continue

                val currentPrice = tickerResult.getOrNull()!!.lastTrade

                // Update peak price for trailing stop
                val updatedPosition = position.calculateCurrentValue(currentPrice)
                positionDao.updatePosition(updatedPosition)

                // Evaluate exit conditions
                val exitSignal = strategy.evaluateExitConditions(updatedPosition, currentPrice, config)

                if (exitSignal != null) {
                    Log.w(TAG, "Exit condition for ${position.pair}: ${exitSignal.reason}")

                    // Execute market sell immediately
                    val sellResult = executeExitOrder(updatedPosition, exitSignal)
                    if (sellResult.isSuccess) {
                        closedPositions.add(updatedPosition)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring exit for ${position.pair}", e)
            }
        }

        return closedPositions
    }

    /**
     * Executes an exit (sell) order triggered by exit conditions monitoring.
     */
    private suspend fun executeExitOrder(position: Position, exitSignal: ExitSignal): Result<Order> {
        val pair = TradingPair.fromString(position.pair)
        val volume = position.amount

        // Execute market sell
        val orderResult = apiClient.addOrder(
            pair = pair,
            type = OrderSide.SELL,
            volume = volume,
            orderType = OrderType.MARKET,
            validate = false
        )

        if (orderResult.isFailure) {
            circuitBreaker.recordFailure()
            return Result.failure(orderResult.exceptionOrNull()!!)
        }

        val orderId = orderResult.getOrNull()!!

        // Cancel protective orders on exchange
        position.exchangeStopOrderId?.let { id ->
            try { apiClient.cancelOrder(id) } catch (_: Exception) {}
        }
        position.exchangeTakeProfitOrderId?.let { id ->
            try { apiClient.cancelOrder(id) } catch (_: Exception) {}
        }

        val fillResult = waitForOrderFill(orderId, pair)

        val fallbackFee = (volume * exitSignal.suggestedPrice) * 0.0026
        val (executionPrice, actualVolume, actualFee, finalStatus) = if (fillResult.isSuccess) {
            val fillInfo = fillResult.getOrNull()!!
            if (fillInfo.status == OrderStatus.CLOSED) {
                Quadruple(fillInfo.executedPrice, fillInfo.executedVolume, fillInfo.fee, fillInfo.status)
            } else {
                Quadruple(exitSignal.suggestedPrice, volume, fallbackFee, OrderStatus.OPEN)
            }
        } else {
            Log.w(TAG, "Using fallback price for exit order $orderId: ${fillResult.exceptionOrNull()?.message}")
            Quadruple(exitSignal.suggestedPrice, volume, fallbackFee, OrderStatus.OPEN)
        }

        val totalValue = actualVolume * executionPrice

        val order = Order(
            orderId = orderId,
            pair = position.pair,
            type = OrderType.MARKET,
            side = OrderSide.SELL,
            price = executionPrice,
            amount = actualVolume,
            cost = totalValue,
            fee = actualFee,
            status = finalStatus,
            createdAt = System.currentTimeMillis(),
            executedAt = if (finalStatus == OrderStatus.CLOSED) System.currentTimeMillis() else null,
            signalId = null
        )

        orderDao.insertOrder(order)

        val realizedPnL = totalValue - position.totalInvested - actualFee
        val closedPosition = position.copy(
            currentPrice = executionPrice,
            currentValue = totalValue,
            unrealizedPnL = realizedPnL,
            realizedPnL = realizedPnL,
            isOpen = false,
            closedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            exchangeStopOrderId = null,
            exchangeTakeProfitOrderId = null
        )

        positionDao.updatePosition(closedPosition)

        Log.i(TAG, "Exit order executed for ${position.pair}: ${exitSignal.reason}, PnL: $realizedPnL")

        return Result.success(order)
    }

    suspend fun ignoreSignal(signal: TradingSignal) {
        signalDao.updateSignal(signal.copy(status = SignalStatus.IGNORED))
    }

    suspend fun cancelOrder(orderId: String): Result<Boolean> {
        return try {
            val result = apiClient.cancelOrder(orderId)
            if (result.isSuccess) {
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

    suspend fun waitForOrderFill(
        orderId: String,
        pair: TradingPair,
        timeoutMs: Long = ORDER_POLL_TIMEOUT_MS,
        pollIntervalMs: Long = ORDER_POLL_INTERVAL_MS
    ): Result<OrderFillInfo> {
        val startTime = System.currentTimeMillis()
        var lastStatus: String? = null

        repeat(MAX_FILL_RETRIES) { attempt ->
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeoutMs) {
                Log.w(TAG, "Order $orderId fill timeout after ${elapsed}ms")
                return Result.failure(Exception("Order fill timeout after ${elapsed}ms"))
            }

            val orderInfoResult = retryWithBackoff(
                maxRetries = 2,
                initialDelayMs = 1000
            ) {
                apiClient.queryOrder(orderId)
            }

            if (orderInfoResult.isFailure) {
                Log.w(TAG, "Failed to query order $orderId: ${orderInfoResult.exceptionOrNull()?.message}")
                delay(pollIntervalMs)
                return@repeat
            }

            val orderInfo = orderInfoResult.getOrNull()
            if (orderInfo == null) {
                Log.w(TAG, "Order $orderId not found on exchange")
                delay(pollIntervalMs)
                return@repeat
            }

            lastStatus = orderInfo.status

            when (orderInfo.status.lowercase()) {
                "closed" -> {
                    val executedPrice = orderInfo.price.toDoubleOrNull() ?: 0.0
                    val executedVolume = orderInfo.vol_exec.toDoubleOrNull() ?: 0.0
                    val fee = orderInfo.fee.toDoubleOrNull() ?: 0.0

                    Log.i(TAG, "Order $orderId filled: price=$executedPrice, volume=$executedVolume, fee=$fee")
                    return Result.success(
                        OrderFillInfo(
                            orderId = orderId,
                            status = OrderStatus.CLOSED,
                            executedPrice = executedPrice,
                            executedVolume = executedVolume,
                            fee = fee,
                            fillTime = System.currentTimeMillis()
                        )
                    )
                }
                "open", "pending" -> {
                    Log.d(TAG, "Order $orderId still ${orderInfo.status} (attempt ${attempt + 1}/$MAX_FILL_RETRIES)")
                    delay(pollIntervalMs)
                }
                "canceled", "expired" -> {
                    Log.w(TAG, "Order $orderId was ${orderInfo.status}")
                    return Result.success(
                        OrderFillInfo(
                            orderId = orderId,
                            status = if (orderInfo.status.lowercase() == "canceled") OrderStatus.CANCELED else OrderStatus.EXPIRED,
                            executedPrice = 0.0,
                            executedVolume = 0.0,
                            fee = 0.0,
                            fillTime = System.currentTimeMillis()
                        )
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown order status: ${orderInfo.status}")
                    delay(pollIntervalMs)
                }
            }
        }

        Log.w(TAG, "Order $orderId did not fill after $MAX_FILL_RETRIES attempts, last status: $lastStatus")
        return Result.failure(Exception("Order did not fill after $MAX_FILL_RETRIES attempts, last status: $lastStatus"))
    }

    data class OrderFillInfo(
        val orderId: String,
        val status: OrderStatus,
        val executedPrice: Double,
        val executedVolume: Double,
        val fee: Double,
        val fillTime: Long
    )

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
