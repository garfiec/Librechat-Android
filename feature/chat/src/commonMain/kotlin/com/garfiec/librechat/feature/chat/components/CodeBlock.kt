package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.copyToClipboard
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    // In-conversation search: overlays yellow/orange match spans on the syntax
    // highlighting; when this block owns the focused occurrence, reports its
    // rect (in the code Text's coordinates) for precise scroll positioning.
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedMatchPosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
) {
    var showCopied by remember { mutableStateOf(false) }

    LaunchedEffect(showCopied) {
        if (showCopied) {
            delay(3000L)
            showCopied = false
        }
    }

    val languageLabel = language?.lowercase() ?: "code"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics {
                contentDescription = "$languageLabel code block"
            },
    ) {
        // Language header with copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.lowercase() ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    copyToClipboard(code, "Code")
                    showCopied = true
                },
            ) {
                AnimatedContent(
                    targetState = showCopied,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "copy_button",
                ) { copied ->
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = stringResource(if (copied) Res.string.cd_copied else Res.string.cd_copy_code),
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        // Code content with syntax highlighting
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            val isDarkTheme = isSystemInDarkTheme()
            val highlightedCode = remember(code, language, searchQuery, searchFocusedOccurrence, isDarkTheme) {
                val syntax = highlightSyntax(code, language?.lowercase())
                if (searchQuery.isNullOrBlank()) {
                    syntax
                } else {
                    addSearchSpans(syntax, searchQuery, searchFocusedOccurrence, isDarkTheme)
                }
            }
            val focusedRange = remember(code, searchQuery, searchFocusedOccurrence) {
                if (searchQuery.isNullOrBlank()) {
                    null
                } else {
                    findOccurrenceRange(code, searchQuery, searchFocusedOccurrence)
                }
            }
            var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = highlightedCode,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
                onTextLayout = { layoutResult = it },
                modifier = Modifier.reportFocusedMatchPosition(layoutResult, focusedRange, onFocusedMatchPosition),
            )
        }
    }
}

// --- Syntax highlighting engine ---

/**
 * Simple regex-based syntax highlighting. Colors keywords, strings, comments,
 * and numbers for common languages. Uses a priority-ordered token list so that
 * earlier matches (e.g. comments) take precedence over later ones (e.g. keywords).
 */
private fun highlightSyntax(code: String, language: String?): AnnotatedString {
    val tokenRules = getTokenRules(language) ?: return AnnotatedString(code)
    return buildAnnotatedString {
        // Collect all matches, sort by position, resolve overlaps
        val tokens = mutableListOf<SyntaxToken>()
        for (rule in tokenRules) {
            rule.regex.findAll(code).forEach { match ->
                val range = if (rule.captureGroup > 0 && match.groups[rule.captureGroup] != null) {
                    match.groups[rule.captureGroup]!!.range
                } else {
                    match.range
                }
                tokens.add(
                    SyntaxToken(
                        start = range.first,
                        end = range.last + 1,
                        style = rule.style,
                        priority = rule.priority,
                    ),
                )
            }
        }

        // Sort by start position; for overlaps, prefer higher priority (lower number)
        tokens.sortWith(compareBy({ it.start }, { it.priority }))

        // Remove overlapping tokens (first one wins)
        val resolved = mutableListOf<SyntaxToken>()
        var lastEnd = 0
        for (token in tokens) {
            if (token.start >= lastEnd) {
                resolved.add(token)
                lastEnd = token.end
            }
        }

        // Build annotated string
        var pos = 0
        for (token in resolved) {
            if (token.start > pos) {
                append(code.substring(pos, token.start))
            }
            withStyle(token.style) {
                append(code.substring(token.start, token.end))
            }
            pos = token.end
        }
        if (pos < code.length) {
            append(code.substring(pos))
        }
    }
}

private data class SyntaxToken(
    val start: Int,
    val end: Int,
    val style: SpanStyle,
    val priority: Int,
)

private data class TokenRule(
    val regex: Regex,
    val style: SpanStyle,
    val priority: Int,
    val captureGroup: Int = 0,
)

