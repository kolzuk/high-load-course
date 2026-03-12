package ru.quipy.payments.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentService: PaymentService

    @OptIn(DelicateCoroutinesApi::class)
    private val executorScope = CoroutineScope(
        Dispatchers.IO
    )

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()

        executorScope.launch {
            logger.debug("Payment {} for order {} created.", paymentId, orderId)
            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }

        return createdAt
    }
}