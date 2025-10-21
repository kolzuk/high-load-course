package ru.quipy.common.utils.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.Semaphore

class WindowLimiterInterceptor(limit: Int) : Interceptor {
    private val semaphore: Semaphore = Semaphore(limit, true)

    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            semaphore.acquire()
            return chain.proceed(chain.request())
        } finally {
            semaphore.release()
        }
    }
}