package com.garfiec.librechat.feature.chat.components.artifact

/**
 * Builds an HTML page that renders Markdown content using marked (GFM) with
 * highlight.js for code syntax highlighting. Both are the copies bundled in the app,
 * referenced relative to the document base URL the platform WebView host supplies
 * (see `webAssetBaseUrl`).
 *
 * Highlighting goes through the marked-highlight extension because marked removed
 * its built-in `highlight` option in v5 — passing one to `setOptions` is accepted
 * and then silently never called.
 */
object MarkdownWebContent {

    fun buildHtml(markdownContent: String, isDarkTheme: Boolean, inline: Boolean = false): String {
        val bgColor = if (isDarkTheme) "#1C1B1F" else "#FFFBFE"
        val fgColor = if (isDarkTheme) "#E6E1E5" else "#1C1B1F"
        val codeBg = if (isDarkTheme) "#2B2930" else "#F3EDF7"
        val borderColor = if (isDarkTheme) "#48464C" else "#CAC4D0"
        val linkColor = if (isDarkTheme) "#D0BCFF" else "#6750A4"
        val hlTheme = if (isDarkTheme) "github-dark" else "github"
        val bodyPadding = if (inline) "8px" else "16px"
        val escapedContent = markdownContent
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-inline' file:; style-src 'self' 'unsafe-inline' file:; img-src data: blob: https:;">
                <link rel="stylesheet" href="highlight/$hlTheme.min.css">
                <style>
                    html, body { max-width: 100%; overflow-x: hidden; }
                    body {
                        margin: 0;
                        padding: $bodyPadding;
                        background: $bgColor;
                        color: $fgColor;
                        font-family: -apple-system, system-ui, sans-serif;
                        font-size: 15px;
                        line-height: 1.6;
                        word-wrap: break-word;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        margin-top: 1.2em;
                        margin-bottom: 0.5em;
                        font-weight: 600;
                    }
                    h1 { font-size: 1.8em; border-bottom: 1px solid $borderColor; padding-bottom: 0.3em; }
                    h2 { font-size: 1.5em; border-bottom: 1px solid $borderColor; padding-bottom: 0.3em; }
                    h3 { font-size: 1.25em; }
                    a { color: $linkColor; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    code {
                        background: $codeBg;
                        padding: 2px 6px;
                        border-radius: 4px;
                        font-size: 0.9em;
                    }
                    pre {
                        background: $codeBg;
                        padding: 12px;
                        border-radius: 8px;
                        overflow-x: auto;
                    }
                    pre code {
                        background: none;
                        padding: 0;
                    }
                    blockquote {
                        border-left: 3px solid $borderColor;
                        margin-left: 0;
                        padding-left: 16px;
                        color: ${fgColor}cc;
                    }
                    table {
                        border-collapse: collapse;
                        width: 100%;
                        margin: 1em 0;
                    }
                    th, td {
                        border: 1px solid $borderColor;
                        padding: 8px 12px;
                        text-align: left;
                    }
                    th {
                        background: $codeBg;
                        font-weight: 600;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                        border-radius: 8px;
                    }
                    hr {
                        border: none;
                        border-top: 1px solid $borderColor;
                        margin: 1.5em 0;
                    }
                    ul, ol {
                        padding-left: 1.5em;
                    }
                    li {
                        margin: 0.25em 0;
                    }
                </style>
            </head>
            <body>
                <div id="content"></div>
                <script src="marked/marked.umd.js"></script>
                <script src="marked-highlight/marked-highlight.umd.js"></script>
                <script src="highlight/highlight.min.js"></script>
                <script>
                    // langPrefix must stay 'hljs language-': the theme stylesheet keys off .hljs,
                    // so dropping it highlights the tokens and leaves the block unstyled.
                    marked.use(markedHighlight.markedHighlight({
                        langPrefix: 'hljs language-',
                        highlight: function(code, lang) {
                            if (lang && hljs.getLanguage(lang)) {
                                try { return hljs.highlight(code, { language: lang }).value; }
                                catch (e) {}
                            }
                            try { return hljs.highlightAuto(code).value; }
                            catch (e) { return code; }
                        }
                    }));
                    marked.setOptions({ gfm: true, breaks: true });
                    const md = `$escapedContent`;
                    document.getElementById('content').innerHTML = marked.parse(md);
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
