package com.garfiec.librechat.feature.agents.util

import com.garfiec.librechat.core.model.request.FunctionDefinition
import com.garfiec.librechat.core.model.request.FunctionTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses an OpenAPI specification and extracts function definitions.
 *
 * Supports both JSON and YAML formats. The parser first attempts JSON parsing,
 * and falls back to YAML if JSON fails. This matches the behavior of the
 * official LibreChat web frontend which uses js-yaml as a fallback.
 *
 * Supports OpenAPI 3.0+ and Swagger 2.0 specs.
 * The parser extracts servers[0].url as the domain, then iterates
 * over paths to build FunctionTool objects from each operation.
 */
object OpenApiSpecParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Attempts to parse the given spec string as an OpenAPI document.
     * Tries JSON first, then falls back to YAML.
     *
     * @param spec The raw OpenAPI specification text (JSON or YAML format).
     * @return [OpenApiParseResult] containing the domain, extracted functions, and any errors.
     */
    fun parse(spec: String): OpenApiParseResult {
        if (spec.isBlank()) {
            return OpenApiParseResult(
                domain = "",
                functions = emptyList(),
                errors = listOf("Specification is empty"),
            )
        }

        val root: JsonObject = try {
            parseSpecToJsonObject(spec)
        } catch (e: Exception) {
            return OpenApiParseResult(
                domain = "",
                functions = emptyList(),
                errors = listOf("Failed to parse specification: ${e.message}"),
            )
        }

        val errors = mutableListOf<String>()

        // Extract domain from servers[0].url
        val domain = extractDomain(root)
        if (domain.isBlank()) {
            errors.add("No server URL found. Add a 'servers' array with at least one entry.")
        } else if (!hasServersArray(root)) {
            // The Swagger-2.0 fallback below SYNTHESIZES a domain from `host` + `basePath`, and
            // mobile is the only client that does. The server never sees it: it validates the
            // spec first, and `validateAndParseOpenAPISpec` rejects anything without a `servers`
            // array before `validateActionDomain` is reached at all. So a spec that gets this far
            // is one the user can fill in an entire action editor against and only then be told
            // "Could not find a valid URL in `servers`" — a message about a field their spec was
            // never going to have. Say so here, while the spec is still the thing on screen.
            errors.add(
                "This is a Swagger 2.0 specification. The server requires an OpenAPI 3 " +
                    "'servers' array — convert the spec, or add 'servers' with the full base URL.",
            )
        }

        // Validate it looks like an OpenAPI spec
        val hasOpenApi = root.containsKey("openapi") || root.containsKey("swagger")
        if (!hasOpenApi) {
            errors.add("Missing 'openapi' or 'swagger' version field.")
        }

        // Extract paths
        val paths = root["paths"]?.jsonObject
        if (paths == null || paths.isEmpty()) {
            errors.add("No paths found in the specification.")
            return OpenApiParseResult(domain = domain, functions = emptyList(), errors = errors)
        }

        val functions = mutableListOf<FunctionTool>()

        for ((pathStr, pathItem) in paths) {
            val pathObj = try {
                pathItem.jsonObject
            } catch (_: Exception) {
                continue
            }

            // Path-level parameters (shared by all operations on this path)
            val pathParameters = pathObj["parameters"]?.let { parseParameterList(it) } ?: emptyList()

            for (method in listOf("get", "post", "put", "patch", "delete", "head", "options")) {
                val operation = pathObj[method]?.jsonObject ?: continue

                val operationId = operation["operationId"]?.jsonPrimitive?.content
                    ?: generateOperationId(method, pathStr)

                val description = operation["summary"]?.jsonPrimitive?.content
                    ?: operation["description"]?.jsonPrimitive?.content
                    ?: "$method $pathStr"

                // Merge path-level and operation-level parameters
                val operationParameters = operation["parameters"]
                    ?.let { parseParameterList(it) }
                    ?: emptyList()

                val allParameters = mergeParameters(pathParameters, operationParameters)

                // Extract request body schema properties
                val bodyProperties = extractRequestBodyProperties(operation)

                // Build the combined parameters JSON object
                val parametersObj = buildParametersObject(allParameters, bodyProperties)

                functions.add(
                    FunctionTool(
                        type = "function",
                        function = FunctionDefinition(
                            name = sanitizeOperationId(operationId),
                            description = description,
                            parameters = parametersObj,
                        ),
                    ),
                )
            }
        }

        if (functions.isEmpty()) {
            errors.add("No operations found in paths.")
        }

        return OpenApiParseResult(domain = domain, functions = functions, errors = errors)
    }

    /**
     * Extracts a display-friendly list of parsed functions showing name, method, and path.
     */
    fun extractFunctionInfo(spec: String): List<ParsedFunctionInfo> {
        if (spec.isBlank()) return emptyList()

        val root: JsonObject = try {
            parseSpecToJsonObject(spec)
        } catch (_: Exception) {
            return emptyList()
        }

        val paths = root["paths"]?.jsonObject ?: return emptyList()
        val infos = mutableListOf<ParsedFunctionInfo>()

        for ((pathStr, pathItem) in paths) {
            val pathObj = try { pathItem.jsonObject } catch (_: Exception) { continue }

            for (method in listOf("get", "post", "put", "patch", "delete", "head", "options")) {
                val operation = pathObj[method]?.jsonObject ?: continue

                val operationId = operation["operationId"]?.jsonPrimitive?.content
                    ?: generateOperationId(method, pathStr)

                val description = operation["summary"]?.jsonPrimitive?.content
                    ?: operation["description"]?.jsonPrimitive?.content
                    ?: ""

                infos.add(
                    ParsedFunctionInfo(
                        name = sanitizeOperationId(operationId),
                        method = method.uppercase(),
                        path = pathStr,
                        description = description,
                    ),
                )
            }
        }

        return infos
    }

    /** Whether the spec carries the OpenAPI 3 `servers` array the server insists on. */
    private fun hasServersArray(root: JsonObject): Boolean {
        val servers = try { root["servers"]?.jsonArray } catch (_: Exception) { null }
        if (servers.isNullOrEmpty()) return false
        val url = try {
            servers[0].jsonObject["url"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        return !url.isNullOrBlank()
    }

    /**
     * The action's `domain`, posted verbatim as `metadata.domain`.
     *
     * Taken from `servers[0].url` unchanged apart from a trailing slash, which is what keeps it
     * validating server-side: `validateActionDomain` compares it against that same string and,
     * since a newer server also binds it to the spec's effective port, any rewriting here — a
     * host-only reduction, a normalized default port — would turn a valid spec into a
     * "Domain mismatch" or "Port mismatch" the user cannot diagnose. An explicit default port
     * (`https://h:443/v1`) validates: WHATWG strips it from both sides and the port check then
     * compares 443 against the protocol's default.
     */
    private fun extractDomain(root: JsonObject): String {
        val servers = try { root["servers"]?.jsonArray } catch (_: Exception) { null }
        if (servers != null && servers.isNotEmpty()) {
            val url = try {
                servers[0].jsonObject["url"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
            if (url != null) return url.trimEnd('/')
        }
        // Fallback for Swagger 2.0: host + basePath
        val host = root["host"]?.jsonPrimitive?.content
        val basePath = root["basePath"]?.jsonPrimitive?.content ?: ""
        val schemes = try {
            root["schemes"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        } ?: "https"
        if (host != null) {
            return "$schemes://$host${basePath.trimEnd('/')}"
        }
        return ""
    }

    private fun generateOperationId(method: String, path: String): String {
        // Convert /api/v1/users/{id}/posts to api_v1_users_id_posts
        val sanitized = path
            .replace("{", "")
            .replace("}", "")
            .replace("/", "_")
            .replace("-", "_")
            .trim('_')
        return "${method}_$sanitized"
    }

    private fun sanitizeOperationId(operationId: String): String {
        // Ensure it's a valid function name: alphanumeric and underscores only
        return operationId
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    private data class ParameterInfo(
        val name: String,
        val description: String,
        val type: String,
        val required: Boolean,
        val location: String, // "query", "path", "header", "cookie"
    )

    private fun parseParameterList(element: JsonElement): List<ParameterInfo> {
        val array = try { element.jsonArray } catch (_: Exception) { return emptyList() }
        return array.mapNotNull { param ->
            try {
                val obj = param.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val description = obj["description"]?.jsonPrimitive?.content ?: ""
                val location = obj["in"]?.jsonPrimitive?.content ?: "query"
                val required = obj["required"]?.jsonPrimitive?.content?.toBoolean() ?: (location == "path")
                val type = obj["schema"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                    ?: obj["type"]?.jsonPrimitive?.content
                    ?: "string"
                ParameterInfo(name, description, type, required, location)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Merge path-level and operation-level parameters.
     * Operation-level parameters override path-level parameters with the same name + location.
     */
    private fun mergeParameters(
        pathParams: List<ParameterInfo>,
        operationParams: List<ParameterInfo>,
    ): List<ParameterInfo> {
        val merged = pathParams.associateBy { "${it.name}:${it.location}" }.toMutableMap()
        for (param in operationParams) {
            merged["${param.name}:${param.location}"] = param
        }
        return merged.values.toList()
    }

    private fun extractRequestBodyProperties(operation: JsonObject): JsonObject? {
        val requestBody = operation["requestBody"]?.jsonObject ?: return null
        val content = requestBody["content"]?.jsonObject ?: return null
        val jsonContent = content["application/json"]?.jsonObject ?: return null
        val schema = jsonContent["schema"]?.jsonObject ?: return null
        return schema["properties"]?.jsonObject
    }

    private fun buildParametersObject(
        parameters: List<ParameterInfo>,
        bodyProperties: JsonObject?,
    ): JsonObject {
        val properties = mutableMapOf<String, JsonElement>()
        val required = mutableListOf<String>()

        // Add query/path/header parameters
        for (param in parameters) {
            properties[param.name] = JsonObject(
                buildMap {
                    put("type", JsonPrimitive(param.type))
                    if (param.description.isNotBlank()) {
                        put("description", JsonPrimitive(param.description))
                    }
                },
            )
            if (param.required) {
                required.add(param.name)
            }
        }

        // Add body properties
        if (bodyProperties != null) {
            for ((key, value) in bodyProperties) {
                properties[key] = value
            }
        }

        return JsonObject(
            buildMap {
                put("type", JsonPrimitive("object"))
                if (properties.isNotEmpty()) {
                    put("properties", JsonObject(properties))
                }
                if (required.isNotEmpty()) {
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                }
            },
        )
    }

    /**
     * Parses a spec string to a [JsonObject], trying JSON first and falling back to YAML.
     *
     * This mirrors the official LibreChat web frontend behavior where `JSON.parse` is
     * attempted first and `js-yaml`'s `load()` is used as a fallback.
     *
     * @throws IllegalArgumentException if neither JSON nor YAML parsing succeeds, or if
     *         the parsed result is not an object (map).
     */
    private fun parseSpecToJsonObject(spec: String): JsonObject {
        // Try JSON first
        val jsonError: Exception
        try {
            return json.parseToJsonElement(spec).jsonObject
        } catch (e: Exception) {
            jsonError = e
        }

        // Fall back to YAML parsing (pure Kotlin, cross-platform)
        try {
            return parseYamlToJsonObject(spec)
        } catch (yamlError: Exception) {
            throw IllegalArgumentException(
                "Not valid JSON or YAML.\n" +
                    "JSON error: ${jsonError.message}\n" +
                    "YAML error: ${yamlError.message}",
                yamlError,
            )
        }
    }
}
