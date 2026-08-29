package dev.nytweetdeck.android.xapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class XApiProfile(
    val graphqlBaseUrl: String,
    val featureKeys: List<String>,
    val featureDefaults: Map<String, Boolean>,
    val operations: Map<String, GraphQlOperation>,
    val restBaseUrl: String = "https://api.twitter.com",
    val restEndpoints: Map<String, String> = emptyMap(),
) {
    data class GraphQlOperation(
        val operationId: String,
        val operationName: String,
        val type: OperationType,
        val featureKeys: List<String>,
        val fieldToggles: List<String>,
    )

    enum class OperationType {
        QUERY,
        MUTATION,
    }

    fun requireOperation(purpose: String): GraphQlOperation = operations[purpose]
        ?: throw XApiException("X Web API定義に${purpose}がありません。", 503)

    fun featuresFor(operation: GraphQlOperation): Map<String, Boolean> {
        val keys = operation.featureKeys.ifEmpty { featureKeys }
        return keys.associateWith { key ->
            featureDefaults[key]
                ?: throw XApiException("X Web API Feature定義に${key}がありません。", 503)
        }
    }

    companion object {
        private const val MAX_PROFILE_BYTES = 1024 * 1024
        private val VALUE_PATTERN = Regex("[A-Za-z0-9_-]{1,160}")
        private val URL_PATTERN = Regex("https://(?:x\\.com|api\\.twitter\\.com)(?:/.*)?")

        fun parse(profileJson: String, defaultsJson: String): XApiProfile {
            require(profileJson.toByteArray().size <= MAX_PROFILE_BYTES) { "X API定義が大きすぎます。" }
            require(defaultsJson.toByteArray().size <= MAX_PROFILE_BYTES) { "X Feature定義が大きすぎます。" }
            val profile = parseObject(profileJson, "X API定義")
            val defaultsRoot = parseObject(defaultsJson, "X Feature定義")
            val baseUrl = profile.requiredString("graphqlBaseUri")
            require(URL_PATTERN.matches(baseUrl)) { "GraphQL URLが不正です。" }
            val restBaseUrl = profile.requiredString("restBaseUri")
            require(URL_PATTERN.matches(restBaseUrl)) { "REST URLが不正です。" }
            val restEndpoints = profile.requiredObject("restEndpoints").mapValues { (key, value) ->
                require(key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,79}"))) { "REST用途名が不正です。" }
                val path = (value as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("REST endpointが文字列ではありません。")
                require(path.startsWith('/') && !path.contains("..") && path.length <= 300) {
                    "REST endpointが不正です。"
                }
                path
            }
            val globalFeatureKeys = profile.requiredArray("featureKeys").strings("featureKeys")
            val defaults = defaultsRoot.requiredObject("defaults").mapValues { (key, value) ->
                require(key.length in 1..200) { "Feature名が不正です。" }
                (value as? JsonPrimitive)?.booleanOrNull
                    ?: throw IllegalArgumentException("Feature既定値がbooleanではありません。")
            }
            val operations = profile.requiredObject("graphqlOperations").mapValues { (purpose, element) ->
                require(purpose.matches(Regex("[A-Za-z][A-Za-z0-9]{0,79}"))) { "API用途名が不正です。" }
                val value = element as? JsonObject
                    ?: throw IllegalArgumentException("GraphQL operationがobjectではありません。")
                val operationId = value.requiredString("operationId")
                val operationName = value.requiredString("operationName")
                require(VALUE_PATTERN.matches(operationId) && VALUE_PATTERN.matches(operationName)) {
                    "GraphQL operation形式が不正です。"
                }
                GraphQlOperation(
                    operationId = operationId,
                    operationName = operationName,
                    type = runCatching { OperationType.valueOf(value.requiredString("type")) }
                        .getOrElse { throw IllegalArgumentException("GraphQL operation種別が不正です。") },
                    featureKeys = value.optionalArray("featureKeys").strings("featureKeys"),
                    fieldToggles = value.optionalArray("fieldToggles").strings("fieldToggles"),
                )
            }
            require(operations.isNotEmpty()) { "GraphQL operationが空です。" }
            return XApiProfile(
                graphqlBaseUrl = baseUrl,
                featureKeys = globalFeatureKeys,
                featureDefaults = defaults,
                operations = operations,
                restBaseUrl = restBaseUrl,
                restEndpoints = restEndpoints,
            )
        }

        private fun parseObject(value: String, label: String): JsonObject = try {
            Json.parseToJsonElement(value).jsonObject
        } catch (exception: Exception) {
            throw IllegalArgumentException("${label}を解析できません。", exception)
        }

        private fun JsonObject.requiredString(name: String): String =
            (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("${name}がありません。")

        private fun JsonObject.requiredObject(name: String): JsonObject = this[name] as? JsonObject
            ?: throw IllegalArgumentException("${name}がobjectではありません。")

        private fun JsonObject.requiredArray(name: String): JsonArray = this[name] as? JsonArray
            ?: throw IllegalArgumentException("${name}がarrayではありません。")

        private fun JsonObject.optionalArray(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())

        private fun JsonArray.strings(label: String): List<String> = map { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.length in 1..200 }
                ?: throw IllegalArgumentException("${label}の値が不正です。")
        }
    }
}
