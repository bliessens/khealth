package dev.hayden

import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.path
import io.ktor.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class Check(val checkName: String, val check: CheckFunction)
typealias CheckFunction = suspend () -> Boolean

private var healthChecks = linkedSetOf<Check>()
private var readyChecks = linkedSetOf<Check>()

class KHealth private constructor(private val config: KHealthConfiguration) {

    /**
     * Interceptor that handles all http requests. If either the health check or ready endpoint are
     * called it will return a custom response with the result of each custom check if any are defined.
     */
    fun interceptor(pipeline: ApplicationCallPipeline) {
        pipeline.intercept(ApplicationCallPipeline.Call) {
            val path = call.request.path()
            if (config.readyCheckEnabled && path == config.readyCheckPath) {
                val (status, responseBody) = processChecks(readyChecks)
                call.respondText(responseBody, ContentType.Application.Json, status)
                finish()
            } else if (config.healthCheckEnabled && path == config.healthCheckPath) {
                val (status, responseBody) = processChecks(healthChecks)
                call.respondText(responseBody, ContentType.Application.Json, status)
                finish()
            }

            return@intercept
        }
    }

    /**
     * Process the checks for a specific endpoint returning the evaluation of each check as a JSON string
     * with a status code. If any of the checks are false, then a [HttpStatusCode.InternalServerError] will be returned,
     * otherwise a [HttpStatusCode.OK] will be returned.
     * @param checkLinkedList A linkedlist of [Check] to be run.
     * @return A pair including a [HttpStatusCode] and a JSON encoded string of each check with their result.
     */
    private suspend fun processChecks(checkLinkedList: LinkedHashSet<Check>): Pair<HttpStatusCode, String> {
        val checksWithResults = checkLinkedList.associate { Pair(it.checkName, it.check.invoke()) }
        val status = if (checksWithResults.containsValue(false)) {
            HttpStatusCode.InternalServerError
        } else HttpStatusCode.OK
        return Pair(status, Json.encodeToString(checksWithResults))
    }

    /**
     * KHealth is a small health check library designed for Ktor. It supports both a 'health' endpoint and
     * a 'ready' endpoint. Both can be customized with custom checks or completely disabled through [KHealthConfiguration].
     * @author Hayden Meloche
     */
    companion object Feature : ApplicationFeature<ApplicationCallPipeline, KHealthConfiguration, KHealth> {
        override val key = AttributeKey<KHealth>("KHealth")
        override fun install(
            pipeline: ApplicationCallPipeline,
            configure: KHealthConfiguration.() -> Unit
        ) = KHealth(KHealthConfiguration().apply(configure)).apply { interceptor(pipeline) }
    }
}

/**
 * Configuration class used to configure [KHealth]. No values are required to be passed in as defaults
 * are provided.
 */
class KHealthConfiguration internal constructor() {
    /**
     * The path of the health check endpoint. Defaults to "/health".
     */
    var healthCheckPath = "/health"
        set(value) {
            field = normalizePath(value)
        }

    /**
     * The path of the ready check endpoint. Defaults to "/ready".
     */
    var readyCheckPath = "/ready"
        set(value) {
            field = normalizePath(value)
        }

    /**
     * Controls whether the health check endpoint is enabled or disabled. Defaults to true.
     */
    var healthCheckEnabled = true

    /**
     * Controls whether the ready check endpoint is enabled or disabled. Defaults to true.
     */
    var readyCheckEnabled = true

    /**
     * Builder function to add checks to the health endpoint.
     */
    fun healthChecks(init: CheckBuilder.() -> Unit) {
        healthChecks = CheckBuilder().apply(init).checks
    }

    /**
     * Builder function to add checks to the ready endpoint.
     */
    fun readyChecks(init: CheckBuilder.() -> Unit) {
        readyChecks = CheckBuilder().apply(init).checks
    }
}

/**
 * A builder class used to create descriptive DSL for adding checks to an endpoint.
 * @see healthChecks
 * @see readyChecks
 */
class CheckBuilder {
    val checks = linkedSetOf<Check>()

    /**
     * A custom check that will be run on every call the customized endpoint.
     * If any check [CheckFunction] returns false, a 500 will be returned.
     * @param name The name of the check in the GET response
     * @param check A boolean returning function that supplies the result of the check
     */
    fun check(name: String, check: CheckFunction) {
        checks.add(Check(name, check))
    }
}

/**
 * Normalizes the provided URI and asserts that it's not blank.
 * The provided URI can have a slash, if it does not then one will be added to normalize it.
 * @param uri The provided URI
 */
private fun normalizePath(uri: String): String {
    return if (uri[0] == '/') {
        assertNotBlank(uri.removePrefix("/"))
        uri
    } else {
        assertNotBlank(uri)
        "/$uri"
    }
}

/**
 * Asserts the provided path value is not blank.
 */
private fun assertNotBlank(value: String) {
    require(value.isNotBlank()) {
        "The provided path must not be empty"
    }
}
