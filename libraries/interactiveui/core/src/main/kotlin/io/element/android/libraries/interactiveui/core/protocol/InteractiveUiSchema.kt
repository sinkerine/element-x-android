/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

import com.ibm.icu.text.IDNA
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
internal class InteractiveUiSchema private constructor(private val root: JsonObject) {
    private val definitions = root.getValue("\$defs").jsonObject
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun read(value: JsonElement): JsonElement = read(value, root, "")

    private fun resolve(rule: JsonObject): JsonObject = rule.string("\$ref")?.let {
        definitions.getValue(it.substringAfterLast('/')).jsonObject
    } ?: rule

    private fun read(raw: JsonElement, unresolved: JsonObject, path: String): JsonElement {
        val rule = resolve(unresolved)
        val value = if (raw is JsonPrimitive && raw.isString) {
            if (hasForbiddenControls(raw.content)) fail(path, InteractiveUiErrorCode.BadValue)
            JsonPrimitive(jsTrim(raw.content))
        } else {
            raw
        }
        val alternatives = (rule["anyOf"] ?: rule["oneOf"]) as? JsonArray
        if (alternatives != null) {
            val selected = if (value is JsonObject) {
                alternatives.firstOrNull { alternative ->
                val properties = resolve(alternative.jsonObject)["properties"] as? JsonObject
                properties?.any { (key, child) ->
                    val constant = child.jsonObject["const"]
                    val candidate = value[key]?.let { if (it is JsonPrimitive && it.isString) JsonPrimitive(jsTrim(it.content)) else it }
                    constant != null && equalScalar(candidate, constant)
                } == true
            }
            } else {
                null
            }
            if (selected != null) return read(value, selected.jsonObject, path)
            if (value is JsonObject && value.string("type") != null && rule === definitions["node"]) {
                return read(value, definitions.getValue("unknown").jsonObject, path)
            }
            var firstError: InteractiveUiValidationException? = null
            for (alternative in alternatives) {
                try {
                    return read(value, alternative.jsonObject, path)
                } catch (error: InteractiveUiValidationException) {
                    if (firstError == null) firstError = error
                }
            }
            if (firstError != null && firstError.error.code !in listOf(InteractiveUiErrorCode.BadType, InteractiveUiErrorCode.NotObject)) {
                throw firstError
            }
            fail(path, InteractiveUiErrorCode.BadType)
        }
        (rule["allOf"] as? JsonArray)?.forEach { read(value, it.jsonObject, path) }
        (rule["if"] as? JsonObject)?.let { condition ->
            val branch = if (matches(value, condition, path)) rule["then"] else rule["else"]
            if (branch is JsonObject) read(value, branch, path)
        }
        (rule["not"] as? JsonObject)?.let {
            if (matches(value, it, path)) fail(path, InteractiveUiErrorCode.BadValue)
        }
        val customError = rule.string("x-error")?.let(InteractiveUiErrorCode::fromWire) ?: InteractiveUiErrorCode.BadValue
        if ("const" in rule && !equalScalar(value, rule["const"])) fail(path, customError)
        (rule["enum"] as? JsonArray)?.let { if (it.none { entry -> equalScalar(value, entry) }) fail(path, customError) }
        rule["type"]?.let { type ->
            val types = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (types.none { matchesType(value, it) }) {
                fail(path, if (type == JsonPrimitive("object")) InteractiveUiErrorCode.NotObject else InteractiveUiErrorCode.BadType)
            }
        }
        if (value is JsonPrimitive && value.isString) {
            if (rule === definitions["https"] && !isHttpsUrl(value.content)) fail(path, InteractiveUiErrorCode.BadUrl)
            val length = codePointLength(value.content)
            if (rule.number("maxLength")?.let { length > it } == true) fail(path, InteractiveUiErrorCode.TooLong)
            if (rule.number("minLength")?.let { length < it } == true) fail(path, customError)
            rule.string("pattern")?.let { pattern ->
                val regex = patterns.getOrPut(pattern) { schemaRegex(pattern) }
                if (!regex.containsMatchIn(value.content)) fail(path, customError)
            }
        }
        if (value is JsonPrimitive && !value.isString && value.doubleOrNull != null) {
            val number = value.double
            if (rule.number("minimum")?.let { number < it } == true || rule.number("maximum")?.let { number > it } == true ||
                rule.number("exclusiveMinimum")?.let { number <= it } == true
            ) {
                fail(path, InteractiveUiErrorCode.BadValue)
            }
        }
        if (value is JsonArray) {
            if (rule.number("maxItems")?.let { value.size > it } == true) fail(path, InteractiveUiErrorCode.TooMany)
            if (rule.number("minItems")?.let { value.size < it } == true) fail(path, InteractiveUiErrorCode.BadValue)
            val items = rule["items"] as? JsonObject
            val out = JsonArray(value.mapIndexed { index, child -> if (items != null) read(child, items, "$path[$index]") else child })
            if (rule["uniqueItems"] == JsonPrimitive(true) && out.map(::jsJsonString).toSet().size != out.size) {
                fail(path, InteractiveUiErrorCode.DuplicateId)
            }
            return out
        }
        if (value is JsonObject) {
            (rule["required"] as? JsonArray)?.forEach {
                val key = it.jsonPrimitive.content
                if (key !in value) fail(field(path, key), InteractiveUiErrorCode.MissingField)
            }
            if (rule.number("maxProperties")?.let { value.size > it } == true) fail(path, InteractiveUiErrorCode.TooMany)
            val out = linkedMapOf<String, JsonElement>()
            for (key in jsKeyOrder(value.keys)) {
                val names = rule["propertyNames"] as? JsonObject
                if (names != null && read(JsonPrimitive(key), names, field(path, key)) != JsonPrimitive(key)) {
                    fail(field(path, key), InteractiveUiErrorCode.BadId)
                }
                val childRule = (rule["properties"] as? JsonObject)?.get(key) as? JsonObject ?: rule["additionalProperties"] as? JsonObject
                if (childRule != null) {
                    out[key] = read(value.getValue(key), childRule, field(path, key))
                } else if (rule["additionalProperties"] == JsonPrimitive(false)) {
                    fail(field(path, key), InteractiveUiErrorCode.BadValue)
                }
            }
            return JsonObject(out)
        }
        return value
    }

