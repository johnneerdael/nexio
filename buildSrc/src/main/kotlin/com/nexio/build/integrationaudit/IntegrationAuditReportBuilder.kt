package com.nexio.build.integrationaudit

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.time.Instant

fun File.normalizedRelativeTo(base: File): String =
    relativeTo(base).path.replace(File.separatorChar, '/')

fun csvCell(value: Any?): String {
    val text = value?.toString().orEmpty()
    return if (text.any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else {
        text
    }
}

fun csvLine(values: List<Any?>): String = values.joinToString(",") { csvCell(it) }

fun readIntegrationAuditSources(root: File): List<Pair<File, String>> =
    root.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .map { file -> file to file.readText() }
        .toList()

fun readMetadataRouterPrerequisiteShapeIds(file: File): Set<String> =
    file.readLines()
        .map { it.substringBefore("#").trim() }
        .filter { it.isNotBlank() }
        .toSet()

fun extractIntegrationProviders(providerFile: File): List<String> {
    val body = providerFile.readText().substringAfter("enum class IntegrationProvider").substringAfter("{").substringBefore("}")
    return Regex("""\b([A-Z][A-Z0-9_]*)\b""")
        .findAll(body)
        .map { it.groupValues[1] }
        .filterNot { it in setOf("ENUM", "CLASS") }
        .distinct()
        .toList()
}

fun extractExpectedApiShapes(shapesFile: File): Map<String, Map<String, String>> {
    if (!shapesFile.exists()) return emptyMap()
    val lines = shapesFile.readLines()
    val source = shapesFile.readText()
    val shapes = linkedMapOf<String, MutableMap<String, String>>()
    var current: String? = null
    val shapeIdPattern = Regex("""^([a-z0-9][a-z0-9_.-]+):\s*$""")
    val scalarPattern = Regex("""^\s{2}([A-Za-z0-9_]+):\s*"?([^"#]+?)"?\s*$""")
    lines.forEach { line ->
        shapeIdPattern.matchEntire(line)?.let { match ->
            current = match.groupValues[1]
            shapes.getOrPut(match.groupValues[1]) { linkedMapOf() }
            return@forEach
        }
        val shapeId = current ?: return@forEach
        scalarPattern.matchEntire(line)?.let { match ->
            shapes.getValue(shapeId)[match.groupValues[1]] = match.groupValues[2].trim()
        }
    }
    Regex("""(?ms)^([a-z0-9][a-z0-9_.-]+):\s*\n(.*?)(?=^[a-z0-9][a-z0-9_.-]+:\s*$|\z)""")
        .findAll(source)
        .forEach { match ->
            shapes[match.groupValues[1]]?.set("rawBlock", match.groupValues[2])
        }
    return shapes
}

fun extractIntegrationContractShapes(contractsFile: File): Map<String, Map<String, String>> {
    if (!contractsFile.exists()) return emptyMap()
    val source = contractsFile.readText()
    val apiSection = source.substringAfter("apiShapes:", "")
    return Regex("""(?ms)^  ([a-z0-9][a-z0-9_.-]+):\n(.*?)(?=^  [a-z0-9][a-z0-9_.-]+:|\z)""")
        .findAll(apiSection)
        .associate { match ->
            val shapeId = match.groupValues[1]
            val block = match.groupValues[2]
            fun value(pattern: String): String =
                Regex(pattern, RegexOption.MULTILINE).find(block)?.groupValues?.get(1).orEmpty()
            shapeId to linkedMapOf(
                "provider" to value("""^    provider:\s*(\S+)"""),
                "lifecycleStatus" to value("""^    lifecycleStatus:\s*(\S+)"""),
                "metadataRouterRequired" to value("""^    metadataRouterRequired:\s*(\S+)"""),
                "method" to value("""^    method:\s*(\S+)"""),
                "path" to value("""^    path:\s*"?([^"\n]+)"?"""),
                "headerPolicy" to value("""^    headerPolicy:\s*(\S+)"""),
                "cacheContract" to value("""^    cacheContract:\s*(\S+)"""),
                "defaultCachePolicy" to value("""^      defaultCachePolicy:\s*(\S+)"""),
                "scope" to value("""^      scope:\s*(\S+)"""),
                "include" to value("""^      include:\s*\[([^\]]*)]"""),
                "forbidden" to value("""^      forbidden:\s*\[([^\]]*)]"""),
                "bestPracticeRules" to value("""^    bestPracticeRules:\s*\[([^\]]*)]""")
            )
        }
}

fun extractIntegrationCacheContracts(contractsFile: File): Map<String, Map<String, String>> {
    if (!contractsFile.exists()) return emptyMap()
    val source = contractsFile.readText()
    val cacheSection = source.substringAfter("cacheContracts:", "").substringBefore("apiShapes:")
    return Regex("""(?ms)^  ([a-z0-9_-]+):\n(.*?)(?=^  [a-z0-9_-]+:|\z)""")
        .findAll(cacheSection)
        .associate { match ->
            val contractId = match.groupValues[1]
            val block = match.groupValues[2]
            fun value(key: String): String =
                Regex("""(?m)^    ${Regex.escape(key)}:\s*(\S+)""").find(block)?.groupValues?.get(1).orEmpty()
            contractId to linkedMapOf(
                "cachePolicy" to value("cachePolicy"),
                "ttl" to value("ttl"),
                "staleAfterExpiry" to value("staleAfterExpiry"),
                "requiresTypedCodec" to value("requiresTypedCodec"),
                "retention" to value("retention")
            )
        }
}

fun buildProviderContractProvenanceRows(
    contractsFile: File,
    contractShapes: Map<String, Map<String, String>>
): List<IntegrationProviderContractProvenanceRow> {
    if (!contractsFile.exists()) return emptyList()
    val source = contractsFile.readText()
    val reviewedAt = Regex("""(?m)^reviewedAt:\s*"?([^"\n]+)"?""").find(source)?.groupValues?.get(1).orEmpty()
    val sourceSection = source.substringAfter("contractSources:", "").substringBefore("headerPolicies:")
    return Regex("""(?ms)^  ([A-Z][A-Z0-9_]*):\n(.*?)(?=^  [A-Z][A-Z0-9_]*:|\z)""")
        .findAll(sourceSection)
        .map { match ->
            val provider = match.groupValues[1]
            val block = match.groupValues[2]
            fun value(key: String): String =
                Regex("""(?m)^    ${Regex.escape(key)}:\s*"?([^"\n]+)"?""").find(block)?.groupValues?.get(1).orEmpty()
            val providerShapes = contractShapes.values.filter { it["provider"] == provider }
            val issues = buildList {
                if (value("lifecycleStatus").isBlank()) add("missing lifecycleStatus")
                if (value("sourceFile").isBlank()) add("missing sourceFile")
                if (value("sourceFile").startsWith("/")) add("sourceFile must be repo relative")
                if (value("sourceType").isBlank()) add("missing sourceType")
                if (value("reviewer").isBlank()) add("missing reviewer")
                if (providerShapes.isEmpty()) add("no shape contracts")
            }
            IntegrationProviderContractProvenanceRow(
                provider = provider,
                lifecycleStatus = value("lifecycleStatus"),
                sourceFile = value("sourceFile"),
                sourceType = value("sourceType"),
                reviewedAt = reviewedAt,
                reviewer = value("reviewer"),
                usedShapes = providerShapes.size,
                activeRequiredShapes = providerShapes.count { it["lifecycleStatus"] == "ACTIVE_REQUIRED" },
                verdict = if (issues.isEmpty()) "PASS" else "FAIL"
            )
        }
        .toList()
}

fun buildCachePolicyContractRows(
    contractShapes: Map<String, Map<String, String>>,
    cacheContracts: Map<String, Map<String, String>>,
    specs: List<IntegrationAuditSpecRow>
): List<IntegrationCachePolicyContractRow> =
    contractShapes.map { (shapeId, shape) ->
        val spec = specs.firstOrNull { it.apiShapeId == shapeId }
        val cacheContractId = shape["cacheContract"].orEmpty()
        val cacheContract = cacheContracts[cacheContractId].orEmpty()
        val expectedPolicy = shape["defaultCachePolicy"].orEmpty().ifBlank { cacheContract["cachePolicy"].orEmpty() }
        val issues = buildList {
            if (cacheContractId.isBlank()) add("missing cache contract")
            if (cacheContract.isEmpty()) add("unknown cache contract")
            if (expectedPolicy == "CacheFirst" && cacheContract["requiresTypedCodec"] != "true") add("CacheFirst contract does not require typed codec")
            if (expectedPolicy == "CacheFirst" && spec != null && spec.codec.isNullOrBlank()) add("CacheFirst runtime spec has no codec")
            if (expectedPolicy == "CacheFirst" && cacheContract["ttl"].isNullOrBlank()) add("CacheFirst contract missing ttl")
            if (expectedPolicy == "CacheFirst" && cacheContract["staleAfterExpiry"].isNullOrBlank()) add("CacheFirst contract missing staleAfterExpiry")
            if (spec?.cacheKeyTemplate?.contains("hashCode") == true) add("cache key template uses hashCode")
        }
        IntegrationCachePolicyContractRow(
            apiShapeId = shapeId,
            provider = shape["provider"].orEmpty(),
            expectedCacheContract = cacheContractId,
            expectedCachePolicy = expectedPolicy,
            actualCachePolicy = spec?.cachePolicy,
            ttl = cacheContract["ttl"].orEmpty(),
            staleAfterExpiry = cacheContract["staleAfterExpiry"].orEmpty(),
            scope = shape["scope"].orEmpty(),
            codec = spec?.codec,
            cacheKeyTemplate = normalizeAuditKeyTemplate(spec?.cacheKeyTemplate),
            verdict = if (issues.isEmpty()) "PASS" else "FAIL",
            issues = issues
        )
    }

fun buildCacheKeyVaryRows(
    contractShapes: Map<String, Map<String, String>>,
    specs: List<IntegrationAuditSpecRow>
): List<IntegrationCacheKeyVaryRow> =
    contractShapes.map { (shapeId, shape) ->
        val spec = specs.firstOrNull { it.apiShapeId == shapeId }
        val forbidden = shape["forbidden"].orEmpty()
        val template = normalizeAuditKeyTemplate(spec?.cacheKeyTemplate)
        val issues = buildList {
            if (shape["include"].isNullOrBlank()) add("missing include list")
            if (!forbidden.contains("raw API key")) add("missing raw API key forbidden rule")
            if (!forbidden.contains("apiKey.hashCode")) add("missing hashCode forbidden rule")
            if (template?.contains("hashCode") == true) add("actual template contains hashCode")
        }
        IntegrationCacheKeyVaryRow(
            apiShapeId = shapeId,
            include = shape["include"].orEmpty(),
            forbidden = forbidden,
            actualTemplate = template,
            verdict = if (issues.isEmpty()) "PASS" else "FAIL",
            issues = issues
        )
    }

fun executableBestPracticeVerdict(
    rule: String,
    input: BestPracticeValidationInput
): BestPracticeValidationResult =
    when (rule) {
        "runtime-contract-baseline" -> validateRuntimeContractBaseline(input)
        "tmdb-detail-append-bundle" -> validateTmdbDetailAppendBundle(input)
        "tvdb-season-batch" -> validateTvdbSeasonBatch(input)
        "kitsu-top-level-castings-include-graph" -> validateKitsuCastingsIncludeGraph(input)
        "ratings-batch-where-available" -> validateRatingsBatchWhereAvailable(input)
        "poster-cache-key-all-output-varying-inputs" -> validatePosterCacheKeyVary(input)
        else -> BestPracticeValidationResult("FAIL", "missing executable validator for rule $rule")
    }

private fun validateRuntimeContractBaseline(input: BestPracticeValidationInput): BestPracticeValidationResult =
    if (input.spec?.apiShapeId == input.shapeId) {
        BestPracticeValidationResult("PASS", "runtime spec mapped to contract")
    } else {
        BestPracticeValidationResult("WARN", "shape has no active runtime spec")
    }

private fun validateTmdbDetailAppendBundle(input: BestPracticeValidationInput): BestPracticeValidationResult {
    val expected = input.shape["rawBlock"].orEmpty()
    val expectedPath = input.shape["path"].orEmpty()
    val hasRequiredBundle = listOf("append_to_response", "credits", "images", "external_ids").all {
        expected.contains(it)
    }
    val hasDetailPath = expectedPath == "/movie/{movie_id}" ||
        expectedPath == "/tv/{tv_id}" ||
        expectedPath == "/tv/{series_id}"
    return if (hasRequiredBundle && hasDetailPath) {
        BestPracticeValidationResult("PASS", "TMDB detail contract includes required append_to_response enrichment bundle")
    } else {
        BestPracticeValidationResult("WARN", "TMDB detail contract is missing required append_to_response evidence")
    }
}

private fun validateTvdbSeasonBatch(input: BestPracticeValidationInput): BestPracticeValidationResult {
    val expectedPath = input.shape["path"].orEmpty()
    val bulkShape = input.shape["bulkShape"].orEmpty()
    val seasonBatchEvidence = expectedPath.contains("/series/{id}/episodes") &&
        bulkShape.contains("season batch", ignoreCase = true)
    return if (seasonBatchEvidence) {
        BestPracticeValidationResult("PASS", "TVDB season endpoint evidence is batch-oriented")
    } else {
        BestPracticeValidationResult("WARN", "TVDB season batch endpoint evidence missing")
    }
}

private fun validateKitsuCastingsIncludeGraph(input: BestPracticeValidationInput): BestPracticeValidationResult {
    val expectedPath = input.shape["path"].orEmpty()
    val rawBlock = input.shape["rawBlock"].orEmpty()
    val hasTopLevelRoute = expectedPath == "/castings"
    val hasIncludeGraph = rawBlock.contains("filter[mediaId]") &&
        rawBlock.contains("include") &&
        rawBlock.contains("person") &&
        rawBlock.contains("character")
    return if (hasTopLevelRoute && hasIncludeGraph) {
        BestPracticeValidationResult("PASS", "Kitsu castings contract uses top-level castings with person and character include graph")
    } else {
        BestPracticeValidationResult("WARN", "Kitsu castings contract lacks top-level route or include graph evidence")
    }
}

private fun validateRatingsBatchWhereAvailable(input: BestPracticeValidationInput): BestPracticeValidationResult {
    val bulkShape = input.shape["bulkShape"].orEmpty()
    val adapterMethod = input.spec?.adapterMethod.orEmpty()
    val batchEvidence = input.shapeId.contains("batch") ||
        bulkShape.contains("batch", ignoreCase = true) ||
        adapterMethod.contains("Batch", ignoreCase = true)
    return if (batchEvidence) {
        BestPracticeValidationResult("PASS", "ratings contract is batch-capable or uses batch adapter evidence")
    } else {
        BestPracticeValidationResult("WARN", "ratings route is not proven batch-capable")
    }
}

private fun validatePosterCacheKeyVary(input: BestPracticeValidationInput): BestPracticeValidationResult {
    val forbidden = input.shape["forbidden"].orEmpty()
    val include = input.shape["include"].orEmpty()
    val actual = input.spec?.cacheKeyTemplate.orEmpty()
    val hasForbiddenRules = forbidden.contains("raw API key") && forbidden.contains("apiKey.hashCode")
    val actualAvoidsForbidden = !actual.contains("apiKey.hashCode") && !actual.contains("raw API key")
    val hasVaryInputs = include.isNotBlank()
    return if (hasForbiddenRules && actualAvoidsForbidden && hasVaryInputs) {
        BestPracticeValidationResult("PASS", "poster cache key contract captures output-varying inputs and forbids raw credentials")
    } else {
        BestPracticeValidationResult("WARN", "poster cache key vary evidence is incomplete")
    }
}

fun buildBestPracticeRows(
    contractShapes: Map<String, Map<String, String>>,
    expectedShapes: Map<String, Map<String, String>>,
    specs: List<IntegrationAuditSpecRow>,
    ruleSupport: Map<String, BestPracticeRuleSupport>
): List<IntegrationBestPracticeRow> =
    contractShapes.flatMap { (shapeId, shape) ->
        shape["bestPracticeRules"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { rule ->
                val spec = specs.firstOrNull { it.apiShapeId == shapeId }
                val mergedShape = shape + expectedShapes[shapeId].orEmpty()
                val result = when (ruleSupport[rule]) {
                    BestPracticeRuleSupport.EXECUTABLE -> executableBestPracticeVerdict(
                        rule = rule,
                        input = BestPracticeValidationInput(shapeId, mergedShape, spec)
                    )
                    BestPracticeRuleSupport.DECLARED_ONLY -> BestPracticeValidationResult(
                        verdict = "WARN",
                        evidence = "DECLARED_ONLY rule support; no executable validator"
                    )
                    null -> BestPracticeValidationResult(
                        verdict = "FAIL",
                        evidence = "missing bestPracticeRuleSupport declaration"
                    )
                }
                IntegrationBestPracticeRow(
                    apiShapeId = shapeId,
                    provider = shape["provider"].orEmpty(),
                    rule = rule,
                    verdict = result.verdict,
                    evidence = result.evidence
                )
            }
    }

fun extractIntegrationApiShapeConstants(sources: List<Pair<File, String>>): Map<String, String> {
    val constants = mutableMapOf<String, String>()
    val objectPattern = Regex("""object\s+([A-Za-z0-9_]+)\s*\{([\s\S]*?)\n\}""")
    val constantPattern = Regex("""const\s+val\s+([A-Z0-9_]+)\s*=\s*"([^"]+)"""")
    sources.forEach { (_, text) ->
        objectPattern.findAll(text).forEach { objectMatch ->
            val objectName = objectMatch.groupValues[1]
            constantPattern.findAll(objectMatch.groupValues[2]).forEach { constantMatch ->
                val constantName = constantMatch.groupValues[1]
                val value = constantMatch.groupValues[2]
                constants["$objectName.$constantName"] = value
                constants.putIfAbsent(constantName, value)
            }
        }
    }
    return constants
}

fun extractAuditStringArgument(window: String, name: String): String? {
    Regex("""$name\s*=\s*"([^"]*)"""")
        .find(window)
        ?.groupValues
        ?.get(1)
        ?.let { return it }
    Regex("""$name\s*=\s*([A-Za-z0-9_]+\.[A-Za-z0-9_]+)""")
        .find(window)
        ?.groupValues
        ?.get(1)
        ?.let { return it }
    Regex("""$name\s*=\s*([A-Za-z0-9_]+)""")
        .find(window)
        ?.groupValues
        ?.get(1)
        ?.let { return it }
    return null
}

fun providerDefaultHeaderPolicy(provider: String, apiShapeId: String?): String? =
    when (provider) {
        "TMDB" -> "tmdb-json-v1"
        "TVDB" -> if (apiShapeId == "tvdb.login") "json-body-no-auth-v1" else "tvdb-json-bearer-v1"
        "TRAKT" -> "trakt-json-v2"
        "SIMKL" -> "simkl-json-v1"
        "KITSU", "ANISKIP", "ARM" -> "public-json-v1"
        "ANIMESKIP" -> "graphql-json-v1"
        "RPDB", "TOP_POSTERS" -> "image-fetch-default-v1"
        "THEINTRODB" -> "introdb-json-optional-bearer-v1"
        "MDBLIST" -> "mdblist-api-key-v1"
        "OMDB" -> "omdb-query-api-key-v1"
        "CUSTOM_IMDB" -> "custom-imdb-json-v1"
        "REAL_DEBRID" -> "real_debrid-json-token-v1"
        "PREMIUMIZE" -> "premiumize-json-token-v1"
        "TORBOX" -> "torbox-json-token-v1"
        "EASY_DEBRID" -> "easy_debrid-json-token-v1"
        "ADDON" -> "addon-json-v1"
        "SHADOW_COLLECTOR" -> "collector-json-v1"
        "GITHUB" -> "github-json-v1"
        "YOUTUBE_TRAILER" -> "youtube-html-v1"
        "OPEN_SUBTITLES",
        "SUBTITLE_SOURCE_DOWNLOAD",
        "SUBTITLE_TRANSLATION",
        "WYZIE_SUBTITLES" -> "subtitle-provider-v1"
        else -> null
    }

fun extractConstructorWindow(text: String, start: Int): String {
    var depth = 0
    var started = false
    var inString = false
    var escaped = false

    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '(' -> {
                depth += 1
                started = true
            }
            ')' -> {
                if (started) {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
    }

    return text.substring(start, minOf(text.length, start + 2600))
}

fun precedingFunctionName(text: String, index: Int): String {
    val prefix = text.substring(0, index)
    return Regex("""\bfun\s+(?:<[^>]+>\s*)?([A-Za-z0-9_]+)\s*\(""")
        .findAll(prefix)
        .lastOrNull()
        ?.groupValues
        ?.get(1)
        ?: "unknown"
}

fun enclosingFunctionBlock(text: String, index: Int): String? {
    val prefix = text.substring(0, index)
    val functionStart = Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]+\s*\(""")
        .findAll(prefix)
        .lastOrNull()
        ?.range
        ?.first
        ?: return null
    val bodyStart = text.indexOf('{', functionStart).takeIf { it >= 0 } ?: return null
    val expressionBodyStart = text.indexOf('=', functionStart).takeIf { it >= 0 && it < bodyStart }
    if (expressionBodyStart != null) {
        val nextFunctionStart = Regex("""\n\s*(?:private\s+|public\s+|internal\s+|protected\s+)?(?:suspend\s+)?fun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]+\s*\(""")
            .find(text, expressionBodyStart + 1)
            ?.range
            ?.first
            ?: text.length
        return text.substring(functionStart, nextFunctionStart)
    }
    var depth = 0
    var inString = false
    var escaped = false
    for (position in bodyStart until text.length) {
        val char = text[position]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) {
                    return text.substring(functionStart, position + 1)
                }
            }
        }
    }
    return text.substring(functionStart)
}

private val integrationRuntimeMarkers = listOf(
    "IntegrationSpec(",
    "IntegrationCallSpec(",
    "IntegrationStreamSpec(",
    "runtime.get(",
    "runtime.call(",
    "runtime.open("
)

private fun hasIntegrationRuntimeMarker(text: String): Boolean =
    integrationRuntimeMarkers.any(text::contains)

private fun functionBlocks(text: String): List<Pair<String, String>> =
    Regex("""\bfun\s+(?:<[^>]+>\s*)?([A-Za-z0-9_]+)\s*\(""")
        .findAll(text)
        .mapNotNull { match ->
            val block = enclosingFunctionBlock(text, match.range.last + 1) ?: return@mapNotNull null
            match.groupValues[1] to block
        }
        .toList()

private fun runtimeWrapperFunctionNames(text: String): Set<String> {
    val blocks = functionBlocks(text)
    val wrappers = blocks
        .filter { (_, block) -> hasIntegrationRuntimeMarker(block) }
        .mapTo(mutableSetOf()) { (name, _) -> name }

    var changed = true
    while (changed) {
        changed = false
        blocks.forEach { (name, block) ->
            if (name !in wrappers && wrappers.any { wrapper -> block.containsFunctionCall(wrapper) }) {
                wrappers += name
                changed = true
            }
            if (name in wrappers) {
                blocks.forEach { (candidate, _) ->
                    if (candidate !in wrappers && block.containsFunctionCall(candidate)) {
                        wrappers += candidate
                        changed = true
                    }
                }
            }
        }
    }
    return wrappers
}

private fun String.containsFunctionCall(name: String): Boolean =
    Regex("""\b${Regex.escape(name)}\s*\(""").containsMatchIn(this)

private fun isRuntimeWrappedProviderLoader(block: String, wrapperNames: Set<String>): Boolean =
    hasIntegrationRuntimeMarker(block) ||
        Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]*WithinRuntimeLoad\s*\(""").containsMatchIn(block) ||
        wrapperNames.any { wrapper -> block.containsFunctionCall(wrapper) }

private fun rawProviderCallIsRuntimeOwned(source: String, callOffset: Int, wrapperNames: Set<String>): Boolean {
    val functionBlock = enclosingFunctionBlock(source, callOffset).orEmpty()
    if (Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]*WithinRuntimeLoad\s*\(""").containsMatchIn(functionBlock)) {
        return true
    }
    val functionName = Regex("""\bfun\s+(?:<[^>]+>\s*)?([A-Za-z0-9_]+)\s*\(""")
        .find(functionBlock)
        ?.groupValues
        ?.get(1)
    if (functionName in wrapperNames && !hasIntegrationRuntimeMarker(functionBlock)) {
        return true
    }
    return isInsideRuntimeOwnedLambda(source, callOffset, wrapperNames)
}

private fun isInsideRuntimeOwnedLambda(source: String, callOffset: Int, wrapperNames: Set<String>): Boolean {
    val lambdaPattern = Regex("""\b(load|call|request|execute|open)\s*=\s*\{""")
    val trailingSpecLambdaPattern = Regex("""Integration(?:Call|Stream)?Spec\([\s\S]{0,1200}\)\s*\{""")
    val prefix = source.substring(0, callOffset)
    if (trailingSpecLambdaPattern.findAll(prefix)
            .toList()
            .asReversed()
            .any { match ->
                val braceStart = source.indexOf('{', match.range.last - 1)
                val braceEnd = matchingBraceEnd(source, braceStart) ?: return@any false
                callOffset in braceStart..braceEnd
            }
    ) {
        return true
    }
    return lambdaPattern.findAll(prefix)
        .toList()
        .asReversed()
        .any { match ->
            val braceStart = source.indexOf('{', match.range.first)
            val braceEnd = matchingBraceEnd(source, braceStart) ?: return@any false
            if (callOffset !in braceStart..braceEnd) return@any false

            val ownerStart = Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]+\s*\(""")
                .findAll(source.substring(0, match.range.first))
                .lastOrNull()
                ?.range
                ?.first
                ?: (match.range.first - 1200).coerceAtLeast(0)
            val ownerPrefix = source.substring(ownerStart, match.range.first)
            hasIntegrationRuntimeMarker(ownerPrefix) ||
                wrapperNames.any { wrapper -> Regex("""\b${Regex.escape(wrapper)}\s*\(""").containsMatchIn(ownerPrefix) }
        }
}

private fun matchingBraceEnd(source: String, braceStart: Int): Int? {
    if (braceStart < 0 || source.getOrNull(braceStart) != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (position in braceStart until source.length) {
        val char = source[position]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return position
            }
        }
    }
    return null
}

fun inferRawClient(window: String): String? {
    val receiverCall = Regex("""\b([a-zA-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)\s*\(""")
        .findAll(window)
        .map { "${it.groupValues[1]}.${it.groupValues[2]}" }
        .filterNot {
            it.startsWith("IntegrationProvider.") ||
                it.startsWith("IntegrationWorkClass.") ||
                it.startsWith("IntegrationCachePolicy.") ||
                it.startsWith("IntegrationScope.") ||
                it.startsWith("IntegrationLoadResult.") ||
                it.startsWith("IntegrationCallResult.") ||
                it.startsWith("Result.") ||
                it.startsWith("String.")
        }
        .firstOrNull()
    return receiverCall
}

fun extractAuditArgumentExpression(window: String, name: String): String? {
    val match = Regex("""$name\s*=""").find(window) ?: return null
    var parenDepth = 0
    var angleDepth = 0
    var inString = false
    var escaped = false
    val start = match.range.last + 1
    for (index in start until window.length) {
        val char = window[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '(' -> parenDepth += 1
            ')' -> if (parenDepth > 0) parenDepth -= 1
            '<' -> angleDepth += 1
            '>' -> if (angleDepth > 0) angleDepth -= 1
            ',' -> if (parenDepth == 0 && angleDepth == 0) {
                return window.substring(start, index).trim()
            }
        }
    }
    return window.substring(start).trim()
}

fun extractAuditCodec(window: String): String? {
    val expression = extractAuditArgumentExpression(window, "codec") ?: return null
    Regex("""gsonCodec<(.+)>\s*\(""")
        .find(expression)
        ?.groupValues
        ?.get(1)
        ?.let { return "gsonCodec<${it.trim()}>" }
    if (expression == "codec") {
        Regex("""codec\s*:\s*(?:com\.nexio\.tv\.core\.integration\.)?IntegrationCodec<([^>]+)>""")
            .find(window)
            ?.groupValues
            ?.get(1)
            ?.let { return "IntegrationCodec<${it.trim()}>" }
        return "IntegrationCodec<T>"
    }
    return expression
        .removeSuffix("()")
        .ifBlank { null }
}

fun extractIntegrationAuditSpecs(
    projectDir: File,
    sources: List<Pair<File, String>>,
    apiShapeConstants: Map<String, String>,
    expectedShapes: Map<String, Map<String, String>>
): List<IntegrationAuditSpecRow> {
    val constructors = listOf("IntegrationSpec", "IntegrationCallSpec", "IntegrationStreamSpec")
    val constructorRows = sources.flatMap { (file, text) ->
        constructors.flatMap { constructor ->
            Regex("""\b$constructor\s*\(""").findAll(text).mapNotNull { match ->
                val window = extractConstructorWindow(text, match.range.first)
                val provider = Regex("""provider\s*=\s*IntegrationProvider\.([A-Z0-9_]+)""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                    ?: "UNKNOWN"
                val workClass = Regex("""workClass\s*=\s*IntegrationWorkClass\.([A-Z0-9_]+)""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                val rawCachePolicy = Regex("""cachePolicy\s*=\s*IntegrationCachePolicy\.([A-Za-z0-9_]+)""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                    ?: Regex("""cachePolicy\s*=\s*([A-Za-z][A-Za-z0-9_]*)""")
                        .find(window)
                        ?.groupValues
                        ?.get(1)
                    ?: if (constructor == "IntegrationCallSpec" || constructor == "IntegrationStreamSpec") "ObserveOnlyOrMutation" else null
                val cachePolicy = if (
                    rawCachePolicy == "cachePolicy" &&
                    Regex("""val\s+cachePolicy\s*=\s*IntegrationCachePolicy\.CacheFirst""").containsMatchIn(text)
                ) {
                    "CacheFirst"
                } else {
                    rawCachePolicy
                }
                val cacheKey = Regex("""cacheKey\s*=\s*"([^"]+)"""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                    ?: Regex("""cacheKey\s*=\s*([^,\n]+)""")
                        .find(window)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?.takeUnless { it == "null" }
                val rawApiShapeId = extractAuditStringArgument(window, "apiShapeId")
                val operationKey = extractAuditStringArgument(window, "operationKey")
                if (rawApiShapeId == "apiShapeId") {
                    return@mapNotNull null
                }
                val apiShapeId = rawApiShapeId?.let { raw ->
                    apiShapeConstants[raw]
                        ?: Regex("""([A-Za-z0-9_]+ApiShapes\.[A-Z0-9_]+)""")
                            .find(window)
                            ?.groupValues
                            ?.get(1)
                            ?.let(apiShapeConstants::get)
                        ?: raw
                }
                val declaredHeaderPolicyId = extractAuditStringArgument(window, "headerPolicyId")
                    ?.let { apiShapeConstants[it] ?: it }
                val expectedHeaderPolicyId = apiShapeId?.let { expectedShapes[it]?.get("headerPolicy") }
                val headerPolicyId = declaredHeaderPolicyId ?: providerDefaultHeaderPolicy(provider, apiShapeId)
                val codec = extractAuditCodec(window)
                val scope = Regex("""scope\s*=\s*IntegrationScope\.([A-Za-z0-9_().]+)""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                    ?: "Global"
                val methodName = precedingFunctionName(text, match.range.first)
                val adapterClass = file.nameWithoutExtension
                val inferredCallId = apiShapeId ?: "${provider.lowercase()}.${adapterClass.removeSuffix("IntegrationProvider").lowercase()}.${methodName}"
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                val issues = buildList {
                    if (provider == "UNKNOWN") add("missing provider")
                    if (apiShapeId == null) add("missing endpoint-shape id")
                    if (headerPolicyId.isNullOrBlank()) add("missing header policy id")
                    if (!expectedHeaderPolicyId.isNullOrBlank() && headerPolicyId != expectedHeaderPolicyId) {
                        add("header policy differs from endpoint contract: actual=$headerPolicyId expected=$expectedHeaderPolicyId")
                    }
                    if (operationKey.isNullOrBlank()) add("missing operation key")
                    if (workClass == null) add("missing work class")
                    if (cachePolicy == null) add("missing cache policy")
                    if (cachePolicy == "CacheFirst" && cacheKey.isNullOrBlank()) add("CacheFirst without cache key")
                    if (cachePolicy == "CacheFirst" && codec == null) add("CacheFirst without typed codec")
                    if (codec?.contains("PassThrough", ignoreCase = true) == true) add("untyped/pass-through codec")
                }
                IntegrationAuditSpecRow(
                    callId = inferredCallId,
                    provider = provider,
                    specType = constructor,
                    adapterClass = adapterClass,
                    adapterMethod = methodName,
                    sourceFile = file.normalizedRelativeTo(projectDir),
                    line = line,
                    apiShapeId = apiShapeId,
                    headerPolicyId = headerPolicyId,
                    expectedHeaderPolicyId = expectedHeaderPolicyId,
                    operationKey = operationKey,
                    cacheKeyTemplate = cacheKey,
                    workClass = workClass,
                    cachePolicy = cachePolicy,
                    codec = codec,
                    scope = scope,
                    rawClient = inferRawClient(window),
                    verdict = if (issues.isEmpty()) "PASS" else "FAIL",
                    issues = issues
                )
            }.toList()
        }
    }
    val tmdbRuntimeGetRows = sources.flatMap { (file, text) ->
        if (file.nameWithoutExtension != "TmdbIntegrationProvider") {
            emptyList()
        } else {
            Regex("""\btmdbRuntimeGet\s*\(""").findAll(text).mapNotNull { match ->
                val window = extractConstructorWindow(text, match.range.first)
                val rawApiShapeId = extractAuditStringArgument(window, "apiShapeId")
                if (rawApiShapeId == null || rawApiShapeId == "apiShapeId") {
                    return@mapNotNull null
                }
                val apiShapeId = apiShapeConstants[rawApiShapeId]
                    ?: Regex("""([A-Za-z0-9_]+ApiShapes\.[A-Z0-9_]+)""")
                        .find(window)
                        ?.groupValues
                        ?.get(1)
                        ?.let(apiShapeConstants::get)
                    ?: rawApiShapeId
                val operationKey = extractAuditStringArgument(window, "operationKey")
                val cacheKey = Regex("""cacheKey\s*=\s*"([^"]+)"""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                    ?: Regex("""cacheKey\s*=\s*([^,\n]+)""")
                        .find(window)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?.takeUnless { it == "null" }
                val expectedHeaderPolicyId = expectedShapes[apiShapeId]?.get("headerPolicy")
                val headerPolicyId = providerDefaultHeaderPolicy("TMDB", apiShapeId)
                val codec = extractAuditCodec(window)
                val methodName = precedingFunctionName(text, match.range.first)
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                val issues = buildList {
                    if (headerPolicyId.isNullOrBlank()) add("missing header policy id")
                    if (!expectedHeaderPolicyId.isNullOrBlank() && headerPolicyId != expectedHeaderPolicyId) {
                        add("header policy differs from endpoint contract: actual=$headerPolicyId expected=$expectedHeaderPolicyId")
                    }
                    if (operationKey.isNullOrBlank()) add("missing operation key")
                    if (cacheKey.isNullOrBlank()) add("CacheFirst without cache key")
                    if (codec == null) add("CacheFirst without typed codec")
                    if (codec?.contains("PassThrough", ignoreCase = true) == true) add("untyped/pass-through codec")
                }
                IntegrationAuditSpecRow(
                    callId = apiShapeId,
                    provider = "TMDB",
                    specType = "IntegrationSpec",
                    adapterClass = file.nameWithoutExtension,
                    adapterMethod = methodName,
                    sourceFile = file.normalizedRelativeTo(projectDir),
                    line = line,
                    apiShapeId = apiShapeId,
                    headerPolicyId = headerPolicyId,
                    expectedHeaderPolicyId = expectedHeaderPolicyId,
                    operationKey = operationKey,
                    cacheKeyTemplate = cacheKey,
                    workClass = "USER_VISIBLE",
                    cachePolicy = "CacheFirst",
                    codec = codec,
                    scope = "Global",
                    rawClient = inferRawClient(window),
                    verdict = if (issues.isEmpty()) "PASS" else "FAIL",
                    issues = issues
                )
            }.toList()
        }
    }
    val tvdbAuthenticatedRows = sources.flatMap { (file, text) ->
        if (file.nameWithoutExtension != "TvdbIntegrationProvider") {
            emptyList()
        } else {
            Regex("""\bcallAuthenticated\s*\(""").findAll(text).mapNotNull { match ->
                val window = extractConstructorWindow(text, match.range.first)
                val shapeMatch = Regex("""callAuthenticated\s*\(\s*([A-Za-z0-9_.]+)\s*,\s*"([^"]+)"\s*,\s*IntegrationWorkClass\.([A-Z0-9_]+)""")
                    .find(window)
                    ?: return@mapNotNull null
                val apiShapeId = apiShapeConstants[shapeMatch.groupValues[1]] ?: shapeMatch.groupValues[1]
                if (apiShapeId != "tvdb.person.extended") {
                    return@mapNotNull null
                }
                val operationKey = shapeMatch.groupValues[2]
                val workClass = shapeMatch.groupValues[3]
                val expectedHeaderPolicyId = expectedShapes[apiShapeId]?.get("headerPolicy")
                val headerPolicyId = providerDefaultHeaderPolicy("TVDB", apiShapeId)
                val methodName = precedingFunctionName(text, match.range.first)
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                val issues = buildList {
                    if (headerPolicyId.isNullOrBlank()) add("missing header policy id")
                    if (!expectedHeaderPolicyId.isNullOrBlank() && headerPolicyId != expectedHeaderPolicyId) {
                        add("header policy differs from endpoint contract: actual=$headerPolicyId expected=$expectedHeaderPolicyId")
                    }
                    if (operationKey.isBlank()) add("missing operation key")
                    if (workClass.isBlank()) add("missing work class")
                }
                IntegrationAuditSpecRow(
                    callId = apiShapeId,
                    provider = "TVDB",
                    specType = "IntegrationCallSpec",
                    adapterClass = file.nameWithoutExtension,
                    adapterMethod = methodName,
                    sourceFile = file.normalizedRelativeTo(projectDir),
                    line = line,
                    apiShapeId = apiShapeId,
                    headerPolicyId = headerPolicyId,
                    expectedHeaderPolicyId = expectedHeaderPolicyId,
                    operationKey = operationKey,
                    cacheKeyTemplate = null,
                    workClass = workClass,
                    cachePolicy = "ObserveOnlyOrMutation",
                    codec = null,
                    scope = "Global",
                    rawClient = inferRawClient(window),
                    verdict = if (issues.isEmpty()) "PASS" else "FAIL",
                    issues = issues
                )
            }.toList()
        }
    }
    return (constructorRows + tmdbRuntimeGetRows + tvdbAuthenticatedRows).sortedWith(compareBy({ it.provider }, { it.sourceFile }, { it.line }))
}

fun extractIntegrationAuditViolations(projectDir: File, sources: List<Pair<File, String>>): List<IntegrationAuditViolation> {
    val allowedPathFragments = listOf(
        "/com/nexio/tv/data/integration/",
        "/com/nexio/tv/core/integration/",
        "/com/nexio/tv/data/remote/",
        "/com/nexio/tv/core/di/"
    )
    val disallowedPathFragments = listOf(
        "/com/nexio/tv/ui/",
        "/com/nexio/tv/workers/",
        "/com/nexio/tv/core/tvdb/",
        "/com/nexio/tv/core/tmdb/",
        "/com/nexio/tv/core/anime/",
        "/com/nexio/tv/core/poster/",
        "/com/nexio/tv/data/repository/",
        "/com/nexio/tv/updater/"
    )
    val forbiddenPatterns = linkedMapOf(
        "raw Retrofit" to Regex("""\bRetrofit\b"""),
        "raw OkHttpClient" to Regex("""\bOkHttpClient\b"""),
        "raw OkHttp newCall" to Regex("""\bnewCall\s*\("""),
        "IntegrationRuntime injection" to Regex("""\bIntegrationRuntime\b"""),
        "IntegrationSpec creation" to Regex("""\bIntegration(?:Call|Stream)?Spec\s*\("""),
        "provider auth execute" to Regex("""\bexecuteAuthorizedRequest\s*\("""),
        "remote poster URL model" to Regex("""https?://[^"\s]*(?:rpdb|ratingposterdb|topposters)[^"\s]*""", RegexOption.IGNORE_CASE),
        "Coil remote model" to Regex("""\bAsyncImage\s*\(|\brememberAsyncImagePainter\s*\(""")
    )
    val boundaryViolations = sources.flatMap { (file, text) ->
        val normalized = "/" + file.normalizedRelativeTo(projectDir)
        val isAllowed = allowedPathFragments.any(normalized::contains)
        val isDisallowed = disallowedPathFragments.any(normalized::contains)
        if (isAllowed || !isDisallowed) {
            emptyList()
        } else {
            forbiddenPatterns.flatMap { (label, regex) ->
                if (!regex.containsMatchIn(text)) {
                    emptyList()
                } else {
                    listOf(
                        IntegrationAuditViolation(
                            offender = file.nameWithoutExtension,
                            forbiddenDependency = label,
                            file = file.normalizedRelativeTo(projectDir),
                            whyForbidden = "feature or facade layer can bypass IntegrationRuntime provider policy",
                            requiredFix = "move provider networking to data/integration adapter or document an explicit exemption"
                        )
                    )
                }
            }
        }
    }.distinct().sortedWith(compareBy({ it.file }, { it.forbiddenDependency }))
    val unwrappedIntegrationViolations = sources.flatMap { (file, text) ->
        val normalized = "/" + file.normalizedRelativeTo(projectDir)
        if (!normalized.contains("/com/nexio/tv/data/integration/")) return@flatMap emptyList()
        if (normalized.contains("/transport/")) return@flatMap emptyList()

        val lines = text.lines()
        val wrapperNames = runtimeWrapperFunctionNames(text)
        val lineStarts = mutableListOf(0)
        lines.dropLast(1).fold(0) { offset, line ->
            val next = offset + line.length + 1
            lineStarts += next
            next
        }
        lines.mapIndexedNotNull { index, line ->
            val rawProviderCall = Regex("""\b[a-z][A-Za-z0-9_]*Api\.[A-Za-z0-9_]+\s*\(""").find(line)
                ?: return@mapIndexedNotNull null

            val wrapped = rawProviderCallIsRuntimeOwned(
                source = text,
                callOffset = lineStarts[index] + rawProviderCall.range.first,
                wrapperNames = wrapperNames
            )
            if (wrapped) return@mapIndexedNotNull null

            IntegrationAuditViolation(
                offender = file.nameWithoutExtension,
                forbiddenDependency = "unwrapped provider API inside integration package",
                file = "${file.normalizedRelativeTo(projectDir)}:${index + 1}",
                whyForbidden = "integration package raw provider calls must be owned by a nearby IntegrationRuntime spec, not merely hidden under an allowed package",
                requiredFix = "wrap the raw provider call in IntegrationRuntime or delegate to a runtime-owned provider"
            )
        }
    }
    return (boundaryViolations + unwrappedIntegrationViolations)
        .distinct()
        .sortedWith(compareBy({ it.file }, { it.forbiddenDependency }))
}

fun documentedIntegrationAuditExemptions(violations: List<IntegrationAuditViolation>): List<IntegrationAuditExemption> {
    val genericImageExemptions = violations
        .filter { it.forbiddenDependency == "Coil remote model" }
        .map { violation ->
            IntegrationAuditExemption(
                path = "generic image model via Coil",
                hostProvider = "generic external artwork/avatar/logo hosts",
                reason = "temporary generic image transport exemption; provider-generated RPDB/Top-Posters posters use integration-poster runtime requests",
                owningFile = violation.file,
                reviewStatus = "documented-temporary"
            )
        }
    return listOf(
        IntegrationAuditExemption(
            path = "@Named(\"playback\") media transport bytes",
            hostProvider = "playback media hosts",
            reason = "out of scope for initial Android migration",
            owningFile = "playback transport adapters",
            reviewStatus = "documented"
        ),
        IntegrationAuditExemption(
            path = "@Named(\"addonStreams\") stream transport",
            hostProvider = "addon stream hosts",
            reason = "out of scope for initial Android migration",
            owningFile = "addon stream transport adapters",
            reviewStatus = "documented"
        ),
        IntegrationAuditExemption(
            path = "raw media segment fetching",
            hostProvider = "media hosts",
            reason = "player transport bytes remain outside non-playback audit",
            owningFile = "player transport",
            reviewStatus = "documented"
        )
    ) + genericImageExemptions
}

fun buildIntegrationAuditProviderRows(
    providers: List<String>,
    policySource: String,
    specRows: List<IntegrationAuditSpecRow>,
    violations: List<IntegrationAuditViolation>,
    sources: List<Pair<File, String>>
): List<IntegrationAuditProviderRow> =
    providers.map { provider ->
        val providerToken = when (provider) {
            "THEINTRODB" -> "IntroDb"
            "SHADOW_COLLECTOR" -> "Shadow"
            "SUBTITLE_SOURCE_DOWNLOAD" -> "SubtitleSourceDownload"
            "SUBTITLE_TRANSLATION" -> "SubtitleTranslation"
            "WYZIE_SUBTITLES" -> "WyzieSubtitle"
            "CUSTOM_IMDB" -> "CustomImdb"
            "REAL_DEBRID" -> "RealDebrid"
            "EASY_DEBRID" -> "EasyDebrid"
            "TOP_POSTERS" -> "TopPosters"
            "YOUTUBE_TRAILER" -> "YouTubeTrailer"
            else -> provider.lowercase().split("_").joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        }
        val adapterClasses = sources
            .map { it.first.nameWithoutExtension }
            .filter { it.contains(providerToken, ignoreCase = true) && it.endsWith("IntegrationProvider") }
            .distinct()
            .sorted()
        val providerSpecs = specRows.filter { it.provider == provider }
        val hasPolicy = policySource.contains("IntegrationProvider.$provider")
        val providerBoundaryViolationLabels = setOf(
            "raw Retrofit",
            "raw OkHttpClient",
            "raw OkHttp newCall",
            "IntegrationRuntime injection",
            "IntegrationSpec creation",
            "provider auth execute"
        )
        val providerViolationCount = violations.count { violation ->
            violation.forbiddenDependency in providerBoundaryViolationLabels &&
                (
                    violation.file.contains(provider.lowercase(), ignoreCase = true) ||
                        violation.forbiddenDependency.contains(provider, ignoreCase = true)
                )
        }
        val missingShapeIds = providerSpecs.count { it.apiShapeId == null }
        val issues = buildList {
            if (adapterClasses.isEmpty()) add("adapter missing")
            if (!hasPolicy) add("policy missing")
            if (missingShapeIds > 0) add("shape ids missing")
            if (providerViolationCount > 0) add("direct bypass")
        }
        IntegrationAuditProviderRow(
            provider = provider,
            adapterClasses = adapterClasses,
            hasPolicy = hasPolicy,
            runtimeCoveredCalls = providerSpecs.size,
            directBypassCalls = providerViolationCount,
            missingEndpointShapeIds = missingShapeIds,
            cachePolicyModes = providerSpecs.mapNotNull { it.cachePolicy }.distinct().sorted(),
            verdict = if (issues.isEmpty()) "PASS" else "FAIL"
        )
    }

fun currentGitSha(projectRoot: File): String =
    runCatching {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(projectRoot)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val output = process.inputStream.bufferedReader().readText().trim()
                if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
            }
    }.getOrDefault("unknown")

fun currentGitWorktreeState(projectRoot: File): GitWorktreeState =
    runCatching {
        val process = ProcessBuilder("git", "status", "--short", "--untracked-files=all")
            .directory(projectRoot)
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
        if (process.waitFor() != 0) {
            GitWorktreeState(state = "UNKNOWN", dirtyFileCount = -1, untrackedFileCount = -1, sample = lines.take(20))
        } else {
            GitWorktreeState(
                state = if (lines.isEmpty()) "CLEAN" else "DIRTY",
                dirtyFileCount = lines.size,
                untrackedFileCount = lines.count { it.startsWith("??") },
                sample = lines.take(20)
            )
        }
    }.getOrDefault(GitWorktreeState(state = "UNKNOWN", dirtyFileCount = -1, untrackedFileCount = -1, sample = emptyList()))

fun auditShapeValue(shape: Map<String, String>, key: String, fallback: String = "unknown"): String =
    shape[key]?.takeIf { it.isNotBlank() } ?: fallback

fun providerAuthMode(provider: String): String =
    when (provider) {
        "TRAKT", "SIMKL", "REAL_DEBRID", "PREMIUMIZE", "TORBOX", "EASY_DEBRID" -> "account"
        "TMDB", "TVDB", "MDBLIST", "OMDB", "CUSTOM_IMDB", "RPDB", "TOP_POSTERS", "YOUTUBE_TRAILER" -> "api-key-or-token"
        else -> "none-or-provider-specific"
    }

fun defaultRequiredHeaders(headerPolicyId: String?): String =
    when (headerPolicyId) {
        "tmdb-json-v1",
        "tvdb-json-bearer-v1" -> "Authorization"
        "trakt-json-v2" -> "X-Trakt-API-Key|X-Trakt-API-Version"
        "simkl-json-v1" -> "simkl-api-key"
        else -> ""
    }

fun defaultOptionalHeaders(headerPolicyId: String?): String =
    when (headerPolicyId) {
        "trakt-json-v2",
        "introdb-json-optional-bearer-v1" -> "Authorization"
        "topposters-thumbnail-v1" -> "query.user_agent"
        else -> ""
    }

fun defaultForbiddenHeaders(provider: String, headerPolicyId: String?): String {
    val crossProviderAuth = mutableListOf("X-Trakt-API-Key", "simkl-api-key", "X-TVDB-ApiKey", "api_key")
    when (provider) {
        "TRAKT" -> crossProviderAuth.remove("X-Trakt-API-Key")
        "SIMKL" -> crossProviderAuth.remove("simkl-api-key")
        "TVDB" -> crossProviderAuth.remove("X-TVDB-ApiKey")
    }
    if (headerPolicyId == "json-body-no-auth-v1" || headerPolicyId?.contains("path-key") == true || headerPolicyId == "topposters-thumbnail-v1") {
        crossProviderAuth += "Authorization"
    }
    return crossProviderAuth.distinct().joinToString("|")
}

fun defaultCredentialLocation(provider: String, headerPolicyId: String?): String =
    when {
        headerPolicyId == "json-body-no-auth-v1" -> "body"
        headerPolicyId?.contains("path-key") == true || headerPolicyId == "topposters-thumbnail-v1" -> "path"
        headerPolicyId?.contains("query-api-key") == true -> "query"
        provider in setOf("TMDB", "TVDB", "TRAKT", "SIMKL", "THEINTRODB") -> "header"
        provider in setOf("MDBLIST", "OMDB", "RPDB") -> "query-or-path"
        else -> "none-or-provider-specific"
    }

fun defaultResponseHeadersCaptured(headerPolicyId: String?): String =
    if (headerPolicyId == "introdb-json-optional-bearer-v1") {
        "X-RateLimit-Limit|X-RateLimit-Remaining|X-RateLimit-Reset|X-UsageLimit-Limit|X-UsageLimit-Remaining|X-UsageLimit-Reset|Retry-After"
    } else {
        "Retry-After"
    }

fun defaultCacheVary(headerPolicyId: String?, shape: Map<String, String>): String =
    when {
        headerPolicyId == "topposters-thumbnail-v1" -> "query.badge_position|query.badge_size|query.blur|query.user_agent_profile_id"
        shape["path"].orEmpty().contains("language", ignoreCase = true) -> "query.language"
        else -> ""
    }

fun buildHeaderPolicyRows(expectedShapes: Map<String, Map<String, String>>): List<IntegrationAuditHeaderPolicyRow> =
    expectedShapes.map { (shapeId, shape) ->
        val provider = shape["provider"].orEmpty()
        val headerPolicyId = shape["headerPolicy"]?.takeIf { it.isNotBlank() }
        val issues = buildList {
            if (headerPolicyId == null) add("missing headerPolicy")
            if (defaultRequiredHeaders(headerPolicyId).split('|').filter { it.isNotBlank() }.any {
                    it in defaultForbiddenHeaders(provider, headerPolicyId).split('|')
                }
            ) {
                add("required header is also forbidden")
            }
        }
        IntegrationAuditHeaderPolicyRow(
            apiShapeId = shapeId,
            provider = provider,
            headerPolicyId = headerPolicyId,
            requiredHeaders = defaultRequiredHeaders(headerPolicyId),
            optionalHeaders = defaultOptionalHeaders(headerPolicyId),
            forbiddenHeaders = defaultForbiddenHeaders(provider, headerPolicyId),
            credentialLocation = defaultCredentialLocation(provider, headerPolicyId),
            userAgentPolicy = if (headerPolicyId == "topposters-thumbnail-v1") "default-or-approved-browser-profile" else "nexio-default-user-agent",
            responseHeadersCaptured = defaultResponseHeadersCaptured(headerPolicyId),
            cacheVary = defaultCacheVary(headerPolicyId, shape),
            verdict = if (issues.isEmpty()) "PASS" else "FAIL",
            issues = issues
        )
    }

fun endpointShapeReadinessStatus(
    shapeId: String,
    shape: Map<String, String>,
    actual: IntegrationAuditSpecRow?,
    metadataRouterPrerequisiteShapeIds: Set<String>
): String =
    when {
        actual != null && actual.verdict == "PASS" -> "ACTIVE_RUNTIME_COVERED"
        actual != null -> "ACTIVE_RUNTIME_SPEC_FAIL"
        shape["active"] == "false" || shape["reviewStatus"].orEmpty().contains("unused", ignoreCase = true) -> "EXEMPT"
        shape["reviewStatus"].orEmpty().contains("retired", ignoreCase = true) -> "RETIRED"
        shapeId in metadataRouterPrerequisiteShapeIds -> "ACTIVE_REQUIRED_MISSING"
        else -> "PLANNED_NOT_ACTIVE"
    }

fun endpointShapeVerdict(
    shapeId: String,
    shape: Map<String, String>,
    actual: IntegrationAuditSpecRow?,
    metadataRouterPrerequisiteShapeIds: Set<String>
): String =
    endpointShapeReadinessStatus(shapeId, shape, actual, metadataRouterPrerequisiteShapeIds)

fun buildEndpointReadinessRows(
    expectedShapes: Map<String, Map<String, String>>,
    specs: List<IntegrationAuditSpecRow>,
    metadataRouterPrerequisiteShapeIds: Set<String>
): List<IntegrationEndpointReadinessRow> =
    expectedShapes.map { (shapeId, shape) ->
        val actual = specs.firstOrNull { it.apiShapeId == shapeId }
        val status = endpointShapeReadinessStatus(shapeId, shape, actual, metadataRouterPrerequisiteShapeIds)
        IntegrationEndpointReadinessRow(
            shapeId = shapeId,
            provider = shape["provider"].orEmpty(),
            status = status,
            metadataRouterRequired = shapeId in metadataRouterPrerequisiteShapeIds,
            actualAdapterMethod = actual?.let { "${it.adapterClass}.${it.adapterMethod}" },
            actualSource = actual?.sourceFile,
            requiredAction = when (status) {
                "ACTIVE_REQUIRED_MISSING" -> "route through IntegrationRuntime or explicitly defer from MetadataRouter prerequisite set"
                "ACTIVE_RUNTIME_SPEC_FAIL" -> "fix runtime spec audit issues before MetadataRouter gate can pass"
                "PLANNED_NOT_ACTIVE" -> "no MetadataRouter blocker; classify as planned inventory until implemented"
                "EXEMPT" -> "keep exemption owner/review status current"
                "RETIRED" -> "remove from active endpoint registry if permanently retired"
                else -> "none"
            }
        )
    }

fun metadataRouterReadinessVerdict(endpointRows: List<IntegrationEndpointReadinessRow>): String =
    when {
        endpointRows.any { it.status == "ACTIVE_REQUIRED_MISSING" || it.status == "ACTIVE_RUNTIME_SPEC_FAIL" } -> "FAIL"
        endpointRows.any { it.status == "PLANNED_NOT_ACTIVE" } -> "PASS_WITH_WARNINGS"
        else -> "PASS"
    }

fun providerLifecycleStatus(row: IntegrationAuditProviderRow): String =
    when {
        row.runtimeCoveredCalls > 0 -> "ACTIVE_RUNTIME_COVERED"
        row.provider == "REAL_DEBRID" -> "DORMANT_PROVIDER"
        row.provider.contains("DEBRID") -> "PLANNED_NOT_ACTIVE"
        else -> "NO_RUNTIME_CALLS"
    }

fun normalizeAuditKeyTemplate(template: String?): String? =
    template
        ?.replace(Regex("""\$\{[A-Za-z0-9_]+\.hashCode\(\)}"""), "{credentialHash}")
        ?.replace(Regex("""[A-Za-z0-9_]+\.hashCode"""), "credentialHash")
        ?.let {
            when {
                it.startsWith("profileCacheKey(profileId") -> "profile:{profileId}:provider:{provider}:operation:{operationKey}"
                it == "request.cacheKey" -> "runtime-request.cacheKey"
                else -> it
            }
        }

fun providerCapabilitySummary(provider: String): String =
    when (provider) {
        "REAL_DEBRID", "PREMIUMIZE", "TORBOX", "EASY_DEBRID" -> "debrid account, library, availability, link resolution"
        "TRAKT", "SIMKL" -> "account library, progress, discovery, mutation"
        "TMDB", "TVDB", "KITSU", "MDBLIST", "OMDB", "CUSTOM_IMDB" -> "metadata, ratings, discovery enrichment"
        "RPDB", "TOP_POSTERS" -> "poster validation and remote poster fetch"
        "THEINTRODB", "ANISKIP", "ANIMESKIP", "ARM" -> "skip and anime bridge resolution"
        else -> "utility integration"
    }

fun validateIntegrationRuntimeAuditOutput(outputDir: File, failOnFailVerdict: Boolean) {
    val requiredOutputs = listOf(
        "integration-runtime-audit.md",
        "integration-runtime-audit.json",
        "provider-policy-matrix.csv",
        "endpoint-shape-matrix.csv",
        "metadata-router-readiness.csv",
        "boundary-violations.csv",
        "boundary-exemptions.csv",
        "header-policy-matrix.csv",
        "header-violations.csv",
        "header-runtime-sample.jsonl",
        "runtime-event-sample.jsonl",
        "provider-contract-provenance.csv",
        "cache-policy-matrix.csv",
        "cache-key-vary-matrix.csv",
        "cache-policy-violations.csv",
        "provider-best-practice-matrix.csv"
    )
    requiredOutputs.forEach { name ->
        val file = outputDir.resolve(name)
        check(file.isFile && file.length() > 0L) { "IntegrationRuntime audit output is missing or empty: ${file.path}" }
    }

    val payload = JsonSlurper().parse(outputDir.resolve("integration-runtime-audit.json")) as? Map<*, *>
        ?: error("IntegrationRuntime audit JSON is not an object")
    check(payload["schemaVersion"] == 1) { "IntegrationRuntime audit JSON has unexpected schemaVersion: ${payload["schemaVersion"]}" }
    val verdict = payload["verdict"]?.toString().orEmpty()
    check(verdict in setOf("PASS", "PASS_WITH_WARNINGS", "FAIL")) { "IntegrationRuntime audit JSON has invalid verdict: $verdict" }
    check(payload["counts"] is Map<*, *>) { "IntegrationRuntime audit JSON is missing counts object" }
    if (failOnFailVerdict && verdict == "FAIL") {
        error("IntegrationRuntime audit verdict is FAIL. See ${outputDir.resolve("integration-runtime-audit.md").path}")
    }
}

fun writeIntegrationRuntimeAudit(
    outputDir: File,
    runtimeEventFixtureFile: File,
    providers: List<IntegrationAuditProviderRow>,
    specs: List<IntegrationAuditSpecRow>,
    violations: List<IntegrationAuditViolation>,
    exemptions: List<IntegrationAuditExemption>,
    expectedShapes: Map<String, Map<String, String>>,
    contractShapes: Map<String, Map<String, String>>,
    cacheContracts: Map<String, Map<String, String>>,
    metadataRouterPrerequisiteShapeIds: Set<String>,
    bestPracticeRuleSupport: Map<String, BestPracticeRuleSupport>,
    providerContractRows: List<IntegrationProviderContractProvenanceRow>,
    gitSha: String,
    gitWorktreeState: GitWorktreeState
) {
    outputDir.mkdirs()
    val missingPolicies = providers.count { !it.hasPolicy }
    val missingShapeIds = specs.count { it.apiShapeId == null }
    val endpointShapeMismatches = specs.count { it.apiShapeId != null && !expectedShapes.containsKey(it.apiShapeId) }
    val missingCachePolicies = specs.count { it.cachePolicy == null }
    val undocumentedExemptions = exemptions.count { it.reviewStatus.isBlank() }
    val missingHeaderPolicies = buildHeaderPolicyRows(expectedShapes).count { it.headerPolicyId.isNullOrBlank() }
    val endpointReadinessRows = buildEndpointReadinessRows(expectedShapes, specs, metadataRouterPrerequisiteShapeIds)
    val metadataRouterVerdict = metadataRouterReadinessVerdict(endpointReadinessRows)
    val activeRequiredMissing = endpointReadinessRows.count { it.status == "ACTIVE_REQUIRED_MISSING" }
    val plannedNotActive = endpointReadinessRows.count { it.status == "PLANNED_NOT_ACTIVE" }
    val directBypasses = violations.size
    val verdict = when {
        directBypasses > 0 || missingPolicies > 0 || missingShapeIds > 0 || endpointShapeMismatches > 0 || missingCachePolicies > 0 || missingHeaderPolicies > 0 -> "FAIL"
        specs.any { it.issues.isNotEmpty() } -> "PASS_WITH_WARNINGS"
        else -> "PASS"
    }
    val generatedAt = Instant.now().toString()

    outputDir.resolve("provider-policy-matrix.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("Provider", "Lifecycle status", "Adapter exists", "Policy exists", "Raw callers confined", "Runtime covered calls", "Capabilities", "Connectivity constraints", "Authentication mode", "Rate-limit expectations", "Retry behavior", "Cache expectations", "Fallback behavior", "Redaction policy", "Cache policy modes used", "Verdict")))
            providers.forEach { row ->
                appendLine(
                    csvLine(
                        listOf(
                            row.provider,
                            providerLifecycleStatus(row),
                            if (row.adapterClasses.isNotEmpty()) "yes" else "no",
                            if (row.hasPolicy) "yes" else "no",
                            if (row.directBypassCalls == 0) "yes" else "no",
                            row.runtimeCoveredCalls,
                            providerCapabilitySummary(row.provider),
                            "runtime lane/backoff/playback policy required before provider network start",
                            providerAuthMode(row.provider),
                            "provider backoff persisted by IntegrationRuntime policy",
                            "runtime retry/backoff policy; provider 429 must not start network during active backoff",
                            if (row.cachePolicyModes.isEmpty()) "unknown" else row.cachePolicyModes.joinToString("|"),
                            "no raw-client fallback outside approved adapter/infrastructure packages",
                            "cache keys, scope keys, auth secrets, and URL credentials redacted or hashed",
                            row.cachePolicyModes.joinToString("|"),
                            row.verdict
                        )
                    )
                )
            }
        }
    )

    outputDir.resolve("endpoint-shape-matrix.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("Shape ID", "Expected provider", "Expected method", "Expected path", "Request shape", "Response shape", "Auth context", "Transport expectations", "Redaction rules", "Actual adapter method", "Actual source", "Verdict")))
            expectedShapes.forEach { (shapeId, shape) ->
                val actual = specs.firstOrNull { it.apiShapeId == shapeId }
                appendLine(
                    csvLine(
                        listOf(
                            shapeId,
                            shape["provider"],
                            shape["method"],
                            shape["path"],
                            auditShapeValue(shape, "bulkShape"),
                            auditShapeValue(shape, "responseShape", "provider DTO or unknown"),
                            auditShapeValue(shape, "authContext", providerAuthMode(shape["provider"].orEmpty())),
                            auditShapeValue(shape, "transportExpectations", "Retrofit/OkHttp call must carry IntegrationRuntime context when network starts"),
                            auditShapeValue(shape, "redactionRules", "hash cache/scope keys; redact tokens, API keys, and full remote URLs"),
                            actual?.let { "${it.adapterClass}.${it.adapterMethod}" } ?: "",
                            actual?.sourceFile ?: "",
                            endpointShapeVerdict(shapeId, shape, actual, metadataRouterPrerequisiteShapeIds)
                        )
                    )
                )
            }
        }
    )

    outputDir.resolve("metadata-router-readiness.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("Shape ID", "Provider", "MetadataRouter prerequisite", "Readiness status", "Actual adapter method", "Actual source", "Required action")))
            endpointReadinessRows.forEach { row ->
                appendLine(
                    csvLine(
                        listOf(
                            row.shapeId,
                            row.provider,
                            if (row.metadataRouterRequired) "yes" else "no",
                            row.status,
                            row.actualAdapterMethod ?: "",
                            row.actualSource ?: "",
                            row.requiredAction
                        )
                    )
                )
            }
        }
    )

    val headerPolicyRows = buildHeaderPolicyRows(expectedShapes)
    val cachePolicyRows = buildCachePolicyContractRows(contractShapes, cacheContracts, specs)
    val cacheKeyVaryRows = buildCacheKeyVaryRows(contractShapes, specs)
    val bestPracticeRows = buildBestPracticeRows(contractShapes, expectedShapes, specs, bestPracticeRuleSupport)
    check(bestPracticeRows.none { it.verdict == "FAIL" }) {
        "Unsupported integration best-practice rules: ${
            bestPracticeRows.filter { it.verdict == "FAIL" }
                .map { it.rule }
                .distinct()
        }"
    }
    outputDir.resolve("header-policy-matrix.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("API shape", "Provider", "Header policy", "Required headers", "Optional headers", "Forbidden headers", "Credential location", "User-Agent policy", "Response headers captured", "Cache vary", "Verdict")))
            headerPolicyRows.forEach { row ->
                appendLine(
                    csvLine(
                        listOf(
                            row.apiShapeId,
                            row.provider,
                            row.headerPolicyId,
                            row.requiredHeaders,
                            row.optionalHeaders,
                            row.forbiddenHeaders,
                            row.credentialLocation,
                            row.userAgentPolicy,
                            row.responseHeadersCaptured,
                            row.cacheVary,
                            row.verdict
                        )
                    )
                )
            }
        }
    )
    outputDir.resolve("header-violations.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("API shape", "Provider", "Header policy", "Violation", "Required fix")))
            headerPolicyRows.flatMap { row -> row.issues.map { row to it } }.forEach { (row, issue) ->
                appendLine(csvLine(listOf(row.apiShapeId, row.provider, row.headerPolicyId, issue, "update expected_api_shapes.yaml headerPolicy or provider header policy mapping")))
            }
        }
    )
    val preferredHeaderPolicyIds = listOf(
        "json-body-no-auth-v1",
        "tvdb-json-bearer-v1",
        "tmdb-json-v1",
        "trakt-json-v2",
        "simkl-json-v1",
        "mdblist-api-key-v1",
        "omdb-query-api-key-v1",
        "rpdb-image-path-key-v1",
        "topposters-image-path-key-v1",
        "topposters-thumbnail-v1",
        "introdb-json-optional-bearer-v1",
        "real_debrid-json-token-v1",
        "premiumize-json-token-v1",
        "torbox-json-token-v1",
        "easy_debrid-json-token-v1"
    )
    val headerRuntimeSampleRows = (
        preferredHeaderPolicyIds.mapNotNull { policyId -> headerPolicyRows.firstOrNull { it.headerPolicyId == policyId } } +
            headerPolicyRows
    ).distinctBy { "${it.apiShapeId}:${it.headerPolicyId}" }.take(35)

    outputDir.resolve("header-runtime-sample.jsonl").writeText(
        headerRuntimeSampleRows.joinToString(separator = "\n", postfix = "\n") { row ->
            JsonOutput.toJson(
                linkedMapOf(
                    "eventName" to "IntegrationHeaderPolicyAudit",
                    "apiShapeId" to row.apiShapeId,
                    "provider" to row.provider,
                    "headerPolicyId" to row.headerPolicyId,
                    "requiredHeaders" to row.requiredHeaders.split('|').filter { it.isNotBlank() },
                    "optionalHeaders" to row.optionalHeaders.split('|').filter { it.isNotBlank() },
                    "forbiddenHeaders" to row.forbiddenHeaders.split('|').filter { it.isNotBlank() },
                    "credentialLocation" to row.credentialLocation,
                    "userAgentPolicy" to row.userAgentPolicy,
                    "responseHeadersCaptured" to row.responseHeadersCaptured.split('|').filter { it.isNotBlank() },
                    "cacheVary" to row.cacheVary.split('|').filter { it.isNotBlank() },
                    "redaction" to linkedMapOf(
                        "authorization" to "<redacted>",
                        "apiKeyPathSegment" to "<redacted:sha256>",
                        "queryApiKey" to "<redacted:sha256>"
                    )
                )
            )
        }
    )

    outputDir.resolve("provider-contract-provenance.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("provider", "lifecycleStatus", "sourceFile", "sourceType", "reviewedAt", "reviewer", "usedShapes", "activeRequiredShapes", "verdict")))
            providerContractRows.forEach { row ->
                appendLine(csvLine(listOf(row.provider, row.lifecycleStatus, row.sourceFile, row.sourceType, row.reviewedAt, row.reviewer, row.usedShapes, row.activeRequiredShapes, row.verdict)))
            }
        }
    )

    outputDir.resolve("cache-policy-matrix.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("apiShapeId", "provider", "expectedCacheContract", "expectedCachePolicy", "actualCachePolicy", "ttl", "staleAfterExpiry", "scope", "codec", "cacheKeyTemplate", "verdict", "issues")))
            cachePolicyRows.forEach { row ->
                appendLine(csvLine(listOf(row.apiShapeId, row.provider, row.expectedCacheContract, row.expectedCachePolicy, row.actualCachePolicy, row.ttl, row.staleAfterExpiry, row.scope, row.codec, row.cacheKeyTemplate, row.verdict, row.issues.joinToString("|"))))
            }
        }
    )

    outputDir.resolve("cache-key-vary-matrix.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("apiShapeId", "include", "forbidden", "actualTemplate", "verdict", "issues")))
            cacheKeyVaryRows.forEach { row ->
                appendLine(csvLine(listOf(row.apiShapeId, row.include, row.forbidden, row.actualTemplate, row.verdict, row.issues.joinToString("|"))))
            }
        }
    )

    outputDir.resolve("cache-policy-violations.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("apiShapeId", "provider", "issue", "requiredFix")))
            cachePolicyRows.flatMap { row -> row.issues.map { row to it } }.forEach { (row, issue) ->
                appendLine(csvLine(listOf(row.apiShapeId, row.provider, issue, "update expected_integration_contracts.yaml or runtime spec cache fields")))
            }
            cacheKeyVaryRows.flatMap { row -> row.issues.map { row to it } }.forEach { (row, issue) ->
                appendLine(csvLine(listOf(row.apiShapeId, contractShapes[row.apiShapeId]?.get("provider").orEmpty(), issue, "update cacheKeyContract include/forbidden fields or runtime cache key template")))
            }
        }
    )

    outputDir.resolve("provider-best-practice-matrix.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("apiShapeId", "provider", "rule", "verdict", "evidence")))
            bestPracticeRows.forEach { row ->
                appendLine(csvLine(listOf(row.apiShapeId, row.provider, row.rule, row.verdict, row.evidence)))
            }
        }
    )

    outputDir.resolve("boundary-violations.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("Offender", "Forbidden dependency", "File", "Why forbidden", "Required fix")))
            violations.forEach { violation ->
                appendLine(csvLine(listOf(violation.offender, violation.forbiddenDependency, violation.file, violation.whyForbidden, violation.requiredFix)))
            }
        }
    )

    outputDir.resolve("boundary-exemptions.csv").writeText(
        buildString {
            appendLine(csvLine(listOf("Path", "Host/provider", "Reason", "Owning file", "Review status")))
            exemptions.forEach { exemption ->
                appendLine(csvLine(listOf(exemption.path, exemption.hostProvider, exemption.reason, exemption.owningFile, exemption.reviewStatus)))
            }
        }
    )

    check(runtimeEventFixtureFile.isFile && runtimeEventFixtureFile.length() > 0L) {
        "Runtime event fixture is missing: ${runtimeEventFixtureFile.path}"
    }
    outputDir.resolve("runtime-event-sample.jsonl").writeText(runtimeEventFixtureFile.readText())

    val jsonPayload = linkedMapOf(
        "schemaVersion" to 1,
        "generatedAt" to generatedAt,
        "gitSha" to gitSha,
        "gitWorktree" to linkedMapOf(
            "state" to gitWorktreeState.state,
            "dirtyFileCount" to gitWorktreeState.dirtyFileCount,
            "untrackedFileCount" to gitWorktreeState.untrackedFileCount,
            "sample" to gitWorktreeState.sample
        ),
        "verdict" to verdict,
        "counts" to linkedMapOf(
            "totalInScopeProviders" to providers.size,
            "totalInScopeEndpointShapes" to expectedShapes.size,
            "totalRuntimeCoveredCalls" to specs.size,
            "totalDirectBypassCalls" to directBypasses,
            "totalMissingPolicyEntries" to missingPolicies,
            "totalEndpointShapeMismatches" to endpointShapeMismatches,
            "totalMissingEndpointShapeIds" to missingShapeIds,
            "totalMissingHeaderPolicies" to missingHeaderPolicies,
            "totalMissingOperationKeys" to specs.count { it.operationKey.isNullOrBlank() },
            "totalActiveRequiredEndpointShapesMissingRuntimeSpec" to activeRequiredMissing,
            "totalPlannedNotActiveEndpointShapes" to plannedNotActive,
            "totalUndocumentedExemptions" to undocumentedExemptions
        ),
        "gates" to linkedMapOf(
            "controlPlane" to verdict,
            "metadataRouterReadiness" to metadataRouterVerdict
        ),
        "providers" to providers.map { row ->
            linkedMapOf(
                "provider" to row.provider,
                "lifecycleStatus" to providerLifecycleStatus(row),
                "adapterClasses" to row.adapterClasses,
                "policyExists" to row.hasPolicy,
                "coverage" to linkedMapOf(
                    "runtimeCoveredCalls" to row.runtimeCoveredCalls,
                    "directBypassCalls" to row.directBypassCalls,
                    "missingEndpointShapeIds" to row.missingEndpointShapeIds
                ),
                "cachePolicyModes" to row.cachePolicyModes,
                "verdict" to row.verdict
            )
        },
        "calls" to specs.map { spec ->
            linkedMapOf(
                "callId" to spec.callId,
                "provider" to spec.provider,
                "apiShapeId" to spec.apiShapeId,
                "headerPolicyId" to spec.headerPolicyId,
                "expectedHeaderPolicyId" to spec.expectedHeaderPolicyId,
                "operationKey" to spec.operationKey,
                "adapterClass" to spec.adapterClass,
                "adapterMethod" to spec.adapterMethod,
                "rawClient" to spec.rawClient,
                "runtime" to linkedMapOf(
                    "cacheKeyTemplate" to normalizeAuditKeyTemplate(spec.cacheKeyTemplate),
                    "workClass" to spec.workClass,
                    "cachePolicy" to spec.cachePolicy,
                    "codec" to spec.codec,
                    "scope" to spec.scope
                ),
                "verdict" to spec.verdict,
                "issues" to spec.issues,
                "evidence" to linkedMapOf(
                    "specFile" to spec.sourceFile,
                    "line" to spec.line
                )
            )
        },
        "violations" to violations.map { violation ->
            linkedMapOf(
                "offender" to violation.offender,
                "forbiddenDependency" to violation.forbiddenDependency,
                "file" to violation.file,
                "whyForbidden" to violation.whyForbidden,
                "requiredFix" to violation.requiredFix
            )
        },
        "headerPolicies" to headerPolicyRows.map { row ->
            linkedMapOf(
                "apiShapeId" to row.apiShapeId,
                "provider" to row.provider,
                "headerPolicyId" to row.headerPolicyId,
                "requiredHeaders" to row.requiredHeaders,
                "optionalHeaders" to row.optionalHeaders,
                "forbiddenHeaders" to row.forbiddenHeaders,
                "credentialLocation" to row.credentialLocation,
                "userAgentPolicy" to row.userAgentPolicy,
                "responseHeadersCaptured" to row.responseHeadersCaptured,
                "cacheVary" to row.cacheVary,
                "verdict" to row.verdict,
                "issues" to row.issues
            )
        },
        "providerContracts" to providerContractRows.map { row ->
            linkedMapOf(
                "provider" to row.provider,
                "lifecycleStatus" to row.lifecycleStatus,
                "sourceFile" to row.sourceFile,
                "sourceType" to row.sourceType,
                "reviewedAt" to row.reviewedAt,
                "reviewer" to row.reviewer,
                "usedShapes" to row.usedShapes,
                "activeRequiredShapes" to row.activeRequiredShapes,
                "verdict" to row.verdict
            )
        },
        "cachePolicies" to cachePolicyRows.map { row ->
            linkedMapOf(
                "apiShapeId" to row.apiShapeId,
                "provider" to row.provider,
                "expectedCacheContract" to row.expectedCacheContract,
                "expectedCachePolicy" to row.expectedCachePolicy,
                "actualCachePolicy" to row.actualCachePolicy,
                "ttl" to row.ttl,
                "staleAfterExpiry" to row.staleAfterExpiry,
                "scope" to row.scope,
                "codec" to row.codec,
                "cacheKeyTemplate" to row.cacheKeyTemplate,
                "verdict" to row.verdict,
                "issues" to row.issues
            )
        },
        "bestPracticeRules" to bestPracticeRows.map { row ->
            linkedMapOf(
                "apiShapeId" to row.apiShapeId,
                "provider" to row.provider,
                "rule" to row.rule,
                "verdict" to row.verdict,
                "evidence" to row.evidence
            )
        },
        "metadataRouterReadiness" to endpointReadinessRows.map { row ->
            linkedMapOf(
                "shapeId" to row.shapeId,
                "provider" to row.provider,
                "status" to row.status,
                "metadataRouterRequired" to row.metadataRouterRequired,
                "actualAdapterMethod" to row.actualAdapterMethod,
                "actualSource" to row.actualSource,
                "requiredAction" to row.requiredAction
            )
        },
        "exemptions" to exemptions.map { exemption ->
            linkedMapOf(
                "path" to exemption.path,
                "hostProvider" to exemption.hostProvider,
                "reason" to exemption.reason,
                "owningFile" to exemption.owningFile,
                "reviewStatus" to exemption.reviewStatus
            )
        }
    )
    outputDir.resolve("integration-runtime-audit.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(jsonPayload)) + "\n")

    outputDir.resolve("integration-runtime-audit.md").writeText(
        buildString {
            appendLine("# IntegrationRuntime Connectivity & Policy Audit")
            appendLine()
            appendLine("Generated: `$generatedAt`")
            appendLine("Git SHA: `$gitSha`")
            appendLine("Git worktree: `${gitWorktreeState.state}` (${gitWorktreeState.dirtyFileCount} changed files, ${gitWorktreeState.untrackedFileCount} untracked)")
            appendLine()
            appendLine("## Section A - Executive Verdict")
            appendLine()
            appendLine("Verdict: `$verdict`")
            appendLine()
            appendLine("Control-plane gate: `$verdict`")
            appendLine("MetadataRouter-readiness gate: `$metadataRouterVerdict`")
            appendLine()
            appendLine("| Metric | Count |")
            appendLine("| --- | ---: |")
            appendLine("| total in-scope providers | ${providers.size} |")
            appendLine("| total in-scope endpoint shapes | ${expectedShapes.size} |")
            appendLine("| total runtime-covered calls | ${specs.size} |")
            appendLine("| total direct-bypass calls | $directBypasses |")
            appendLine("| total missing policy entries | $missingPolicies |")
            appendLine("| total missing policy fields | $missingCachePolicies |")
            appendLine("| total endpoint-shape mismatches | $endpointShapeMismatches |")
            appendLine("| total missing endpoint-shape ids | $missingShapeIds |")
            appendLine("| total missing header policies | $missingHeaderPolicies |")
            appendLine("| total missing operation keys | ${specs.count { it.operationKey.isNullOrBlank() }} |")
            appendLine("| active required endpoint shapes missing runtime spec | $activeRequiredMissing |")
            appendLine("| planned-not-active endpoint shapes | $plannedNotActive |")
            appendLine("| total undocumented exemptions | $undocumentedExemptions |")
            appendLine()
            appendLine("## Section B - Provider Coverage Summary")
            appendLine()
            appendLine("| Provider | Lifecycle status | Adapter exists | Policy exists | Raw callers confined | Runtime covered calls | Cache policy modes used | Verdict |")
            appendLine("| --- | --- | ---: | ---: | ---: | ---: | --- | --- |")
            providers.forEach { row ->
                appendLine("| ${row.provider} | ${providerLifecycleStatus(row)} | ${if (row.adapterClasses.isNotEmpty()) "yes" else "no"} | ${if (row.hasPolicy) "yes" else "no"} | ${if (row.directBypassCalls == 0) "yes" else "no"} | ${row.runtimeCoveredCalls} | ${row.cachePolicyModes.joinToString(", ").ifBlank { "none" }} | ${row.verdict} |")
            }
            appendLine()
            appendLine("## Section C - Call-Site Coverage Matrix")
            appendLine()
            appendLine("| Call ID | Provider | Header policy | Adapter method | Operation key | Runtime spec key template | Work class | Cache policy | Raw client | Verdict |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            specs.forEach { spec ->
                appendLine("| `${spec.callId}` | ${spec.provider} | `${spec.headerPolicyId ?: ""}` | `${spec.adapterClass}.${spec.adapterMethod}` | `${spec.operationKey ?: ""}` | `${normalizeAuditKeyTemplate(spec.cacheKeyTemplate) ?: ""}` | ${spec.workClass ?: "MISSING"} | ${spec.cachePolicy ?: "MISSING"} | `${spec.rawClient ?: ""}` | ${spec.verdict} |")
            }
            appendLine()
            appendLine("## Section D - Runtime Policy Matrix")
            appendLine()
            appendLine("| Call ID | Provider | Scope | Work class | Cache policy | Codec | Lane concurrency | Backoff scope |")
            appendLine("| --- | --- | --- | --- | --- | --- | ---: | --- |")
            specs.forEach { spec ->
                appendLine("| `${spec.callId}` | ${spec.provider} | ${spec.scope ?: "MISSING"} | ${spec.workClass ?: "MISSING"} | ${spec.cachePolicy ?: "MISSING"} | `${spec.codec ?: ""}` | 1 | provider/scope |")
            }
            appendLine()
            appendLine("## Section E - Endpoint-Shape Matrix")
            appendLine()
            appendLine("| Shape ID | Expected shape | Request shape | Auth context | Redaction rules | Actual adapter method | Actual source | Verdict |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
            expectedShapes.forEach { (shapeId, shape) ->
                val actual = specs.firstOrNull { it.apiShapeId == shapeId }
                appendLine("| `$shapeId` | `${shape["method"] ?: ""} ${shape["path"] ?: ""}` | ${auditShapeValue(shape, "bulkShape")} | ${auditShapeValue(shape, "authContext", providerAuthMode(shape["provider"].orEmpty()))} | ${auditShapeValue(shape, "redactionRules", "hash/redact credentials and URLs")} | `${actual?.let { "${it.adapterClass}.${it.adapterMethod}" } ?: ""}` | `${actual?.sourceFile ?: ""}` | ${endpointShapeVerdict(shapeId, shape, actual, metadataRouterPrerequisiteShapeIds)} |")
            }
            appendLine()
            appendLine("## Section E1 - MetadataRouter Readiness")
            appendLine()
            appendLine("Gate: `$metadataRouterVerdict`")
            appendLine()
            appendLine("| Shape ID | Provider | Required for MetadataRouter | Status | Actual adapter method | Required action |")
            appendLine("| --- | --- | ---: | --- | --- | --- |")
            endpointReadinessRows.forEach { row ->
                appendLine("| `${row.shapeId}` | ${row.provider} | ${if (row.metadataRouterRequired) "yes" else "no"} | ${row.status} | `${row.actualAdapterMethod ?: ""}` | ${row.requiredAction} |")
            }
            appendLine()
            appendLine("## Section E2 - Header Policy Matrix")
            appendLine()
            appendLine("| API shape | Provider | Header policy | Required headers | Optional headers | Forbidden headers | Credential location | User-Agent policy | Response headers captured | Cache vary | Verdict |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            headerPolicyRows.forEach { row ->
                appendLine("| `${row.apiShapeId}` | ${row.provider} | `${row.headerPolicyId ?: ""}` | `${row.requiredHeaders}` | `${row.optionalHeaders}` | `${row.forbiddenHeaders}` | ${row.credentialLocation} | ${row.userAgentPolicy} | `${row.responseHeadersCaptured}` | `${row.cacheVary}` | ${row.verdict} |")
            }
            appendLine()
            appendLine("## Section E3 - Cache-Policy Conformance")
            appendLine()
            appendLine("| API shape | Provider | Expected cache contract | Expected policy | Actual policy | TTL | Stale after expiry | Scope | Codec | Verdict |")
            appendLine("| --- | --- | --- | --- | --- | ---: | ---: | --- | --- | --- |")
            cachePolicyRows.forEach { row ->
                appendLine("| `${row.apiShapeId}` | ${row.provider} | `${row.expectedCacheContract}` | ${row.expectedCachePolicy} | ${row.actualCachePolicy ?: ""} | ${row.ttl} | ${row.staleAfterExpiry} | ${row.scope} | `${row.codec ?: ""}` | ${row.verdict} |")
            }
            appendLine()
            appendLine("## Section E4 - Cache-Key Vary Conformance")
            appendLine()
            appendLine("| API shape | Include fields | Forbidden material | Actual template | Verdict |")
            appendLine("| --- | --- | --- | --- | --- |")
            cacheKeyVaryRows.forEach { row ->
                appendLine("| `${row.apiShapeId}` | `${row.include}` | `${row.forbidden}` | `${row.actualTemplate ?: ""}` | ${row.verdict} |")
            }
            appendLine()
            appendLine("## Section E5 - Provider Contract Provenance")
            appendLine()
            appendLine("| Provider | Lifecycle | Source file | Source type | Reviewed at | Reviewer | Used shapes | Active-required shapes | Verdict |")
            appendLine("| --- | --- | --- | --- | --- | --- | ---: | ---: | --- |")
            providerContractRows.forEach { row ->
                appendLine("| ${row.provider} | ${row.lifecycleStatus} | `${row.sourceFile}` | ${row.sourceType} | ${row.reviewedAt} | ${row.reviewer} | ${row.usedShapes} | ${row.activeRequiredShapes} | ${row.verdict} |")
            }
            appendLine()
            appendLine("## Section E6 - Best-Practice Provider-Efficiency Conformance")
            appendLine()
            appendLine("| API shape | Provider | Rule | Verdict | Evidence |")
            appendLine("| --- | --- | --- | --- | --- |")
            bestPracticeRows.forEach { row ->
                appendLine("| `${row.apiShapeId}` | ${row.provider} | ${row.rule} | ${row.verdict} | ${row.evidence} |")
            }
            appendLine()
            appendLine("## Section F - Boundary And Bypass Report")
            appendLine()
            if (violations.isEmpty()) {
                appendLine("No boundary violations detected by the static scanner.")
            } else {
                appendLine("| Offender | Forbidden dependency | File | Why forbidden | Required fix |")
                appendLine("| --- | --- | --- | --- | --- |")
                violations.forEach { violation ->
                    appendLine("| `${violation.offender}` | ${violation.forbiddenDependency} | `${violation.file}` | ${violation.whyForbidden} | ${violation.requiredFix} |")
                }
            }
            appendLine()
            appendLine("## Section G - Runtime Event Audit")
            appendLine()
            appendLine("Sample events are written to `runtime-event-sample.jsonl`. Rows include `eventName`, source component, provider context, and redaction metadata. `DefaultIntegrationRuntime` emits the same phase model in tests, including fresh-cache hits with `loaderInvoked=false` and network starts with `networkStarted=true`.")
            appendLine()
            appendLine("## Exemptions")
            appendLine()
            appendLine("| Path | Host/provider | Reason | Owning file | Review status |")
            appendLine("| --- | --- | --- | --- | --- |")
            exemptions.forEach { exemption ->
                appendLine("| `${exemption.path}` | ${exemption.hostProvider} | ${exemption.reason} | `${exemption.owningFile}` | ${exemption.reviewStatus} |")
            }
        }
    )
}
