package com.axiombase.server.routes

import com.axiombase.server.DatabaseManager
import com.axiombase.server.HealthChecker
import com.axiombase.server.LlmTxtGenerator
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Route.observabilityRoutes(appMicrometerRegistry: PrometheusMeterRegistry, dbManager: DatabaseManager, storageDir: File) {
    get("/metrics") {
         call.application.environment.log.info("Endpoint /metrics hit")
         call.respondText(appMicrometerRegistry.scrape())
    }

    get("/health") {
        call.application.environment.log.debug("Endpoint /health hit")
        val healthStatus = HealthChecker.check(dbManager, storageDir)
        val statusCode = if (healthStatus.status == "unhealthy") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
        call.respond(statusCode, healthStatus)
    }

    get("/health/live") {
        call.respondText("OK")
    }

    get("/health/ready") {
        val healthStatus = HealthChecker.check(dbManager, storageDir)
        val statusCode = if (healthStatus.status == "unhealthy") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
        call.respond(statusCode, healthStatus)
    }

    get("/llm.txt") {
        call.application.environment.log.info("Endpoint /llm.txt hit")
        call.respondText(LlmTxtGenerator.generate(application), contentType = ContentType.Text.Plain)
    }

    get("/userguide") {
        call.application.environment.log.info("Endpoint /userguide hit")
        val content = Thread.currentThread().contextClassLoader.getResourceAsStream("USERGUIDE.md")
            ?.bufferedReader()?.readText()
        if (content != null) {
            call.respondText(content, contentType = ContentType.Text.Plain)
        } else {
            call.respondText("USERGUIDE.md not found", status = HttpStatusCode.NotFound)
        }
    }
}
