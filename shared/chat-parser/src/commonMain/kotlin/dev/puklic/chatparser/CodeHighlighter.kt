package dev.puklic.chatparser

/**
 * Token kinds emitted by [CodeHighlighter]. Kinds map to colour roles in the
 * Compose renderer (see `LocalCodeColors` in `shared:compose-ui`).
 */
public enum class CodeTokenKind {
    Keyword,
    Type,
    String,
    Number,
    Comment,
    Function,
    Punctuation,
    Literal,
    Plain,
}

/**
 * A single highlighted token spanning [start, end) over the source string.
 * Tokens emitted by [CodeHighlighter.tokenize] are ordered by `start` and
 * non-overlapping; gaps between tokens are implicit plain text.
 */
public data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

/**
 * Per-language tokeniser configuration. Generic enough to cover most C-family
 * + script languages with a single shared scanner.
 */
internal data class LanguageSpec(
    val keywords: Set<String>,
    val types: Set<String> = emptySet(),
    val literals: Set<String> = emptySet(),
    val keywordsCaseSensitive: Boolean = true,
    val lineComment: String? = "//",
    val blockCommentStart: String? = "/*",
    val blockCommentEnd: String? = "*/",
    val stringDelimiters: Set<Char> = setOf('"'),
    val supportsBacktickStrings: Boolean = false,
)

/**
 * Minimum-complexity regex/spec-driven syntax highlighter for fenced code blocks.
 *
 * Scope: keyword/type/string/number/comment/function/literal token classification
 * for the 15 most common languages users see in Discord. Unknown languages return
 * an empty token list — the renderer then displays plain monospace text.
 */
public object CodeHighlighter {

    /**
     * Returns a list of [CodeToken]s for [source] under the grammar of [language].
     * Returns an empty list when [language] is null or unrecognised.
     *
     * Tokens are ordered by `start` and non-overlapping. Source ranges not covered
     * by any token are implicitly [CodeTokenKind.Plain].
     */
    @Suppress("ReturnCount")
    public fun tokenize(language: String?, source: String): List<CodeToken> {
        if (language == null || source.isEmpty()) return emptyList()
        val spec = LANGUAGES[language.lowercase()] ?: return emptyList()
        return Scanner(source, spec).scan()
    }
}

private class Scanner(private val src: String, private val spec: LanguageSpec) {
    private val tokens = mutableListOf<CodeToken>()
    private var i = 0

    fun scan(): List<CodeToken> {
        while (i < src.length) {
            when {
                consumeBlockComment() -> {}
                consumeLineComment() -> {}
                consumeString() -> {}
                consumeNumber() -> {}
                src[i].isLetter() || src[i] == '_' -> consumeWord()
                else -> i++ // skip punctuation / whitespace silently (Plain)
            }
        }
        return tokens
    }

    private fun consumeBlockComment(): Boolean {
        val start = spec.blockCommentStart ?: return false
        val end = spec.blockCommentEnd ?: return false
        if (!src.startsWith(start, i)) return false
        val from = i
        val close = src.indexOf(end, startIndex = i + start.length)
        i = if (close < 0) src.length else close + end.length
        tokens.add(CodeToken(from, i, CodeTokenKind.Comment))
        return true
    }

    private fun consumeLineComment(): Boolean {
        val marker = spec.lineComment ?: return false
        if (!src.startsWith(marker, i)) return false
        val from = i
        var j = i + marker.length
        while (j < src.length && src[j] != '\n') j++
        i = j
        tokens.add(CodeToken(from, i, CodeTokenKind.Comment))
        return true
    }

    private fun consumeString(): Boolean {
        val ch = src[i]
        val delims = spec.stringDelimiters + if (spec.supportsBacktickStrings) setOf('`') else emptySet()
        if (ch !in delims) return false
        val from = i
        val quote = ch
        var j = i + 1
        while (j < src.length) {
            val c = src[j]
            if (c == '\\' && j + 1 < src.length) {
                j += 2
                continue
            }
            if (c == quote) {
                j++
                break
            }
            j++
        }
        i = j
        tokens.add(CodeToken(from, i, CodeTokenKind.String))
        return true
    }