// Color palette for syntax highlighting (works on both light and dark themes)
// These are chosen to be visible on dark backgrounds (surfaceContainerHighest)
// and also acceptable on light backgrounds.
private val commentStyle = SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)
private val stringStyle = SpanStyle(color = Color(0xFFCE9178))
private val keywordStyle = SpanStyle(color = Color(0xFF569CD6))
private val numberStyle = SpanStyle(color = Color(0xFFB5CEA8))
private val typeStyle = SpanStyle(color = Color(0xFF4EC9B0))
private val functionStyle = SpanStyle(color = Color(0xFFDCDCAA))
private val annotationStyle = SpanStyle(color = Color(0xFFD7BA7D))
private val constantStyle = SpanStyle(color = Color(0xFF4FC1FF))

// Cache token rules per language to avoid recreating Regex objects on every code block render.
private val tokenRulesCache = mutableMapOf<String, List<TokenRule>>()

private fun getTokenRules(language: String?): List<TokenRule>? {
    if (language == null || language == "markdown" || language == "md") return null
    val key = when (language) {
        "kt" -> "kotlin"
        "py" -> "python"
        "js" -> "javascript"
        "ts" -> "typescript"
        "sh", "shell", "zsh" -> "bash"
        "html" -> "xml"
        "golang" -> "go"
        "rs" -> "rust"
        "cpp", "c++", "h", "hpp" -> "c"
        "rb" -> "ruby"
        "yml" -> "yaml"
        "docker" -> "dockerfile"
        else -> language
    }
    return tokenRulesCache.getOrPut(key) {
        when (key) {
            "kotlin" -> kotlinRules()
            "java" -> javaRules()
            "python" -> pythonRules()
            "javascript" -> jsRules()
            "typescript" -> tsRules()
            "json" -> jsonRules()
            "bash" -> bashRules()
            "xml" -> xmlRules()
            "css" -> cssRules()
            "sql" -> sqlRules()
            "go" -> goRules()
            "rust" -> rustRules()
            "c" -> cRules()
            "swift" -> swiftRules()
            "ruby" -> rubyRules()
            "yaml" -> yamlRules()
            "toml" -> tomlRules()
            "dockerfile" -> dockerRules()
            else -> genericRules()
        }
    }
}

// --- Language rule sets ---

