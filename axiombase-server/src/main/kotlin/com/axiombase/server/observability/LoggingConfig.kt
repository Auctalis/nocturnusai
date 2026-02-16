package com.axiombase.server.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory

/**
 * Programmatic logging configuration.
 * Called at startup to switch between text/JSON format and add file appender.
 *
 * Env vars:
 *   LOG_FORMAT=json|text    (default: text)
 *   LOG_LEVEL=DEBUG|INFO|WARN|ERROR  (default: INFO)
 *   LOG_FILE=/path/to/axiombase.log  (optional — enables rolling file)
 */
object LoggingConfig {

    private val logger = LoggerFactory.getLogger(LoggingConfig::class.java)

    fun configure() {
        val format = System.getenv("LOG_FORMAT")?.lowercase() ?: "text"
        val level = System.getenv("LOG_LEVEL")?.uppercase() ?: "INFO"
        val logFile = System.getenv("LOG_FILE")

        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)

        // Set root level
        rootLogger.level = Level.toLevel(level, Level.INFO)

        if (format == "json") {
            // Replace the default text appender with JSON
            rootLogger.detachAndStopAllAppenders()

            val jsonAppender = ConsoleAppender<ILoggingEvent>().apply {
                name = "CONSOLE_JSON"
                this.context = context
                encoder = LogstashEncoder().apply {
                    this.context = context
                    shortenedLoggerNameLength = 36
                    start()
                }
                start()
            }
            rootLogger.addAppender(jsonAppender)
            logger.info("Logging format: JSON (structured)")
        } else {
            logger.info("Logging format: text (human-readable)")
        }

        // Add rolling file appender if LOG_FILE is set
        if (!logFile.isNullOrBlank()) {
            val fileAppender = RollingFileAppender<ILoggingEvent>().apply {
                name = "FILE"
                this.context = context
                file = logFile

                val appender = this
                rollingPolicy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>().apply {
                    this.context = context
                    fileNamePattern = "$logFile.%d{yyyy-MM-dd}.%i.gz"
                    setMaxFileSize(FileSize.valueOf("50MB"))
                    maxHistory = 30
                    setTotalSizeCap(FileSize.valueOf("1GB"))
                    setParent(appender)
                    start()
                }

                // File always uses JSON for machine parsing
                encoder = LogstashEncoder().apply {
                    this.context = context
                    shortenedLoggerNameLength = 36
                    start()
                }
                start()
            }
            rootLogger.addAppender(fileAppender)
            logger.info("File logging enabled: $logFile (rolling, 50MB/file, 30 days, 1GB cap)")
        }

        // Set package-specific levels
        context.getLogger("io.netty").level = Level.WARN
        context.getLogger("io.ktor").level = Level.toLevel(level, Level.INFO)
        context.getLogger("com.axiombase").level = Level.toLevel(level, Level.INFO)

        logger.info("Log level: $level")
    }
}