    private fun consumeNumber(): Boolean {
        val ch = src[i]
        if (!ch.isDigit()) return false
        // Don't pick up numbers attached to identifiers (e.g. var2). Caller handled letters first,
        // but identifier start chars include digits only if preceded — we're at i, prev char check:
        val from = i
        var j = i
        if (src[j] == '0' && j + 1 < src.length && (src[j + 1] == 'x' || src[j + 1] == 'X')) {
            j += 2
            while (j < src.length && (src[j].isDigit() || src[j] in 'a'..'f' || src[j] in 'A'..'F')) j++
        } else {
            while (j < src.length && src[j].isDigit()) j++
            if (j < src.length && src[j] == '.' && j + 1 < src.length && src[j + 1].isDigit()) {
                j++
                while (j < src.length && src[j].isDigit()) j++
            }
        }
        i = j
        tokens.add(CodeToken(from, i, CodeTokenKind.Number))
        return true
    }

    @Suppress("ComplexCondition")
    private fun consumeWord() {
        val from = i
        var j = i
        while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
        val word = src.substring(from, j)
        i = j
        val key = if (spec.keywordsCaseSensitive) word else word.lowercase()
        val literalsKey = if (spec.keywordsCaseSensitive) word else word.lowercase()
        val isLiteral = spec.literals.any {
            if (spec.keywordsCaseSensitive) it == word else it.lowercase() == literalsKey
        }
        val isKeyword = spec.keywords.any {
            if (spec.keywordsCaseSensitive) it == word else it.lowercase() == key
        }
        val isType = spec.types.any {
            if (spec.keywordsCaseSensitive) it == word else it.lowercase() == key
        }
        val kind = when {
            isLiteral -> CodeTokenKind.Literal
            isKeyword -> CodeTokenKind.Keyword
            isType -> CodeTokenKind.Type
            isFunctionCallAhead(j) -> CodeTokenKind.Function
            else -> return // emit nothing → Plain
        }
        tokens.add(CodeToken(from, j, kind))
    }

    private fun isFunctionCallAhead(endIdx: Int): Boolean {
        var k = endIdx
        while (k < src.length && src[k] == ' ') k++
        return k < src.length && src[k] == '('
    }
}

// ----- Language specs ----------------------------------------------------

private val KOTLIN_KW = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
    "field", "file", "finally", "get", "import", "init", "param", "property", "receiver", "set",
    "setparam", "where", "actual", "abstract", "annotation", "companion", "const", "crossinline",
    "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit",
    "noinline", "open", "operator", "out", "override", "private", "protected", "public", "reified",
    "sealed", "suspend", "tailrec", "vararg",
)
private val KOTLIN_TYPES = setOf(
    "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String", "Unit", "Nothing",
    "Any", "Array", "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet",
)

private val JAVA_KW = setOf(
    "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default", "do",
    "else", "enum", "extends", "final", "finally", "for", "goto", "if", "implements", "import",
    "instanceof", "interface", "native", "new", "package", "private", "protected", "public", "return",
    "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
    "try", "volatile", "while", "yield", "var", "record",
)
private val JAVA_TYPES = setOf(
    "boolean", "byte", "char", "double", "float", "int", "long", "short", "void", "String",
    "Object", "Integer", "Long", "Boolean", "List", "Map", "Set",
)
private val JAVA_LITERALS = setOf("true", "false", "null")

private val PYTHON_KW = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
    "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
    "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "match", "case",
)
private val PYTHON_LITERALS = setOf("True", "False", "None")

private val JS_KW = setOf(
    "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
    "else", "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof",
    "new", "of", "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void",
    "while", "with", "yield", "let", "static", "async", "await",
)
private val JS_LITERALS = setOf("true", "false", "null", "undefined", "NaN", "Infinity")

private val TS_KW = JS_KW + setOf(
    "abstract", "as", "declare", "enum", "implements", "interface", "is", "keyof", "namespace",
    "private", "protected", "public", "readonly", "type", "satisfies",
)
private val TS_TYPES = setOf(
    "string", "number", "boolean", "any", "unknown", "never", "void", "object", "bigint", "symbol",
)

private val RUST_KW = setOf(
    "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false", "fn", "for",
    "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self",
    "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while",
    "async", "await", "dyn",
)
private val RUST_TYPES = setOf(
    "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize",
    "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result",
)
private val RUST_LITERALS = setOf("true", "false", "None", "Some", "Ok", "Err")

