package com.nexio.tv.data.repository.benchmark

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

data class BenchmarkAwareScoringCorpusManifest(
    val corpusVersion: Int = 1,
    val name: String,
    val datasets: List<String>,
    val variants: List<String> = emptyList()
) {
    companion object {
        fun fromJson(json: String, gson: Gson = Gson()): BenchmarkAwareScoringCorpusManifest {
            return gson.fromJson(json, BenchmarkAwareScoringCorpusManifest::class.java)
        }
    }
}

data class BenchmarkAwareScoringCorpus(
    val manifest: BenchmarkAwareScoringCorpusManifest,
    val dataset: BenchmarkAwareScoringDataset,
    val variants: List<BenchmarkAwareNamedConfigVariant>
)

object BenchmarkAwareScoringCorpusLoader {

    fun load(directory: Path, gson: Gson = Gson()): BenchmarkAwareScoringCorpus {
        val manifestPath = directory.resolve("manifest.json")
        val manifest = BenchmarkAwareScoringCorpusManifest.fromJson(
            json = String(Files.readAllBytes(manifestPath)),
            gson = gson
        )
        val scenarios = manifest.datasets.flatMap { relativePath ->
            val dataset = BenchmarkAwareScoringDataset.fromPath(directory.resolve(relativePath), gson)
            dataset.scenarios
        }
        val variants = manifest.variants.map { relativePath ->
            val path = directory.resolve(relativePath)
            BenchmarkAwareNamedConfigVariant(
                name = path.fileName.toString(),
                config = BenchmarkAwareStreamScoringConfig.fromJson(String(Files.readAllBytes(path)), gson)
            )
        }
        return BenchmarkAwareScoringCorpus(
            manifest = manifest,
            dataset = BenchmarkAwareScoringDataset(
                datasetVersion = 1,
                scenarios = scenarios
            ),
            variants = variants
        )
    }
}
