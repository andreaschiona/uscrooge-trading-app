package com.uscrooge.app.executor

import android.util.Log
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.BrokerApi
import com.uscrooge.app.data.api.BrokerOrderInfo
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.api.retryWithBackoff
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.model.*
import com.uscrooge.app.di.BrokerRegistry
import com.uscrooge.app.integration.GitHubIssueReporter
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
    private val krakenApiClient: KrakenApiClient,
    private val alpacaApiClient: AlpacaApiClient,
    private val brokerRegistry: BrokerRegistry,
    private val signalDao: TradingSignalDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val circuitBreaker: CircuitBreaker,
    private val gitHubIssueReporter: GitHubIssueReporter
) {

    @Volatile
    private var config: TradingConfig = TradingConfig()

    companion object {
        private const val TAG = "OrderExecutor"
        private const val ORDER_POLL_INTERVAL_MS = 2000L
        private const val ORDER_POLL_TIMEOUT_MS = 30000L
        private const val MAX_FILL_RETRIES = 5
    }

    fun updateConfig(newConfig: TradingConfig) {
        this.config = newConfig
    }

    private fun getBrokerForPair(pair: String): BrokerApi {
        val quote = pair.substringAfter("/").uppercase()
        return if (quote == "EUR") krakenApiClient else alpacaApiClient
    }

    private fun getBrokerForPosition(position: Position): BrokerApi {
        return if (position.broker == "Alpaca") alpacaApiClient else krakenApiClient
    }

    suspend fun checkTradingAllowed(): String? {
        return circuitBreaker.checkTradingAllowed(config)
    }

    suspend fun executeSignal(signal: TradingSignal, bypassCircuitBreaker: Boolean = false): Result<Order> {
        val blocked = circuitBreaker.checkTradingAllowed(config, skipDrawdownCheck = bypassCircuitBreaker)
        if (blocked != null) {
            signalDao.updateSignal(signal.copy(status = SignalStatus.FAILED))
            return Result.failure(Exception("Trading blocked: $blocked"))
        }

        return try {
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
                signalDao.updateSignal(signal.copy(status = SignalStatus.FAILED))
            }

            result
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            signalDao.updateSignal(signal.copy(status = SignalStatus.FAILED))
            Result.failure(e)
        }
    }

    private suspend fun executeBuyOrder(signal: TradingSignal): Result<Order> {
        val broker = getBrokerForPair(signal.pair)
        val isAlpaca = broker === alpacaApiClient

        val tickerResult = if (isAlpaca) {
            broker.getTicker(signal.pair.substringBefore("/"))
        } else {
            (broker as KrakenApiClient).getTicker(TradingPair.fromString(signal.pair))
        }
        if (tickerResult.isFailure) {
            return Result.failure(tickerResult.exceptionOrNull()!!)
        }

        val currentPrice = tickerResult.getOrNull()!!.ask
        val slippage = abs((currentPrice - signal.suggestedPrice) / signal.suggestedPrice) * 100

        if (slippage > config.maxSlippagePercent) {
            Log.w(TAG, "Slippage ${String.format("%.2f", slippage)}% exceeds max ${config.maxSlippagePercent}% for ${signal.pair}. Forcing market order.")
        }

        val quoteCurrency = signal.pair.substringAfter("/").uppercase()
        val balanceResult = broker.getAvailableBalance(quoteCurrency)
        if (balanceResult.isFailure) {
            return Result.failure(balanceResult.exceptionOrNull()!!)
        }
        val availableBalance = balanceResult.getOrNull()!!
        val maxAffordableAmount = availableBalance * 0.997
        val effectiveAmount = if (signal.suggestedAmount > maxAffordableAmount) {
            if (maxAffordableAmount <= 0.0) {
                return Result.failure(Exception(
                    "Insufficient $quoteCurrency balance (available: ${String.format("%.2f", availableBalance)}) to execute order for ${signal.pair}"
                ))
            }
            Log.w(TAG, "Signal amount ${String.format("%.2f", signal.suggestedAmount)} exceeds available balance ${String.format("%.2f", availableBalance)} for ${signal.pair}. Reducing to ${String.format("%.2f", maxAffordableAmount)}")
            maxAffordableAmount
        } else {
            signal.suggestedAmount
        }

        val volume = effectiveAmount / currentPrice

        if (!isAlpaca) {
            val krakenSymbol = TradingPair.fromString(signal.pair).toKrakenSymbol()
            val orderMinimum = krakenApiClient.getOrderMinimum(krakenSymbol)
            if (orderMinimum > 0.0 && volume < orderMinimum) {
                return Result.failure(Exception(
                    "Buy volume $volume for ${signal.pair} is below minimum $orderMinimum"
                ))
            }
        }

        val useLimit = config.useLimitOrders && signal.strength < config.strongSignalThreshold && slippage <= config.maxSlippagePercent
        val orderType = if (useLimit) OrderType.LIMIT else OrderType.MARKET
        val limitPrice = if (useLimit) currentPrice * 0.999 else null

        val symbol = if (isAlpaca) signal.pair.substringBefore("/") else signal.pair

        val validateResult = if (isAlpaca) {
            broker.placeOrder(
                symbol = symbol,
                side = OrderSide.BUY,
                quantity = volume,
                orderType = orderType,
                limitPrice = limitPrice,
                validate = true
            )
        } else {
            (broker as KrakenApiClient).addOrder(
                pair = TradingPair.fromString(signal.pair),
                type = OrderSide.BUY,
                volume = volume,
                orderType = orderType,
                price = limitPrice,
                validate = true
            )
        }

        if (validateResult.isFailure) {
            return Result.failure(validateResult.exceptionOrNull()!!)
        }

        val orderResult = if (isAlpaca) {
            broker.placeOrder(
                symbol = symbol,
                side = OrderSide.BUY,
                quantity = volume,
                orderType = orderType,
                limitPrice = limitPrice,
                validate = false
            )
        } else {
            (broker as KrakenApiClient).addOrder(
                pair = TradingPair.fromString(signal.pair),
                type = OrderSide.BUY,
                volume = volume,
                orderType = orderType,
                price = limitPrice,
                validate = false
            )
        }

        if (orderResult.isFailure) {
            return Result.failure(orderResult.exceptionOrNull()!!)
        }

        val orderId = orderResult.getOrNull()!!

        val fillResult = waitForOrderFill(orderId, signal.pair, broker)

        val (executionPrice, actualVolume, actualFee, finalStatus) = if (fillResult.isSuccess) {
            val fillInfo = fillResult.getOrNull()!!
            if (fillInfo.status == OrderStatus.CLOSED) {
                Quadruple(fillInfo.executedPrice, fillInfo.executedVolume, fillInfo.fee, fillInfo.status)
            } else {
                Quadruple(limitPrice ?: currentPrice, volume, effectiveAmount * 0.0026, OrderStatus.OPEN)
            }
        } else {
            Log.w(TAG, "Using fallback price for order $orderId: ${fillResult.exceptionOrNull()?.message}")
            Quadruple(limitPrice ?: currentPrice, volume, effectiveAmount * 0.0026, OrderStatus.OPEN)
        }

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

        val existingPosition = positionDao.getOpenPositionByPair(signal.pair)
        val brokerName = if (isAlpaca) "Alpaca" else "Kraken"

        if (existingPosition != null) {
            val newAmount = existingPosition.amount + volume
            val newTotalInvested = existingPosition.totalInvested + effectiveAmount
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
            placeProtectiveOrders(updatedPosition, signal.stopLoss, signal.takeProfit, broker)
        } else {
            val position = Position(
                pair = signal.pair,
                amount = volume,
                averageEntryPrice = executionPrice,
                currentPrice = executionPrice,
                peakPrice = executionPrice,
                totalInvested = effectiveAmount,
                currentValue = volume * executionPrice,
                unrealizedPnL = 0.0,
                unrealizedPnLPercent = 0.0,
                openedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isOpen = true,
                broker = brokerName
            )

            val positionId = positionDao.insertPosition(position)
            val savedPosition = position.copy(id = positionId)
            placeProtectiveOrders(savedPosition, signal.stopLoss, signal.takeProfit, broker)
        }

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

    private suspend fun placeProtectiveOrders(position: Position, stopLossPrice: Double, takeProfitPrice: Double, broker: BrokerApi) {
        val isAlpaca = broker === alpacaApiClient
        val symbol = if (isAlpaca) position.pair.substringBefore("/") else position.pair

        position.exchangeStopOrderId?.let { orderId ->
            try { broker.cancelOrder(orderId) } catch (_: Exception) {}
        }
        position.exchangeTakeProfitOrderId?.let { orderId ->
            try { broker.cancelOrder(orderId) } catch (_: Exception) {}
        }

        var stopOrderId: String? = null
        var takeProfitOrderId: String? = null

        try {
            val stopResult = if (isAlpaca) {
                broker.placeOrder(
                    symbol = symbol,
                    side = OrderSide.SELL,
                    quantity = position.amount,
                    orderType = OrderType.STOP_LOSS,
                    stopPrice = stopLossPrice,
                    validate = false
                )
            } else {
                (broker as KrakenApiClient).addOrder(
                    pair = TradingPair.fromString(position.pair),
                    type = OrderSide.SELL,
                    volume = position.amount,
                    orderType = OrderType.STOP_LOSS,
                    price = stopLossPrice,
                    validate = false
                )
            }
            if (stopResult.isSuccess) {
                stopOrderId = stopResult.getOrNull()
                Log.i(TAG, "Stop-loss placed for ${position.pair} at $stopLossPrice: $stopOrderId")
            } else {
                Log.w(TAG, "Failed to place stop-loss for ${position.pair}: ${stopResult.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error placing stop-loss for ${position.pair}", e)
        }

        try {
            val tpResult = if (isAlpaca) {
                broker.placeOrder(
                    symbol = symbol,
                    side = OrderSide.SELL,
                    quantity = position.amount,
                    orderType = OrderType.TAKE_PROFIT,
                    limitPrice = takeProfitPrice,
                    validate = false
                )
            } else {
                (broker as KrakenApiClient).addOrder(
                    pair = TradingPair.fromString(position.pair),
                    type = OrderSide.SELL,
                    volume = position.amount,
                    orderType = OrderType.TAKE_PROFIT,
                    price = takeProfitPrice,
                    validate = false
                )
            }
            if (tpResult.isSuccess) {
                takeProfitOrderId = tpResult.getOrNull()
                Log.i(TAG, "Take-profit placed for ${position.pair} at $takeProfitPrice: $takeProfitOrderId")
            } else {
                Log.w(TAG, "Failed to place take-profit for ${position.pair}: ${tpResult.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error placing take-profit for ${position.pair}", e)
        }

        positionDao.updatePosition(
            position.copy(
                exchangeStopOrderId = stopOrderId ?: position.exchangeStopOrderId,
                exchangeTakeProfitOrderId = takeProfitOrderId ?: position.exchangeTakeProfitOrderId
            )
        )
    }

    private suspend fun executeSellOrder(signal: TradingSignal): Result<Order> {
        val broker = getBrokerForPair(signal.pair)
        val isAlpaca = broker === alpacaApiClient

        val position = positionDao.getOpenPositionByPair(signal.pair)
            ?: return Result.failure(Exception("No open position for ${signal.pair}"))

        val symbol = if (isAlpaca) signal.pair.substringBefore("/") else signal.pair
                val tickerResult = if (isAlpaca) {
                    broker.getTicker(symbol)
                } else {
                    (broker as KrakenApiClient).getTicker(TradingPair.fromString(position.pair))
                }
        if (tickerResult.isFailure) {
            return Result.failure(tickerResult.exceptionOrNull()!!)
        }

        val currentPrice = tickerResult.getOrNull()!!.bid
        val volume = position.amount

        if (!isAlpaca) {
            val krakenSymbol = TradingPair.fromString(signal.pair).toKrakenSymbol()
            val orderMinimum = krakenApiClient.getOrderMinimum(krakenSymbol)
            if (orderMinimum > 0.0 && volume < orderMinimum) {
                return closeDustPosition(position, "Sell signal (volume below minimum)")
            }
        }

        val validateResult = if (isAlpaca) {
            broker.placeOrder(
                symbol = symbol,
                side = OrderSide.SELL,
                quantity = volume,
                orderType = OrderType.MARKET,
                validate = true
            )
        } else {
            (broker as KrakenApiClient).addOrder(
                pair = TradingPair.fromString(signal.pair),
                type = OrderSide.SELL,
                volume = volume,
                orderType = OrderType.MARKET,
                validate = true
            )
        }

        if (validateResult.isFailure) {
            return Result.failure(validateResult.exceptionOrNull()!!)
        }

        val orderResult = if (isAlpaca) {
            broker.placeOrder(
                symbol = symbol,
                side = OrderSide.SELL,
                quantity = volume,
                orderType = OrderType.MARKET,
                validate = false
            )
        } else {
            (broker as KrakenApiClient).addOrder(
                pair = TradingPair.fromString(signal.pair),
                type = OrderSide.SELL,
                volume = volume,
                orderType = OrderType.MARKET,
                validate = false
            )
        }

        if (orderResult.isFailure) {
            return Result.failure(orderResult.exceptionOrNull()!!)
        }

        val orderId = orderResult.getOrNull()!!

        position.exchangeStopOrderId?.let { id ->
            try { broker.cancelOrder(id) } catch (_: Exception) {}
        }
        position.exchangeTakeProfitOrderId?.let { id ->
            try { broker.cancelOrder(id) } catch (_: Exception) {}
        }

        val fillResult = waitForOrderFill(orderId, signal.pair, broker)

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

        reportPositionFeedback(closedPosition, "Sell signal: ${signal.getReasonsList().joinToString(", ")}", config)

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

    suspend fun monitorExitConditions(strategy: TradingStrategy): List<Position> {
        val positions = positionDao.getOpenPositions().first()
        val closedPositions = mutableListOf<Position>()

        for (position in positions) {
            try {
                val broker = getBrokerForPosition(position)
                val isAlpaca = broker === alpacaApiClient
                val symbol = if (isAlpaca) position.pair.substringBefore("/") else position.pair

                val tickerResult = if (isAlpaca) {
                    broker.getTicker(symbol)
                } else {
                    (broker as KrakenApiClient).getTicker(TradingPair.fromString(position.pair))
                }
                if (tickerResult.isFailure) continue

                val currentPrice = tickerResult.getOrNull()!!.lastTrade

                val updatedPosition = position.calculateCurrentValue(currentPrice)
                positionDao.updatePosition(updatedPosition)

                val exitSignal = strategy.evaluateExitConditions(updatedPosition, currentPrice, config)

                if (exitSignal != null) {
                    Log.w(TAG, "Exit condition for ${position.pair}: ${exitSignal.reason}")

                    val sellResult = executeExitOrder(updatedPosition, exitSignal, broker)
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

    private suspend fun executeExitOrder(position: Position, exitSignal: ExitSignal, broker: BrokerApi): Result<Order> {
        val isAlpaca = broker === alpacaApiClient
        val symbol = if (isAlpaca) position.pair.substringBefore("/") else position.pair
        val volume = position.amount

        if (!isAlpaca) {
            val krakenSymbol = TradingPair.fromString(position.pair).toKrakenSymbol()
            val orderMinimum = krakenApiClient.getOrderMinimum(krakenSymbol)
            if (orderMinimum > 0.0 && volume < orderMinimum) {
                return closeDustPosition(position, "Exit condition: ${exitSignal.reason} (volume below minimum)")
            }
        }

        val orderResult = if (isAlpaca) {
            broker.placeOrder(
                symbol = symbol,
                side = OrderSide.SELL,
                quantity = volume,
                orderType = OrderType.MARKET,
                validate = false
            )
        } else {
            (broker as KrakenApiClient).addOrder(
                pair = TradingPair.fromString(position.pair),
                type = OrderSide.SELL,
                volume = volume,
                orderType = OrderType.MARKET,
                validate = false
            )
        }

        if (orderResult.isFailure) {
            circuitBreaker.recordFailure()
            return Result.failure(orderResult.exceptionOrNull()!!)
        }

        val orderId = orderResult.getOrNull()!!

        position.exchangeStopOrderId?.let { id ->
            try { broker.cancelOrder(id) } catch (_: Exception) {}
        }
        position.exchangeTakeProfitOrderId?.let { id ->
            try { broker.cancelOrder(id) } catch (_: Exception) {}
        }

        val fillResult = waitForOrderFill(orderId, position.pair, broker)

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

        reportPositionFeedback(closedPosition, "Exit condition: ${exitSignal.reason}", config)

        Log.i(TAG, "Exit order executed for ${position.pair}: ${exitSignal.reason}, PnL: $realizedPnL")

        return Result.success(order)
    }

    private suspend fun closeDustPosition(position: Position, reason: String, broker: BrokerApi? = null): Result<Order> {
        Log.w(TAG, "Closing dust position ${position.pair}: volume ${position.amount} below minimum, reason: $reason")

        val activeBroker = broker ?: getBrokerForPosition(position)
        position.exchangeStopOrderId?.let { id ->
            try { activeBroker.cancelOrder(id) } catch (_: Exception) {}
        }
        position.exchangeTakeProfitOrderId?.let { id ->
            try { activeBroker.cancelOrder(id) } catch (_: Exception) {}
        }

        val loss = -position.totalInvested
        val closedPosition = position.copy(
            currentValue = 0.0,
            realizedPnL = loss,
            isOpen = false,
            closedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            exchangeStopOrderId = null,
            exchangeTakeProfitOrderId = null
        )
        positionDao.updatePosition(closedPosition)
        reportPositionFeedback(closedPosition, reason, config)
        val dustOrder = Order(
            orderId = "dust_${System.currentTimeMillis()}_${position.pair.hashCode()}",
            pair = position.pair,
            type = OrderType.MARKET,
            side = OrderSide.SELL,
            price = 0.0,
            amount = 0.0,
            cost = 0.0,
            fee = 0.0,
            status = OrderStatus.CANCELED,
            createdAt = System.currentTimeMillis()
        )
        orderDao.insertOrder(dustOrder)
        return Result.success(dustOrder)
    }

    suspend fun closePosition(position: Position): Result<Order> {
        return try {
            val broker = getBrokerForPosition(position)
            val isAlpaca = broker === alpacaApiClient
            val symbol = if (isAlpaca) position.pair.substringBefore("/") else position.pair
            val volume = position.amount

            if (volume <= 0.0) {
                return Result.failure(Exception("Position ${position.pair} has no volume to sell"))
            }

            if (!isAlpaca) {
                val krakenSymbol = TradingPair.fromString(position.pair).toKrakenSymbol()
                val orderMinimum = krakenApiClient.getOrderMinimum(krakenSymbol)
                if (orderMinimum > 0.0 && volume < orderMinimum) {
                    return closeDustPosition(position, "Manual close (volume below minimum)")
                }
            }

            val orderResult = if (isAlpaca) {
                broker.placeOrder(
                    symbol = symbol,
                    side = OrderSide.SELL,
                    quantity = volume,
                    orderType = OrderType.MARKET,
                    validate = false
                )
            } else {
                (broker as KrakenApiClient).addOrder(
                    pair = TradingPair.fromString(position.pair),
                    type = OrderSide.SELL,
                    volume = volume,
                    orderType = OrderType.MARKET,
                    validate = false
                )
            }

            if (orderResult.isFailure) {
                return Result.failure(orderResult.exceptionOrNull()!!)
            }

            val orderId = orderResult.getOrNull()!!

            position.exchangeStopOrderId?.let { id ->
                try { broker.cancelOrder(id) } catch (_: Exception) {}
            }
            position.exchangeTakeProfitOrderId?.let { id ->
                try { broker.cancelOrder(id) } catch (_: Exception) {}
            }

            val fillResult = waitForOrderFill(orderId, position.pair, broker)
            val fallbackFee = volume * position.currentPrice * 0.0026
            val (executionPrice, actualVolume, actualFee, finalStatus) = if (fillResult.isSuccess) {
                val fillInfo = fillResult.getOrNull()!!
                if (fillInfo.status == OrderStatus.CLOSED) {
                    Quadruple(fillInfo.executedPrice, fillInfo.executedVolume, fillInfo.fee, fillInfo.status)
                } else {
                    Quadruple(position.currentPrice, volume, fallbackFee, OrderStatus.OPEN)
                }
            } else {
                Quadruple(position.currentPrice, volume, fallbackFee, OrderStatus.OPEN)
            }

            val totalValue = actualVolume * executionPrice
            val realizedPnL = totalValue - position.totalInvested - actualFee

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
            reportPositionFeedback(closedPosition, "Manual close by user", config)

            Log.i(TAG, "Position closed manually: ${position.pair}, PnL: $realizedPnL")

            Result.success(order)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close position ${position.pair}: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun ignoreSignal(signal: TradingSignal) {
        signalDao.updateSignal(signal.copy(status = SignalStatus.IGNORED))
    }

    suspend fun cancelOrder(orderId: String): Result<Boolean> {
        return try {
            val order = orderDao.getOrderById(orderId)
            if (order != null) {
                val broker = getBrokerForPair(order.pair)
                val result = broker.cancelOrder(orderId)
                if (result.isSuccess) {
                    orderDao.updateOrder(order.copy(status = OrderStatus.CANCELED))
                }
                result
            } else {
                Result.failure(Exception("Order not found: $orderId"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun waitForOrderFill(
        orderId: String,
        @Suppress("UNUSED_PARAMETER") pair: String,
        broker: BrokerApi,
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
                broker.getOrder(orderId)
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
                    val executedPrice = orderInfo.avgFillPrice
                    val executedVolume = orderInfo.filledQuantity
                    val fee = orderInfo.fee

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

    // Overload for backward compatibility with Kraken
    suspend fun waitForOrderFill(
        orderId: String,
        pair: TradingPair,
        timeoutMs: Long = ORDER_POLL_TIMEOUT_MS,
        pollIntervalMs: Long = ORDER_POLL_INTERVAL_MS
    ): Result<OrderFillInfo> {
        val broker = getBrokerForPair(pair.symbol)
        return waitForOrderFill(orderId, pair.symbol, broker, timeoutMs, pollIntervalMs)
    }

    data class OrderFillInfo(
        val orderId: String,
        val status: OrderStatus,
        val executedPrice: Double,
        val executedVolume: Double,
        val fee: Double,
        val fillTime: Long
    )

    private suspend fun reportPositionFeedback(
        closedPosition: Position,
        exitReason: String?,
        currentConfig: TradingConfig
    ) {
        try {
            val buyOrder = orderDao.getLastBuyOrderByPair(closedPosition.pair)
            val openingSignal = buyOrder?.signalId?.let { signalId ->
                signalDao.getSignalById(signalId)
            }

            gitHubIssueReporter.reportPositionFeedback(
                position = closedPosition,
                openingSignal = openingSignal,
                exitReason = exitReason,
                config = currentConfig
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report position feedback for ${closedPosition.pair}", e)
        }
    }

    suspend fun updatePositionPrices() {
        val positions = positionDao.getOpenPositions().first()

        positions.forEach { position ->
            try {
                val broker = getBrokerForPosition(position)
                val isAlpaca = broker === alpacaApiClient
                val symbol = if (isAlpaca) position.pair.substringBefore("/") else position.pair

                val tickerResult = if (isAlpaca) {
                    broker.getTicker(symbol)
                } else {
                    (broker as KrakenApiClient).getTicker(TradingPair.fromString(position.pair))
                }

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
