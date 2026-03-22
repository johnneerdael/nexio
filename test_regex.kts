import java.util.regex.Pattern

fun main() {
    try {
        val regexStr = """(?<![^\s\[(_\-.,])(foo)(?=[\s)\]_.\-,]|$)"""
        java.util.regex.Pattern.compile(regexStr)
        println("Regex 1 success")
        
        val langStr = """multi(?![ .\-_]?sub(title)?s?)"""
        val fullLang = """(?<![^\s\[(_\-.,])($langStr)(?=[\s)\]_.\-,]|$)"""
        java.util.regex.Pattern.compile(fullLang)
        println("Regex 2 success")
    } catch(e: Exception) {
        println(e.toString())
    }
}
