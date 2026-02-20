package ru.quipy.payments.logic

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.queue.EsQueue
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val registry: MeterRegistry,
    private val esQueue: EsQueue
) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    @OptIn(DelicateCoroutinesApi::class)
    private val executorScope = CoroutineScope(
        Dispatchers.IO
    )

    private val paymentExecutionTimer = registry.timer("payment_executor_task_duration")
    private val createEventTimer = registry.timer("paymentESservice_create_duration")

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()

        executorScope.launch {
            val start = System.nanoTime()
            val createdEvent =
                esQueue.submit {
                    paymentESService.create {
                        it.create(
                            paymentId,
                            orderId,
                            amount
                        )
                    }
                }

            createEventTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
            logger.debug("Payment {} for order {} created.", createdEvent.paymentId, orderId)

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
            paymentExecutionTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }

        return createdAt
    }
}