    private fun matches(value: JsonElement, rule: JsonObject, path: String): Boolean = try {
        read(value, rule, path)
        true
    } catch (_: InteractiveUiValidationException) {
        false
    }

    companion object {
        val V1: InteractiveUiSchema by lazy {
            val text = checkNotNull(InteractiveUiSchema::class.java.getResourceAsStream("/interactive-ui/interactive-ui-v1.schema.json"))
                .bufferedReader()
                .use { it.readText() }
            InteractiveUiSchema(Json.parseToJsonElement(text).jsonObject)
        }
    }
}

internal fun schemaRegex(pattern: String): Regex {
    val translated = pattern.replace("\\s", JS_WHITESPACE_CLASS)
    // Java's $ also matches before a final U+0085; ECMAScript's non-multiline $ requires the absolute end.
    val hasEndAnchor = translated.endsWith('$') && translated.dropLast(1).takeLastWhile { it == '\\' }.length % 2 == 0
    return Regex(if (hasEndAnchor) translated.dropLast(1) + "\\z" else translated)
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
private fun equalScalar(a: JsonElement?, b: JsonElement?): Boolean {
    val firstNumber = (a as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    val secondNumber = (b as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    if (firstNumber != null && secondNumber != null) return firstNumber == secondNumber
    return a == b
}
private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
    "null" -> value === JsonNull
    "array" -> value is JsonArray
    "object" -> value is JsonObject
    "string" -> value is JsonPrimitive && value.isString
    "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
    "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
    "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let(::isSafeInteger) == true
    else -> false
}

internal fun isHttpsUrl(value: String): Boolean {
    // WHATWG strips literal ASCII tabs/newlines before parsing, never percent-decoded controls.
    // Keep this input separate: control validation, schema length/pattern and model output use the original string.
    val input = value.filterNot { it == '\t' || it == '\n' || it == '\r' }
    // Special URLs skip all initial slash/backslash separators; the schema still checks the original input.
    val schemeEnd = input.indexOf(':')
    if (schemeEnd < 0) return false
    var authorityStart = schemeEnd + 1
    while (authorityStart < input.length && input[authorityStart] in "/\\") authorityStart++
    val authorityEnd = input.indexOfAny(charArrayOf('/', '\\', '?', '#'), authorityStart)
        .let { if (it < 0) input.length else it }
    val authority = input.substring(authorityStart, authorityEnd)
    val rawHostAndPort = authority.substringAfterLast('@')
    val isIpv6 = rawHostAndPort.startsWith('[')
    val rawHost = if (isIpv6) {
        val closingBracket = rawHostAndPort.indexOf(']')
        if (closingBracket < 0) return false
        rawHostAndPort.substring(0, closingBracket + 1)
    } else {
        rawHostAndPort.substringBefore(':')
    }
    val host = if (isIpv6) {
        rawHost
    } else {
        val decoded = try {
            URLDecoder.decode(rawHost.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (decoded.all { it.code < 128 }) {
            // Node URL's ASCII domain path preserves even non-decodable ACE labels.
            decoded.lowercase(java.util.Locale.ROOT)
        } else {
            val info = IDNA.Info()
            val ascii = StringBuilder()
            IDNA.getUTS46Instance(IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII)
                .nameToASCII(decoded, ascii, info)
            // URL's non-strict domain-to-ASCII does not enforce DNS lengths, empty labels or hyphen positions.
            val ignored = setOf(
                IDNA.Error.EMPTY_LABEL,
                IDNA.Error.LABEL_TOO_LONG,
                IDNA.Error.DOMAIN_NAME_TOO_LONG,
                IDNA.Error.LEADING_HYPHEN,
                IDNA.Error.TRAILING_HYPHEN,
                IDNA.Error.HYPHEN_3_4
            )
            if (info.errors.any { it !in ignored }) return false
            ascii.toString()
        }
    }
    if (host.isEmpty() || !isIpv6 && !isWhatwgDomain(host)) return false
    val port = rawHostAndPort.removePrefix(rawHost)
    if (port.isNotEmpty() && !port.matches(Regex(":[0-9]*"))) return false
    // WHATWG permits port zero; OkHttp's parser requires a positive port. Validate the rest unchanged.
    val candidatePort = if (port.matches(Regex(":0+"))) ":1" else port
    // Host validation above follows URL's non-strict rules, which permit empty labels and long DNS names.
    // Use a placeholder for domains so OkHttp only checks credentials, scheme and port. IPv6 still needs its parser.
    val candidateHost = if (isIpv6) host else "example.org"
    val prefix = input.substring(0, authorityEnd).removeSuffix(rawHostAndPort)
    val url = (prefix + candidateHost + candidatePort + input.substring(authorityEnd)).toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.host.isNotEmpty() && url.username.isEmpty() && url.password.isEmpty()
}

// WHATWG treats domains ending in a number as IPv4, including legacy octal/hex forms.
// OkHttp accepts such strings as domains, so apply the URL standard's IPv4 bounds too.
private fun isWhatwgDomain(host: String): Boolean {
    if (host.any { it <= ' ' || it in "#%/:<>?@[\\\\]^|" || it == '\u007f' }) return false
    val parts = host.removeSuffix(".").split('.')
    fun ipv4Number(part: String): Long? {
        val radix: Int
        val digits: String
        when {
            part.startsWith("0x", ignoreCase = true) -> {
                radix = 16
                digits = part.drop(2)
            }
            part.length >= 2 && part.startsWith('0') -> {
                radix = 8
                digits = part.drop(1)
            }
            else -> {
                radix = 10
                digits = part
            }
        }
        if (digits.isEmpty()) return 0L
        if (digits.any { it.digitToIntOrNull(radix) == null }) return null
        return digits.toLongOrNull(radix)
    }
    val last = parts.last()
    val hexadecimal = last.startsWith("0x", ignoreCase = true) && last.drop(2).all { it.digitToIntOrNull(16) != null }
    val endsInNumber = last.isNotEmpty() && (last.all { it in '0'..'9' } || hexadecimal || ipv4Number(last) != null)
    if (!endsInNumber) return true
    if (parts.size > 4) return false
    if (parts.any(String::isEmpty)) return false
    val numbers = parts.map { ipv4Number(it) ?: return false }
    if (numbers.dropLast(1).any { it > 255 }) return false
    val lastBits = 8 * (5 - numbers.size)
    val lastLimit = 1L shl lastBits
    return numbers.last() < lastLimit
}

internal fun jsJsonString(value: JsonElement): String = when (value) {
    is JsonObject -> jsKeyOrder(value.keys).joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + jsJsonString(value.getValue(it)) }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::jsJsonString)
    is JsonPrimitive -> if (!value.isString && value.doubleOrNull != null) jsNumberToString(value.double) else value.toString()
}
