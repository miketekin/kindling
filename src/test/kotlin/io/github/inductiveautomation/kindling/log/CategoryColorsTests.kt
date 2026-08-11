package io.github.inductiveautomation.kindling.log

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import java.time.Instant

class CategoryColorsTests :
    FunSpec(
        {
            fun events(vararg loggers: String) = loggers.map { logger ->
                WrapperLogEvent(
                    timestamp = Instant.EPOCH,
                    message = "",
                    logger = logger,
                )
            }

            fun systemEvents(vararg threads: String) = threads.map { thread ->
                SystemLogEvent(
                    timestamp = Instant.EPOCH,
                    message = "",
                    logger = "logger",
                    thread = thread,
                    level = Level.INFO,
                    mdc = emptyList(),
                    stacktrace = emptyList(),
                )
            }

            val threadKey = { event: LogEvent -> (event as? SystemLogEvent)?.thread }

            test("Keys are ranked by frequency") {
                CategoryColors.topKeys(events("a", "b", "b", "c", "c", "c"), LogEvent::logger) shouldContainExactly
                    mapOf("c" to 0, "b" to 1, "a" to 2)
            }

            test("Ties are broken by name") {
                CategoryColors.topKeys(events("b", "a"), LogEvent::logger) shouldContainExactly
                    mapOf("a" to 0, "b" to 1)
            }

            test("Ranks are limited to five keys") {
                CategoryColors.topKeys(events("a", "a", "b", "b", "c", "c", "d", "d", "e", "e", "f"), LogEvent::logger)
                    .shouldContainExactly(mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3, "e" to 4))
            }

            test("Empty data yields no ranks") {
                CategoryColors.topKeys(emptyList(), LogEvent::logger).shouldBeEmpty()
            }

            test("Null keys are skipped") {
                CategoryColors.topKeys(events("a", "b"), threadKey).shouldBeEmpty()
            }

            test("Threads are ranked by frequency") {
                CategoryColors.topKeys(systemEvents("worker-1", "worker-2", "worker-2"), threadKey) shouldContainExactly
                    mapOf("worker-2" to 0, "worker-1" to 1)
            }
        },
    )
