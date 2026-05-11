package com.nexio.tv.data.trailer.cipher

/**
 * Parses raw YouTube player JS and extracts a [CipherManifest] describing
 * the sequence of operations applied to the signature string.
 *
 * Port of YoutubeExplode's PlayerSource.cs. Regex strings kept
 * structurally identical to upstream so they can be updated by lifting
 * patterns verbatim if YouTube rotates their player JS minification.
 *
 * Returns `null` if any required pattern fails to match — caller treats
 * that as "cipher unavailable, drop signatureCipher entries."
 */
internal object PlayerSourceParser {

    fun parse(playerJs: String): CipherManifest? {
        // $ in raw strings must be written as ${'$'} to avoid Kotlin string-template
        // interpretation. The character class [$_\w] is intentional — matches JS identifier
        // chars including dollar-sign, underscore, and word chars.
        val idChar = """[${'$'}_\w]"""

        // 1. Signature timestamp — 5 digits after `signatureTimestamp:` or `sts:`
        val signatureTimestamp =
            Regex("""(?:signatureTimestamp|sts):(\d{5})""")
                .find(playerJs)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 2. Cipher entrypoint — a function that does
        //    str.split("") ... return X.join("")
        val cipherCallsite =
            Regex(
                """${idChar}+=function\(${idChar}+\)\{(${idChar}+)=\1\.split\(['"]{2}\);.*?return \1\.join\(['"]{2}\)\}""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(playerJs)
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 3. Cipher container object name
        val cipherContainerName =
            Regex("""(${idChar}+)\.${idChar}+\(${idChar}+,\d+\);""")
                .find(cipherCallsite)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 4. Cipher container definition
        val cipherDefinition =
            Regex(
                """var ${Regex.escape(cipherContainerName)}=\{.*?\};""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(playerJs)
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 5. Identify each named function by its body signature:
        //    - contains `%`  → swap
        //    - contains `splice` → splice
        //    - contains `reverse` → reverse
        val swapFuncName = Regex(
            """(${idChar}+):function\(${idChar}+,${idChar}+\)\{+[^}]*?%[^}]*?\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(cipherDefinition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        val spliceFuncName = Regex(
            """(${idChar}+):function\(${idChar}+,${idChar}+\)\{+[^}]*?splice[^}]*?\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(cipherDefinition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        val reverseFuncName = Regex(
            """(${idChar}+):function\(${idChar}+\)\{+[^}]*?reverse[^}]*?\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(cipherDefinition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        // 6. Walk the entrypoint statements; build the ordered ops list
        val operations = mutableListOf<CipherOperation>()
        val twoArgCallPattern = Regex("""${idChar}+\.(${idChar}+)\(${idChar}+,(\d+)\)""")
        val oneArgCallPattern = Regex("""${idChar}+\.(${idChar}+)\(${idChar}+\)""")

        for (statement in cipherCallsite.split(';')) {
            twoArgCallPattern.find(statement)?.let { match ->
                val calledFuncName = match.groupValues[1]
                val index = match.groupValues[2].toIntOrNull() ?: return@let
                when (calledFuncName) {
                    swapFuncName -> operations += SwapCipherOperation(index)
                    spliceFuncName -> operations += SpliceCipherOperation(index)
                    reverseFuncName -> operations += ReverseCipherOperation
                }
                return@let
            }
            oneArgCallPattern.find(statement)?.let { match ->
                val calledFuncName = match.groupValues[1]
                if (calledFuncName == reverseFuncName) {
                    operations += ReverseCipherOperation
                }
            }
        }

        return CipherManifest(signatureTimestamp, operations)
    }
}