private fun kotlinRules(): List<TokenRule> {
    val keywords = "val|var|fun|class|object|interface|enum|sealed|data|annotation|companion|" +
        "if|else|when|for|while|do|return|break|continue|throw|try|catch|finally|" +
        "import|package|is|as|in|out|by|init|constructor|super|this|null|true|false|" +
        "private|protected|internal|public|open|abstract|override|final|suspend|inline|" +
        "crossinline|noinline|reified|typealias|lateinit|const|vararg|operator|infix|tailrec"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("\"\"\"[\\s\\S]*?\"\"\""), stringStyle, 1),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)'"), stringStyle, 1),
        TokenRule(Regex("@\\w+"), annotationStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*[fFLl]?\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun javaRules(): List<TokenRule> {
    val keywords = "public|private|protected|static|final|abstract|synchronized|volatile|" +
        "transient|native|strictfp|class|interface|enum|extends|implements|" +
        "if|else|for|while|do|switch|case|default|break|continue|return|throw|" +
        "try|catch|finally|new|instanceof|import|package|this|super|void|" +
        "boolean|byte|char|short|int|long|float|double|null|true|false"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)'"), stringStyle, 1),
        TokenRule(Regex("@\\w+"), annotationStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*[fFdDlL]?\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun pythonRules(): List<TokenRule> {
    val keywords = "def|class|if|elif|else|for|while|return|yield|break|continue|pass|" +
        "import|from|as|try|except|finally|raise|with|lambda|and|or|not|is|in|" +
        "True|False|None|del|global|nonlocal|assert|async|await"
    return listOf(
        TokenRule(Regex("#.*"), commentStyle, 0),
        TokenRule(Regex("\"\"\"[\\s\\S]*?\"\"\""), stringStyle, 1),
        TokenRule(Regex("'''[\\s\\S]*?'''"), stringStyle, 1),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("@\\w+"), annotationStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*[jJ]?\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun jsRules(): List<TokenRule> {
    val keywords = "var|let|const|function|return|if|else|for|while|do|switch|case|default|" +
        "break|continue|throw|try|catch|finally|new|delete|typeof|instanceof|in|of|" +
        "class|extends|super|import|export|from|as|default|async|await|yield|" +
        "this|null|undefined|true|false|void|static|get|set"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("`[\\s\\S]*?`"), stringStyle, 1),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun tsRules(): List<TokenRule> {
    val keywords = "var|let|const|function|return|if|else|for|while|do|switch|case|default|" +
        "break|continue|throw|try|catch|finally|new|delete|typeof|instanceof|in|of|" +
        "class|extends|super|import|export|from|as|default|async|await|yield|" +
        "this|null|undefined|true|false|void|static|get|set|" +
        "type|interface|enum|implements|namespace|declare|abstract|readonly|keyof|" +
        "infer|never|unknown|any|string|number|boolean|symbol|bigint"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("`[\\s\\S]*?`"), stringStyle, 1),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("@\\w+"), annotationStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun jsonRules(): List<TokenRule> {
    return listOf(
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\"\\s*:"), constantStyle, 1), // keys
        TokenRule(Regex(":\\s*\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 2), // string values
        TokenRule(Regex("\\b(true|false|null)\\b"), keywordStyle, 3),
        TokenRule(Regex("-?\\b\\d+\\.?\\d*([eE][+-]?\\d+)?\\b"), numberStyle, 4),
    )
}

private fun bashRules(): List<TokenRule> {
    val keywords = "if|then|else|elif|fi|for|while|do|done|case|esac|function|return|" +
        "in|select|until|local|export|source|alias|unalias|set|unset|declare|" +
        "readonly|shift|exit|echo|printf|read|cd|pwd|ls|cp|mv|rm|mkdir|chmod|" +
        "chown|grep|sed|awk|find|sort|cat|head|tail|wc|cut|tr|xargs"
    return listOf(
        TokenRule(Regex("#.*"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'[^']*'"), stringStyle, 1),
        TokenRule(Regex("\\$\\{?\\w+\\}?"), constantStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b\\d+\\b"), numberStyle, 5),
    )
}

private fun xmlRules(): List<TokenRule> {
    return listOf(
        TokenRule(Regex("<!--[\\s\\S]*?-->"), commentStyle, 0),
        TokenRule(Regex("\"[^\"]*\""), stringStyle, 1),
        TokenRule(Regex("'[^']*'"), stringStyle, 1),
        TokenRule(Regex("</?\\w[\\w.-]*"), keywordStyle, 2),
        TokenRule(Regex("/?>"), keywordStyle, 2),
        TokenRule(Regex("\\b\\w+(?==)"), typeStyle, 3),
    )
}

private fun cssRules(): List<TokenRule> {
    return listOf(
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("#[0-9a-fA-F]{3,8}\\b"), numberStyle, 2),
        TokenRule(Regex("\\b\\d+\\.?\\d*(px|em|rem|vh|vw|%|s|ms)?\\b"), numberStyle, 2),
        TokenRule(Regex("[.#][\\w-]+"), typeStyle, 3),
        TokenRule(Regex("@\\w+"), annotationStyle, 3),
        TokenRule(Regex("[\\w-]+(?=\\s*:)"), constantStyle, 4),
    )
}

private fun sqlRules(): List<TokenRule> {
    val keywords = "SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|DROP|" +
        "ALTER|TABLE|INDEX|VIEW|JOIN|INNER|LEFT|RIGHT|OUTER|ON|AND|OR|NOT|IN|" +
        "BETWEEN|LIKE|IS|NULL|AS|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|UNION|ALL|" +
        "DISTINCT|EXISTS|CASE|WHEN|THEN|ELSE|END|BEGIN|COMMIT|ROLLBACK|PRIMARY|" +
        "KEY|FOREIGN|REFERENCES|CONSTRAINT|DEFAULT|CHECK|UNIQUE|AUTO_INCREMENT"
    return listOf(
        TokenRule(Regex("--.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("\\b($keywords)\\b", RegexOption.IGNORE_CASE), keywordStyle, 3),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
    )
}

private fun goRules(): List<TokenRule> {
    val keywords = "break|case|chan|const|continue|default|defer|else|fallthrough|for|func|" +
        "go|goto|if|import|interface|map|package|range|return|select|struct|switch|" +
        "type|var|true|false|nil|iota"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("`[\\s\\S]*?`"), stringStyle, 1),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun rustRules(): List<TokenRule> {
    val keywords = "as|async|await|break|const|continue|crate|dyn|else|enum|extern|false|" +
        "fn|for|if|impl|in|let|loop|match|mod|move|mut|pub|ref|return|self|Self|" +
        "static|struct|super|trait|true|type|unsafe|use|where|while|yield"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)'"), stringStyle, 1),
        TokenRule(Regex("#\\[.*?]"), annotationStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*(_\\d+)*([fui]\\d+)?\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*[!(]"), functionStyle, 6, captureGroup = 1),
    )
}

private fun cRules(): List<TokenRule> {
    val keywords = "auto|break|case|char|const|continue|default|do|double|else|enum|extern|" +
        "float|for|goto|if|int|long|register|return|short|signed|sizeof|static|" +
        "struct|switch|typedef|union|unsigned|void|volatile|while|" +
        "class|namespace|template|typename|virtual|override|public|private|protected|" +
        "try|catch|throw|new|delete|nullptr|true|false|this|using|include|define|" +
        "ifdef|ifndef|endif|pragma"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("#\\s*\\w+"), annotationStyle, 1),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 2),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*[fFlLuU]*\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun swiftRules(): List<TokenRule> {
    val keywords = "class|struct|enum|protocol|extension|func|var|let|if|else|guard|switch|" +
        "case|default|for|while|repeat|return|break|continue|throw|throws|rethrows|" +
        "try|catch|do|import|as|is|in|self|Self|super|init|deinit|nil|true|false|" +
        "public|private|internal|fileprivate|open|static|override|mutating|lazy|weak|" +
        "unowned|optional|required|convenience|final|where|typealias|associatedtype|" +
        "async|await|actor"
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("@\\w+"), annotationStyle, 2),
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
        TokenRule(Regex("\\b(\\w+)\\s*\\("), functionStyle, 6, captureGroup = 1),
    )
}

private fun rubyRules(): List<TokenRule> {
    val keywords = "def|class|module|if|elsif|else|unless|case|when|while|until|for|do|" +
        "begin|end|rescue|ensure|raise|return|yield|break|next|redo|retry|" +
        "require|include|extend|attr_accessor|attr_reader|attr_writer|" +
        "self|super|nil|true|false|and|or|not|in|then|puts|print"
    return listOf(
        TokenRule(Regex("#.*"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex(":\\w+"), constantStyle, 2), // symbols
        TokenRule(Regex("\\b($keywords)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
    )
}

private fun yamlRules(): List<TokenRule> {
    return listOf(
        TokenRule(Regex("#.*"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("\\b(true|false|yes|no|null|on|off)\\b", RegexOption.IGNORE_CASE), keywordStyle, 3),
        TokenRule(Regex("^\\s*[\\w.-]+(?=\\s*:)", RegexOption.MULTILINE), constantStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
    )
}

private fun tomlRules(): List<TokenRule> {
    return listOf(
        TokenRule(Regex("#.*"), commentStyle, 0),
        TokenRule(Regex("\"\"\"[\\s\\S]*?\"\"\""), stringStyle, 1),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'[^']*'"), stringStyle, 1),
        TokenRule(Regex("\\b(true|false)\\b"), keywordStyle, 3),
        TokenRule(Regex("\\[+[\\w.-]+]+"), typeStyle, 4),
        TokenRule(Regex("^\\s*[\\w.-]+(?=\\s*=)", RegexOption.MULTILINE), constantStyle, 4),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
    )
}

private fun dockerRules(): List<TokenRule> {
    val keywords = "FROM|RUN|CMD|LABEL|MAINTAINER|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|" +
        "USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL|AS"
    return listOf(
        TokenRule(Regex("#.*"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'[^']*'"), stringStyle, 1),
        TokenRule(Regex("\\b($keywords)\\b", RegexOption.IGNORE_CASE), keywordStyle, 3),
        TokenRule(Regex("\\$\\{?\\w+\\}?"), constantStyle, 4),
    )
}

private fun genericRules(): List<TokenRule> {
    // Minimal highlighting for unknown languages: strings, comments, numbers
    return listOf(
        TokenRule(Regex("//.*"), commentStyle, 0),
        TokenRule(Regex("#.*"), commentStyle, 0),
        TokenRule(Regex("/\\*[\\s\\S]*?\\*/"), commentStyle, 0),
        TokenRule(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), stringStyle, 1),
        TokenRule(Regex("'(?:[^'\\\\]|\\\\.)*'"), stringStyle, 1),
        TokenRule(Regex("\\b\\d+\\.?\\d*\\b"), numberStyle, 5),
    )
}
