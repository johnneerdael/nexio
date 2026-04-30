package com.nexio.tv.core.catalog.rails

/**
 * Marks a file that ships a catalog-rail loader but has not yet been migrated to
 * implement [CatalogRailSource].
 *
 * The `generateCatalogRailUniformityAudit` Gradle task scans for either [CatalogRailSource]
 * implementation or this annotation in every file listed in
 * `app/src/test/resources/catalog/known_rail_loader_paths.txt`. A file in the manifest with
 * neither marker fails the audit. An annotation on a file NOT in the manifest also fails the
 * audit (prevents drift).
 *
 * Source-retention so it leaves no runtime footprint.
 *
 * @property reason Free-form one-line summary of why this file isn't yet conformant.
 * @property tracking Path to the migration plan document that will retire this annotation.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
annotation class CatalogRailNotYetUniform(
    val reason: String,
    val tracking: String
)
