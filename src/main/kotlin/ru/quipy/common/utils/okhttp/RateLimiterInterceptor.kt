package ru.quipy.common.utils.okhttp

import io.github.resilience4j.ratelimiter.RateLimiter
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RateLimiterInterceptor(
    private val limiter: RateLimiter,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!limiter.acquirePermission()) throw IOException("Timeout from RateLimiter")

        return chain.proceed(chain.request())
    }
}
