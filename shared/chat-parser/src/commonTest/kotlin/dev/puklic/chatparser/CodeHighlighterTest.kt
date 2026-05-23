package dev.puklic.chatparser

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

@Suppress("LargeClass")
class CodeHighlighterTest : StringSpec({

    fun List<CodeToken>.kindsAt(source: String): List<Pair<String, CodeTokenKind>> =
        map { source.substring(it.start, it.end) to it.kind }

    "empty source returns empty list" {
        CodeHighlighter.tokenize("kotlin", "").shouldBeEmpty()
    }

    "unknown language returns no tokens (renderer falls back to plain)" {
        CodeHighlighter.tokenize("brainfuck", "++>>--<<.").shouldBeEmpty()
    }

    "null language returns no tokens" {
        CodeHighlighter.tokenize(null, "anything").shouldBeEmpty()
    }

    "kotlin: keyword, function, val, number" {
        val src = "fun main() { val x = 1 }"
        val toks = CodeHighlighter.tokenize("kotlin", src).kindsAt(src)
        toks shouldContain ("fun" to CodeTokenKind.Keyword)
        toks shouldContain ("main" to CodeTokenKind.Function)
        toks shouldContain ("val" to CodeTokenKind.Keyword)
        toks shouldContain ("1" to CodeTokenKind.Number)
    }

    "kotlin: string literal" {
        val src = """val s = "hello""""
        val toks = CodeHighlighter.tokenize("kotlin", src).kindsAt(src)
        toks shouldContain ("\"hello\"" to CodeTokenKind.String)
    }

    "kotlin: line comment" {
        val src = "// hi\nval x = 1"
        val toks = CodeHighlighter.tokenize("kotlin", src).kindsAt(src)
        toks shouldContain ("// hi" to CodeTokenKind.Comment)
    }

    "kotlin: block comment" {
        val src = "/* multi\nline */ val x = 1"
        val toks = CodeHighlighter.tokenize("kotlin", src).kindsAt(src)
        toks shouldContain ("/* multi\nline */" to CodeTokenKind.Comment)
    }

    "python: comment and function call" {
        val src = "# comment\nprint(\"hi\")"
        val toks = CodeHighlighter.tokenize("python", src).kindsAt(src)
        toks shouldContain ("# comment" to CodeTokenKind.Comment)
        toks shouldContain ("print" to CodeTokenKind.Function)
        toks shouldContain ("\"hi\"" to CodeTokenKind.String)
    }

    "python: literals True/False/None" {
        val src = "x = True or False or None"
        val toks = CodeHighlighter.tokenize("python", src).kindsAt(src)
        toks shouldContain ("True" to CodeTokenKind.Literal)
        toks shouldContain ("False" to CodeTokenKind.Literal)
        toks shouldContain ("None" to CodeTokenKind.Literal)
    }

    "json: string keys and values, number" {
        val src = """{"key": "val", "n": 42}"""
        val toks = CodeHighlighter.tokenize("json", src).kindsAt(src)
        toks shouldContain ("\"key\"" to CodeTokenKind.String)
        toks shouldContain ("\"val\"" to CodeTokenKind.String)
        toks shouldContain ("42" to CodeTokenKind.Number)
    }

    "json: true/false/null literals" {
        val src = """{"a": true, "b": false, "c": null}"""
        val toks = CodeHighlighter.tokenize("json", src).kindsAt(src)
        toks shouldContain ("true" to CodeTokenKind.Literal)
        toks shouldContain ("false" to CodeTokenKind.Literal)
        toks shouldContain ("null" to CodeTokenKind.Literal)
    }

    "js alias resolves to javascript" {
        val src = "const x = 1"
        val ts = CodeHighlighter.tokenize("js", src).kindsAt(src)
        ts shouldContain ("const" to CodeTokenKind.Keyword)
    }

    "ts alias resolves to typescript" {
        val src = "let x: number = 1"
        val ts = CodeHighlighter.tokenize("ts", src).kindsAt(src)
        ts shouldContain ("let" to CodeTokenKind.Keyword)
        ts shouldContain ("number" to CodeTokenKind.Type)
    }

    "bash: line comment with #" {
        val src = "# hi\necho hi"
        val toks = CodeHighlighter.tokenize("bash", src).kindsAt(src)
        toks shouldContain ("# hi" to CodeTokenKind.Comment)
    }

    "sql: keywords are case-insensitive" {
        val src = "SELECT * FROM t WHERE x = 1"
        val toks = CodeHighlighter.tokenize("sql", src).kindsAt(src)
        toks shouldContain ("SELECT" to CodeTokenKind.Keyword)
        toks shouldContain ("FROM" to CodeTokenKind.Keyword)
        toks shouldContain ("WHERE" to CodeTokenKind.Keyword)
        toks shouldContain ("1" to CodeTokenKind.Number)
    }

    "tokens are ordered and non-overlapping" {
        val src = "fun main() { val x = 1 }"
        val toks = CodeHighlighter.tokenize("kotlin", src)
        for (i in 1 until toks.size) {
            (toks[i].start >= toks[i - 1].end) shouldBe true
        }
    }
})
