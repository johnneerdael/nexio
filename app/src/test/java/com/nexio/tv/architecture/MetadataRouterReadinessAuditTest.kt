package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterReadinessAuditTest {
    private val reportDir = File("app/build/reports/integration-runtime-audit")
    private val routerPrerequisiteShapes = File("app/src/test/resources/integration/metadata_router_prerequisites.txt")
        .readLines()
        .map { it.substringBefore("#").trim() }
        .filter { it.isNotBlank() }
        .toSet()

    @Test
    fun `MetadataRouter active required endpoint shapes are runtime covered`() {
        val csv = reportDir.resolve("metadata-router-readiness.csv")
        assertTrue("Run :app:generateIntegrationRuntimeAudit before this assertion.", csv.isFile)

        val missing = csv.readLines()
            .drop(1)
            .filter { it.contains(",ACTIVE_REQUIRED_MISSING,") }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `cache policy audit has no hashCode credential evidence`() {
        val csv = reportDir.resolve("cache-policy-violations.csv")
        assertTrue("Run :app:generateIntegrationRuntimeAudit before this assertion.", csv.isFile)

        val violations = csv.readLines()
            .drop(1)
            .filter { it.contains("hashCode") }

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `MetadataRouter prerequisite endpoint shapes have locked runtime policy evidence`() {
        val readiness = readCsv("metadata-router-readiness.csv", "Shape ID").singleRows()
        val headers = readCsv("header-policy-matrix.csv", "API shape").singleRows()
        val cachePolicies = readCsv("cache-policy-matrix.csv", "apiShapeId").singleRows()
        val endpointShapes = readCsv("endpoint-shape-matrix.csv", "Shape ID").singleRows()
        val bestPracticeRules = readCsv("provider-best-practice-matrix.csv", "apiShapeId")
            .filterKeys { it in routerPrerequisiteShapes }

        val missing = routerPrerequisiteShapes.filter { shapeId ->
            readiness[shapeId]?.get("Readiness status") != "ACTIVE_RUNTIME_COVERED" ||
                readiness[shapeId]?.get("Required action") != "none" ||
                readiness[shapeId]?.get("Actual adapter method").isNullOrBlank() ||
                readiness[shapeId]?.get("Actual source").isNullOrBlank()
        }
        assertEquals("Router prerequisite shapes must be runtime-covered with concrete adapter evidence.", emptyList<String>(), missing)

        val endpointProblems = routerPrerequisiteShapes.filter { shapeId ->
            endpointShapes[shapeId]?.get("Verdict") != "ACTIVE_RUNTIME_COVERED" ||
                endpointShapes[shapeId]?.get("Expected method").isNullOrBlank() ||
                endpointShapes[shapeId]?.get("Expected path").isNullOrBlank()
        }
        assertEquals("Router prerequisite endpoint-shape contracts must be locked and active.", emptyList<String>(), endpointProblems)

        val headerProblems = routerPrerequisiteShapes.filter { shapeId ->
            headers[shapeId]?.get("Verdict") != "PASS" ||
                headers[shapeId]?.get("Header policy").isNullOrBlank() ||
                headers[shapeId]?.get("User-Agent policy").isNullOrBlank()
        }
        assertEquals("Router prerequisite header policies must pass and be explicit.", emptyList<String>(), headerProblems)

        val cacheProblems = routerPrerequisiteShapes.filter { shapeId ->
            val row = cachePolicies[shapeId]
            row?.get("verdict") != "PASS" ||
                row["expectedCachePolicy"].isNullOrBlank() ||
                row["actualCachePolicy"].isNullOrBlank() ||
                row["scope"].isNullOrBlank()
        }
        assertEquals("Router prerequisite cache policies must pass and be explicit.", emptyList<String>(), cacheProblems)

        val bestPracticeWarnings = bestPracticeRules
            .filterValues { rows -> rows.any { row -> row["verdict"] != "PASS" } }
            .keys
            .sorted()
        assertEquals("Router prerequisite best-practice rules must pass; warnings are only acceptable outside router scope.", emptyList<String>(), bestPracticeWarnings)
    }

    @Test
    fun `provider blueprint and contract resources are tracked by git`() {
        val required = listOf(
            "apiblueprints/tmdb-contract.md",
            "apiblueprints/tvdb-contract.md",
            "apiblueprints/kitsu-contract.md",
            "apiblueprints/trakt-simkl.md",
            "apiblueprints/rpdb-topposters.md",
            "apiblueprints/tidb-mdblist.md",
            "apiblueprints/full-audit.md",
            "app/src/test/resources/integration/expected_api_shapes.yaml",
            "app/src/test/resources/integration/expected_integration_contracts.yaml"
        )

        val process = ProcessBuilder("git", "ls-files")
            .directory(File("."))
            .redirectErrorStream(true)
            .start()
        val tracked = process.inputStream.bufferedReader().readLines().toSet()
        val exitCode = process.waitFor()
        assertEquals("git ls-files failed.", 0, exitCode)

        val missing = required.filterNot(tracked::contains)
        assertEquals(emptyList<String>(), missing)
    }

    private fun readCsv(fileName: String, keyColumn: String): Map<String, List<Map<String, String>>> {
        val csv = reportDir.resolve(fileName)
        assertTrue("Run :app:generateIntegrationRuntimeAudit before this assertion.", csv.isFile)

        val lines = csv.readLines().filter { it.isNotBlank() }
        val headers = parseCsvLine(lines.first())
        val keyIndex = headers.indexOf(keyColumn)
        assertTrue("Missing key column $keyColumn in $fileName.", keyIndex >= 0)

        return lines.drop(1)
            .map { parseCsvLine(it) }
            .filter { it.size > keyIndex }
            .groupBy(
                keySelector = { values -> values[keyIndex] },
                valueTransform = { values ->
                    val row = headers.mapIndexed { index, header -> header to values.getOrElse(index) { "" } }.toMap()
                    row
                }
            )
    }

    private fun Map<String, List<Map<String, String>>>.singleRows(): Map<String, Map<String, String>> =
        mapValues { (key, rows) ->
            assertEquals("Expected exactly one CSV row for $key.", 1, rows.size)
            rows.single()
        }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        values += current.toString()
        return values
    }
}
