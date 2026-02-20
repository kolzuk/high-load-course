package ru.quipy.common.utils.queue

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@Component
class EsQueue(
    registry: MeterRegistry,
) : CoroutineScope {
    private val log = LoggerFactory.getLogger(javaClass)

    private val workers = 150
    private val capacity = 1000

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(workers)
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + dispatcher

    private val ch = Channel<Task<*>>(capacity)

    private val inflight = AtomicInteger(0)

    private val enqueued = registry.counter("esqueue_enqueued_total")
    private val dropped = registry.counter("esqueue_dropped_total")

    private val queueDelayTimer: Timer = Timer.builder("esqueue_queue_delay")
        .description("Time from enqueue to start of execution")
        .publishPercentileHistogram()
        .register(registry)

    private val runTimeTimer: Timer = Timer.builder("esqueue_run_time")
        .description("Task execution time")
        .publishPercentileHistogram()
        .register(registry)

    private val totalTimeTimer: Timer = Timer.builder("esqueue_total_time")
        .description("Total time (queue delay + execution)")
        .publishPercentileHistogram()
        .register(registry)

    private val queueSize = AtomicInteger(0)

    init {
        registry.gauge("esqueue_queue_size", queueSize)
        registry.gauge("esqueue_inflight", inflight)

        repeat(workers) { idx ->
            launch(CoroutineName("es-worker-$idx")) {
                for (task in ch) {
                    queueSize.decrementAndGet()
                    val startExecNanos = System.nanoTime()
                    val queueDelayNanos = startExecNanos - task.enqueuedAtNanos
                    queueDelayTimer.record(queueDelayNanos, TimeUnit.NANOSECONDS)

                    inflight.incrementAndGet()
                    try {
                        val runStart = System.nanoTime()
                        task.run()
                        runTimeTimer.record(System.nanoTime() - runStart, TimeUnit.NANOSECONDS)
                    } catch (t: Throwable) {
                        log.error("ES worker crashed", t)
                    } finally {
                        inflight.decrementAndGet()
                        totalTimeTimer.record(System.nanoTime() - task.enqueuedAtNanos, TimeUnit.NANOSECONDS)
                    }
                }
            }
        }
    }

    suspend fun <T> submit(block: suspend () -> T): T {
        val deferred = CompletableDeferred<T>()
        val task = Task(System.nanoTime(), deferred, block)

        ch.send(task)
        queueSize.incrementAndGet()
        enqueued.increment()

        return deferred.await()
    }

    suspend fun submitAsync(block: suspend () -> Unit): Boolean {
        val deferred = CompletableDeferred<Unit>()
        val task = Task(System.nanoTime(), deferred, block)
        val ok = ch.trySend(task).isSuccess
        if (ok) {
            queueSize.incrementAndGet()
            enqueued.increment()
        } else {
            dropped.increment()
        }
        return ok
    }

    fun <T> trySubmit(block: suspend () -> T): Deferred<T>? {
        val deferred = CompletableDeferred<T>()
        val task = Task(System.nanoTime(), deferred, block)

        val ok = ch.trySend(task).isSuccess
        return if (ok) {
            queueSize.incrementAndGet()
            enqueued.increment()
            deferred
        } else {
            dropped.increment()
            null
        }
    }

    @PreDestroy
    fun shutdown() {
        ch.close()
        job.cancel()
    }

    private class Task<T>(
        val enqueuedAtNanos: Long,
        private val deferred: CompletableDeferred<T>,
        private val block: suspend () -> T,
    ) {
        suspend fun run() {
            try {
                deferred.complete(block())
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
    }
}
