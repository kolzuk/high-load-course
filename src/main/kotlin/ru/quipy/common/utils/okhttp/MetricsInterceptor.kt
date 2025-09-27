package ru.quipy.common.utils.okhttp

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.Interceptor
import okhttp3.Response
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class MetricsInterceptor(meterRegistry: MeterRegistry) : Interceptor {

    private val parallelRequests: AtomicInteger? = meterRegistry.gauge("parallel_requests", AtomicInteger(0))
    private val requestsTotal: Counter = Counter.builder("requests_total")
        .description("Total number of requests sent to external service")
        .register(meterRegistry)

    override fun intercept(chain: Interceptor.Chain): Response {
        parallelRequests?.getAndIncrement()
        requestsTotal.increment()
        val response = chain.proceed(chain.request())
        parallelRequests?.getAndDecrement()
        return response
    }
}