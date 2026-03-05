package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.f4b6a3.uuid.UuidCreator
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.common.utils.queue.EsQueue
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.UUID

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val meterRegistry: MeterRegistry,
    private val webClient: WebClient,
    private val esQueue: EsQueue
) : PaymentExternalSystemAdapter {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()

        const val MAX_ATTEMPTS = 5
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimiter = SlidingWindowRateLimiter(rateLimitPerSec.toLong())
    private val semaphore = Semaphore(parallelRequests)

    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        performPaymentAsync(paymentId, amount, paymentStartedAt, deadline, attempts = 0)
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    private suspend fun performPaymentAsync(
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long,
        attempts: Int
    ) {
        logger.debug("[{}] Submitting payment request for payment {}", accountName, paymentId)

        val transactionId = UuidCreator.getTimeOrderedEpoch()

//        esQueue.submit {
//            // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
//            // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
//            paymentESService.update(paymentId) {
//                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
//            }
//        }

        logger.debug("[{}] Submit: {} , txId: {}", accountName, paymentId, transactionId)

        try {
            val response =
                webClient
                    .post()
                    .uri(
                        "http://$paymentProviderHostPort/external/process" +
                                "?serviceName=$serviceName" +
                                "&token=$token" +
                                "&accountName=$accountName" +
                                "&transactionId=$transactionId" +
                                "&paymentId=$paymentId" +
                                "&amount=$amount"
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(ExternalSysResponse::class.java)
                    .awaitSingle()

            logger.debug(
                "[{}] Payment processed for txId: {}, payment: {}, message: {}, result code: {}",
                accountName,
                transactionId,
                paymentId,
                response.body?.result,
                response.statusCode
            )

//            esQueue.submitAsync {
//                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
//                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
//                paymentESService.update(paymentId) {
//                    it.logProcessing(
//                        response.body!!.result, now(), transactionId, reason = response.body!!.message
//                    )
//                }
//            }
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
//                    esQueue.submitAsync {
//                        paymentESService.update(paymentId) {
//                            it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
//                        }
//                    }
                }
                // is TooManyRequestsException -> {
                //     logger.error("[$accountName] Too many requests for txId: $transactionId, payment: $paymentId")
                //     if (attempts < MAX_ATTEMPTS && deadline - now() > requestAverageProcessingTime.toMillis()) {
                //         performPaymentAsync(paymentId, amount, paymentStartedAt, deadline, attempts + 1)
                //     }
                // }
                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

//                    esQueue.submitAsync {
//                        paymentESService.update(paymentId) {
//                            it.logProcessing(false, now(), transactionId, reason = e.message)
//                        }
//                    }
                }
            }
        }
    }
}

fun now() = System.currentTimeMillis()
