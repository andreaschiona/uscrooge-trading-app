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
class OrderDaoTest {

    private lateinit var db: TradingDatabase
    private lateinit var dao: OrderDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.orderDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndGetAllOrders() = runBlocking {
        val order = createTestOrder()
        dao.insertOrder(order)
        val orders = dao.getAllOrders().first()
        assertEquals(1, orders.size)
    }

    @Test
    fun getOrdersByStatus() = runBlocking {
        dao.insertOrder(createTestOrder(status = OrderStatus.OPEN))
        dao.insertOrder(createTestOrder(orderId = "order-2", status = OrderStatus.CLOSED))
        val open = dao.getOrdersByStatus(OrderStatus.OPEN).first()
        assertEquals(1, open.size)
    }

    @Test
    fun getOrderById() = runBlocking {
        val order = createTestOrder()
        dao.insertOrder(order)
        val result = dao.getOrderById("order-1")
        assertNotNull(result)
        assertEquals("BTC/EUR", result!!.pair)
    }

    @Test
    fun updateOrder() = runBlocking {
        dao.insertOrder(createTestOrder())
        val order = dao.getOrderById("order-1")!!
        dao.updateOrder(order.copy(status = OrderStatus.CLOSED))
        val updated = dao.getOrderById("order-1")
        assertEquals(OrderStatus.CLOSED, updated!!.status)
    }

    @Test
    fun deleteOrder() = runBlocking {
        val order = createTestOrder()
        dao.insertOrder(order)
        dao.deleteOrder(order)
        val orders = dao.getAllOrders().first()
        assertTrue(orders.isEmpty())
    }

    @Test
    fun getOrdersByPair() = runBlocking {
        dao.insertOrder(createTestOrder(pair = "BTC/EUR"))
        dao.insertOrder(createTestOrder(orderId = "order-2", pair = "ETH/EUR"))
        val btcOrders = dao.getOrdersByPair("BTC/EUR").first()
        assertEquals(1, btcOrders.size)
    }

    @Test
    fun getLastBuyOrderByPair() = runBlocking {
        dao.insertOrder(createTestOrder(orderId = "order-1", pair = "BTC/EUR", side = OrderSide.BUY))
        dao.insertOrder(createTestOrder(orderId = "order-2", pair = "BTC/EUR", side = OrderSide.BUY))
        val last = dao.getLastBuyOrderByPair("BTC/EUR")
        assertNotNull(last)
        assertEquals("order-2", last!!.orderId)
    }

    @Test
    fun getTradeCountSince() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertOrder(createTestOrder(orderId = "order-1", createdAt = now - 1000, status = OrderStatus.CLOSED))
        dao.insertOrder(createTestOrder(orderId = "order-2", createdAt = now - 50000, status = OrderStatus.OPEN))
        val count = dao.getTradeCountSince(now - 10000)
        assertEquals(1, count)
    }

    private fun createTestOrder(
        orderId: String = "order-1",
        pair: String = "BTC/EUR",
        side: OrderSide = OrderSide.BUY,
        status: OrderStatus = OrderStatus.OPEN,
        createdAt: Long = System.currentTimeMillis()
    ): Order = Order(
        orderId = orderId,
        pair = pair,
        type = OrderType.MARKET,
        side = side,
        price = 50000.0,
        amount = 0.01,
        cost = 500.0,
        fee = 1.3,
        status = status,
        createdAt = createdAt
    )
}