private val GO_KW = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
    "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
    "struct", "switch", "type", "var",
)
private val GO_TYPES = setOf(
    "bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int", "int8", "int16",
    "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
)
private val GO_LITERALS = setOf("true", "false", "nil", "iota")

private val C_KW = setOf(
    "auto", "break", "case", "const", "continue", "default", "do", "else", "enum", "extern", "for",
    "goto", "if", "register", "return", "signed", "sizeof", "static", "struct", "switch", "typedef",
    "union", "unsigned", "volatile", "while", "inline", "restrict",
)
private val CPP_KW = C_KW + setOf(
    "alignas", "alignof", "and", "asm", "bitand", "bitor", "catch", "class", "compl", "concept",
    "constexpr", "constinit", "consteval", "decltype", "delete", "explicit", "export", "false",
    "friend", "mutable", "namespace", "new", "noexcept", "not", "nullptr", "operator", "or",
    "private", "protected", "public", "reinterpret_cast", "requires", "static_cast", "template",
    "this", "thread_local", "throw", "true", "try", "typeid", "typename", "using", "virtual",
    "wchar_t", "xor", "dynamic_cast", "co_await", "co_return", "co_yield",
)
private val C_TYPES = setOf(
    "char", "short", "int", "long", "float", "double", "void", "bool",
    "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "size_t",
)
private val C_LITERALS = setOf("true", "false", "NULL", "nullptr")

private val BASH_KW = setOf(
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do", "done",
    "function", "in", "select", "time", "return", "exit", "break", "continue", "local", "export",
    "readonly", "declare", "typeset", "set", "unset", "shift", "trap", "eval", "exec", "source",
)

private val JSON_LITERALS = setOf("true", "false", "null")

private val YAML_LITERALS = setOf("true", "false", "null", "True", "False", "Null", "TRUE", "FALSE", "NULL", "yes", "no", "Yes", "No", "on", "off")

private val SQL_KW = setOf(
    "select", "from", "where", "and", "or", "not", "in", "is", "null", "as", "join", "left", "right",
    "inner", "outer", "full", "on", "using", "group", "by", "having", "order", "asc", "desc", "limit",
    "offset", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop",
    "alter", "add", "column", "primary", "key", "foreign", "references", "index", "unique", "view",
    "with", "case", "when", "then", "else", "end", "union", "all", "distinct", "exists", "between",
    "like", "ilike", "default", "constraint", "check", "cascade", "restrict",
)
private val SQL_TYPES = setOf(
    "int", "integer", "bigint", "smallint", "decimal", "numeric", "float", "real", "double", "text",
    "varchar", "char", "boolean", "bool", "date", "time", "timestamp", "uuid", "json", "jsonb", "bytea",
)
private val SQL_LITERALS = setOf("true", "false", "null")

private val SWIFT_KW = setOf(
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init",
    "inout", "internal", "let", "open", "operator", "private", "protocol", "public", "rethrows",
    "static", "struct", "subscript", "typealias", "var", "break", "case", "continue", "default",
    "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return", "switch",
    "where", "while", "as", "Any", "catch", "false", "is", "nil", "super", "self", "Self", "throw",
    "throws", "true", "try", "async", "await",
)
private val SWIFT_TYPES = setOf(
    "Int", "Int8", "Int16", "Int32", "Int64", "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
    "Float", "Double", "Bool", "String", "Character", "Array", "Dictionary", "Set", "Optional",
)
private val SWIFT_LITERALS = setOf("true", "false", "nil")

private val XML_SPEC = LanguageSpec(
    keywords = emptySet(),
    lineComment = null,
    blockCommentStart = "<!--",
    blockCommentEnd = "-->",
    stringDelimiters = setOf('"', '\''),
)

