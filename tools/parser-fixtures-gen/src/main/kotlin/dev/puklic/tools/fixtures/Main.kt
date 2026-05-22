package dev.puklic.tools.fixtures

import dev.puklic.chatparser.parseRichText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Parser fixtures generator — Phase 1 CLI utility.
 *
 * Usage:
 *   parser-fixtures-gen "**bold** _italic_"   # arg-style
 *   echo "**bold**" | parser-fixtures-gen     # stdin
 *   parser-fixtures-gen --help
 */
private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
    classDiscriminator = "kind"
}

fun main(args: Array<String>) {
    val input = readInput(args) ?: return
    val doc = parseRichText(input)
    println(JSON.encodeToString(doc.toSurrogate()))
}

/** Returns the parser input, or null if the caller already handled output (help). */
internal fun readInput(args: Array<String>): String? {
    if (args.isNotEmpty() && (args[0] == "--help" || args[0] == "-h")) {
        System.err.println("Usage: parser-fixtures-gen [raw markdown text | <stdin>]")
        System.err.println("Outputs RichTextDocument JSON to stdout.")
        return null
    }
    return if (args.isEmpty()) {
        System.`in`.bufferedReader().readText()
    } else {
        args.joinToString(" ")
    }
}

/** Public entry for unit tests — runs the full pipeline and returns the JSON string. */
fun renderJson(input: String): String = JSON.encodeToString(parseRichText(input).toSurrogate())
