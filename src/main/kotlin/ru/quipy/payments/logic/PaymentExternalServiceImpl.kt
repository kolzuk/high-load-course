package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient
import ru.quipy.common.utils.SlidingWindowRateLimiter
import java.util.concurrent.TimeUnit
import java.util.UUID

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentProviderHostPort: String,
    private val token: String,
    meterRegistry: MeterRegistry,
    private val webClient: WebClient,
    private val circuitBreaker: CircuitBreaker
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

    private val timer = meterRegistry.timer("external_service_latency")

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
        val transactionId = UUID.randomUUID()
        logger.debug("[{}] Submit: {} , txId: {}", accountName, paymentId, transactionId)

        while (!circuitBreaker.tryAcquirePermission()) {
            delay(500)
        }
        val start = now()

        try {
            val response = withTimeout(400) {
                makeRequest(paymentId, transactionId, amount)
            }

            logger.debug(
                "[{}] Payment processed for txId: {}, payment: {}, message: {}, result code: {}",
                accountName,
                transactionId,
                paymentId,
                response.body?.result,
                response.statusCode
            )
            circuitBreaker.onSuccess(now() - start, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
            circuitBreaker.onError(now() - start, TimeUnit.MILLISECONDS, e)
            if (now() < deadline) {
                performPaymentAsync(paymentId, amount, paymentStartedAt, deadline, attempts + 1)
            }
        }
    }


    private suspend fun makeRequest(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int
    ): ResponseEntity<ExternalSysResponse?> {
        rateLimiter.tickBlocking()
        return semaphore.withPermit {
            val start = now()
            val res = webClient.post()
                .uri(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName" +
                            "&token=$token" +
                            "&accountName=$accountName" +
                            "&transactionId=$transactionId" +
                            "&paymentId=$paymentId" +
                            "&amount=$amount"
                )
                .header("x-idempotency-key", paymentId.toString())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(ExternalSysResponse::class.java)
                .awaitSingle()
            timer.record(now() - start, TimeUnit.MILLISECONDS)
            res
        }
    }
}

fun now() = System.currentTimeMillis()
