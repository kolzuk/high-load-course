package ru.quipy

import io.pyroscope.http.Format
import io.pyroscope.javaagent.EventType
import io.pyroscope.javaagent.PyroscopeAgent
import io.pyroscope.javaagent.config.Config
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import ru.quipy.common.utils.NamedThreadFactory
import java.util.concurrent.Executors


@SpringBootApplication
class OnlineShopApplication {
    val log: Logger = LoggerFactory.getLogger(OnlineShopApplication::class.java)

    companion object {
        val appExecutor = Executors.newFixedThreadPool(64, NamedThreadFactory("main-app-executor"))
    }
}

fun main(args: Array<String>) {
    PyroscopeAgent.start(
        Config.Builder()
            .setApplicationName("online-store")
            .setServerAddress("http://localhost:4040")
            .setProfilingEvent(EventType.WALL)
            .setFormat(Format.JFR)
            .build()
    );

    runApplication<OnlineShopApplication>(*args)
}
