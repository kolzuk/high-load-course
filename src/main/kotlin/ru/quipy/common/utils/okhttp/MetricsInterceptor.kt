package ru.quipy.common.utils.okhttp

import io.micrometer.core.instrument.MeterRegistry
import okhttp3.Interceptor
import okhttp3.Response
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class MetricsInterceptor(meterRegistry: MeterRegistry) : Interceptor {

    private val parallelRequests: AtomicInteger? = meterRegistry.gauge("parallel_requests", AtomicInteger(0))
    private val requestsProcessed = meterRegistry.counter("requests_processed")

    override fun intercept(chain: Interceptor.Chain): Response {
        parallelRequests?.getAndIncrement()
        val response = chain.proceed(chain.request())
        parallelRequests?.getAndDecrement()
        requestsProcessed.increment()
        return response
    }
}