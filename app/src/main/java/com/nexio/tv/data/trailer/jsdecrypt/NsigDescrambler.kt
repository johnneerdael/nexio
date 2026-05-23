package com.nexio.tv.data.trailer.jsdecrypt

import android.net.Uri
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class NsigDescrambler @Inject constructor() {

    private var playerJsHash: Int? = null
    private var cachedNFunctionCode: Pair<String, String>? = null

    @Synchronized
    private fun cachedFunctionFor(jsCode: String): Pair<String, String>? {
        val hash = jsCode.hashCode()
        if (hash != playerJsHash) {
            playerJsHash = hash
            cachedNFunctionCode = null
        }
        return cachedNFunctionCode
    }

    @Synchronized
    private fun cacheFunction(jsCode: String, code: Pair<String, String>) {
        playerJsHash = jsCode.hashCode()
        cachedNFunctionCode = code
    }

    suspend fun descrambleUrl(url: String, jsCode: String?): String {
        if (jsCode.isNullOrBlank()) return url
        if (!url.contains("googlevideo.com")) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val nSignature = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: return url
        val descrambled = descramble(nSignature, jsCode) ?: return url
        if (descrambled == nSignature) return url

        val rebuilt = uri.buildUpon().clearQuery()
        for (key in uri.queryParameterNames) {
            val values = uri.getQueryParameters(key)
            if (values.isEmpty()) {
                rebuilt.appendQueryParameter(key, "")
            } else {
                for (i in values.indices) {
                    rebuilt.appendQueryParameter(key, if (key == "n") descrambled else values[i])
                }
            }
        }
        return rebuilt.build().toString()
    }

    private suspend fun descramble(nSignature: String, jsCode: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val extractor = JsNsigExtractor(jsCode)
                val functionCode = cachedFunctionFor(jsCode) ?: run {
                    val functionName = extractor.extractNFunctionName() ?: return@withContext null
                    extractor.extractNFunctionCode(functionName).also { cacheFunction(jsCode, it) }
                }
                extractor.nFunctionResponse(functionCode, nSignature)
            } catch (t: Throwable) {
                Log.w(TAG, "n= descramble failed: ${t.message}")
                null
            }
        }

    private class JsNsigExtractor(private val jsCode: String) {

        fun extractNFunctionName(): String? {
            for (i in DEOBFUSCATION_FUNCTION_NAME_REGEXES.indices) {
                val match = DEOBFUSCATION_FUNCTION_NAME_REGEXES[i].find(jsCode) ?: continue
                val functionName = match.groups[1]?.value ?: continue
                val arrayIndex = if (match.groups.size > 2) {
                    match.groups[2]?.value?.toIntOrNull()
                } else {
                    null
                }
                if (arrayIndex == null) return functionName

                val arrayPattern = Regex("""var\s+${Regex.escape(functionName)}\s*=\s*\[(.+?)][;,]""")
                val names = arrayPattern.find(jsCode)
                    ?.groups
                    ?.get(1)
                    ?.value
                    ?.split(",")
                    ?.map { it.trim() }
                    ?: continue
                return names.getOrNull(arrayIndex)
            }
            return null
        }

        fun extractNFunctionCode(funcName: String): Pair<String, String> {
            val parsed = parseFunction(funcName)
                ?: throw IllegalStateException("Could not find JS n function \"$funcName\"")
            val args = parsed.first
            val code = parsed.second
            val fixedCode = fixupNFunctionCode(args.split(","), code)
            return fixedCode.first.joinToString(",") to fixedCode.second
        }

        private fun parseFunction(funcName: String): Pair<String, String>? {
            val escaped = Regex.escape(funcName)
            val patterns = listOf(
                Regex("""${escaped}\s*=\s*function\s*\("""),
                Regex("""function\s+${escaped}\s*\("""),
                Regex("""(?:var|const|let)\s+${escaped}\s*=\s*function\s*\(""")
            )
            for (i in patterns.indices) {
                val match = patterns[i].find(jsCode) ?: continue
                val openParen = jsCode.indexOf('(', match.range.last)
                if (openParen < 0) continue
                val closeParen = findClosing(openParen, '(', ')') ?: continue
                val openBrace = jsCode.indexOf('{', closeParen)
                if (openBrace < 0) continue
                val closeBrace = findClosing(openBrace, '{', '}') ?: continue
                val args = jsCode.substring(openParen + 1, closeParen)
                val body = jsCode.substring(openBrace + 1, closeBrace)
                return args to body
            }
            return null
        }

        private fun findClosing(startIndex: Int, open: Char, close: Char): Int? {
            var depth = 0
            var inString: Char? = null
            var escaped = false
            for (i in startIndex until jsCode.length) {
                val ch = jsCode[i]
                if (inString != null) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == inString -> inString = null
                    }
                    continue
                }
                when (ch) {
                    '\'', '"', '`' -> inString = ch
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return null
        }

        suspend fun nFunctionResponse(functionCode: Pair<String, String>, nSignature: String): String {
            val escaped = nSignature
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            val formattedFunction = "(function(${functionCode.first}) { ${functionCode.second} }(\"$escaped\"))"
            val result = captureReturnFromEval(formattedFunction)
            val values = when (result) {
                is Array<*> -> result.toList()
                is List<*> -> result
                else -> return result?.toString().orEmpty()
            }
            return values.joinToString("") { it.toString() }
        }

        private fun fixupNFunctionCode(argNames: List<String>, code: String): Pair<List<String>, String> {
            val firstArg = argNames.firstOrNull().orEmpty()
            val regex = Regex(
                """;\s*if\s*\(\s*typeof\s+[a-zA-Z0-9_${'$'}]+\s*===?\s*(['"])undefined\1\s*\)\s*return\s+$firstArg;"""
            )
            return argNames to code.replace(regex, ";")
        }

        private companion object {
            private const val SINGLE = """[a-zA-Z0-9_${'$'}]"""
            private const val MULTI = """[a-zA-Z0-9_${'$'}]+"""
            private const val ARRAY_ACCESS = """\[(\d+)]"""

            private val DEOBFUSCATION_FUNCTION_NAME_REGEXES = listOf(
                Regex("""([A-Za-z0-9_${'$'}]{2,})=function[\s\S]*?return [A-Z]\[\d+]"""),
                Regex("""$SINGLE="nn"\[+$MULTI\.$MULTI],$MULTI\($MULTI\),$MULTI=$MULTI\.$MULTI\[$MULTI]\|\|null\)&&\($MULTI=($MULTI)$ARRAY_ACCESS"""),
                Regex("""$SINGLE="nn"\[+$MULTI\.$MULTI],$MULTI\($MULTI\),$MULTI=$MULTI\.$MULTI\[$MULTI]\|\|null\)[\s\S]+?\|\|($MULTI)\(""\)"""),
                Regex(""",$MULTI\($MULTI\),$MULTI=$MULTI\.$MULTI\[$MULTI]\|\|null\)&&\(\b$MULTI=($MULTI)$ARRAY_ACCESS\($SINGLE\),$MULTI\.set\((?:"n+"|$MULTI),$MULTI\)"""),
                Regex("""$SINGLE="nn"\[+$MULTI\.$MULTI],$MULTI=$MULTI\.get\($MULTI\)\)[\s\S]+?\|\|($MULTI)\(""\)"""),
                Regex("""$SINGLE="nn"\[+$MULTI\.$MULTI],$MULTI=$MULTI\.get\($MULTI\)\)&&\($MULTI=($MULTI)$ARRAY_ACCESS"""),
                Regex("""\($SINGLE=String\.fromCharCode\(110\),$SINGLE=$SINGLE\.get\($SINGLE\)\)&&\($SINGLE=($MULTI)(?:$ARRAY_ACCESS)?\($SINGLE\)"""),
                Regex("""\.get\("n"\)\)&&\($SINGLE=($MULTI)(?:$ARRAY_ACCESS)?\($SINGLE\)""")
            )
        }
    }

    private companion object {
        const val TAG = "NsigDescrambler"
    }
}