private val LANGUAGES: Map<String, LanguageSpec> = mapOf(
    "kotlin" to LanguageSpec(
        keywords = KOTLIN_KW,
        types = KOTLIN_TYPES,
        literals = setOf("true", "false", "null"),
        supportsBacktickStrings = false,
        stringDelimiters = setOf('"'),
    ),
    "kt" to LanguageSpec(
        keywords = KOTLIN_KW,
        types = KOTLIN_TYPES,
        literals = setOf("true", "false", "null"),
        stringDelimiters = setOf('"'),
    ),
    "java" to LanguageSpec(
        keywords = JAVA_KW,
        types = JAVA_TYPES,
        literals = JAVA_LITERALS,
        stringDelimiters = setOf('"', '\''),
    ),
    "python" to LanguageSpec(
        keywords = PYTHON_KW,
        literals = PYTHON_LITERALS,
        lineComment = "#",
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"', '\''),
    ),
    "py" to LanguageSpec(
        keywords = PYTHON_KW,
        literals = PYTHON_LITERALS,
        lineComment = "#",
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"', '\''),
    ),
    "javascript" to LanguageSpec(
        keywords = JS_KW,
        literals = JS_LITERALS,
        stringDelimiters = setOf('"', '\''),
        supportsBacktickStrings = true,
    ),
    "js" to LanguageSpec(
        keywords = JS_KW,
        literals = JS_LITERALS,
        stringDelimiters = setOf('"', '\''),
        supportsBacktickStrings = true,
    ),
    "typescript" to LanguageSpec(
        keywords = TS_KW,
        types = TS_TYPES,
        literals = JS_LITERALS,
        stringDelimiters = setOf('"', '\''),
        supportsBacktickStrings = true,
    ),
    "ts" to LanguageSpec(
        keywords = TS_KW,
        types = TS_TYPES,
        literals = JS_LITERALS,
        stringDelimiters = setOf('"', '\''),
        supportsBacktickStrings = true,
    ),
    "rust" to LanguageSpec(
        keywords = RUST_KW,
        types = RUST_TYPES,
        literals = RUST_LITERALS,
        stringDelimiters = setOf('"'),
    ),
    "rs" to LanguageSpec(
        keywords = RUST_KW,
        types = RUST_TYPES,
        literals = RUST_LITERALS,
        stringDelimiters = setOf('"'),
    ),
    "go" to LanguageSpec(
        keywords = GO_KW,
        types = GO_TYPES,
        literals = GO_LITERALS,
        stringDelimiters = setOf('"', '\''),
        supportsBacktickStrings = true,
    ),
    "c" to LanguageSpec(
        keywords = C_KW,
        types = C_TYPES,
        literals = C_LITERALS,
        stringDelimiters = setOf('"', '\''),
    ),
    "cpp" to LanguageSpec(
        keywords = CPP_KW,
        types = C_TYPES,
        literals = C_LITERALS,
        stringDelimiters = setOf('"', '\''),
    ),
    "c++" to LanguageSpec(
        keywords = CPP_KW,
        types = C_TYPES,
        literals = C_LITERALS,
        stringDelimiters = setOf('"', '\''),
    ),
    "bash" to LanguageSpec(
        keywords = BASH_KW,
        lineComment = "#",
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"', '\''),
    ),
    "sh" to LanguageSpec(
        keywords = BASH_KW,
        lineComment = "#",
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"', '\''),
    ),
    "shell" to LanguageSpec(
        keywords = BASH_KW,
        lineComment = "#",
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"', '\''),
    ),
    "json" to LanguageSpec(
        keywords = emptySet(),
        literals = JSON_LITERALS,
        lineComment = null,
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"'),
    ),
    "yaml" to LanguageSpec(
        keywords = emptySet(),
        literals = YAML_LITERALS,
        lineComment = "#",
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"', '\''),
    ),
    "yml" to LanguageSpec(
        keywords = emptySet(),
        literals = YAML_LITERALS,
        lineComment = "#",
        blockCommentStart = null,
        blockCommentEnd = null,
        stringDelimiters = setOf('"', '\''),
    ),
    "xml" to XML_SPEC,
    "html" to XML_SPEC,
    "sql" to LanguageSpec(
        keywords = SQL_KW,
        types = SQL_TYPES,
        literals = SQL_LITERALS,
        keywordsCaseSensitive = false,
        lineComment = "--",
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        stringDelimiters = setOf('"', '\''),
    ),
    "swift" to LanguageSpec(
        keywords = SWIFT_KW,
        types = SWIFT_TYPES,
        literals = SWIFT_LITERALS,
        stringDelimiters = setOf('"'),
    ),
)
