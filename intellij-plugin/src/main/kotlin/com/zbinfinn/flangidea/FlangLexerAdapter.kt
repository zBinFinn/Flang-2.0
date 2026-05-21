package com.zbinfinn.flangidea

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class FlangLexerAdapter : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.tokenStart = startOffset
        locateToken()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        tokenStart = tokenEnd
        locateToken()
    }

    private fun locateToken() {
        if (tokenStart >= endOffset) {
            tokenType = null
            tokenEnd = endOffset
            return
        }

        val c = buffer[tokenStart]
        when {
            c.isWhitespace() -> scanWhitespace()
            c == '/' && peek(1) == '/' -> scanLineComment()
            c == '/' && peek(1) == '*' -> scanBlockComment()
            c == 's' && peek(1) == '"' -> scanString(FlangTokenTypes.STYLED_STRING, tokenStart + 2)
            c == '"' -> scanString(FlangTokenTypes.STRING, tokenStart + 1)
            c == '`' -> scanEmitContent()
            c == '@' -> {
                tokenEnd = tokenStart + 1
                tokenType = FlangTokenTypes.ANNOTATION
            }
            c == '.' && peek(1)?.let { it == '_' || it.isLetter() } == true -> scanIdentifier(FlangTokenTypes.ENUM_SHORTHAND, tokenStart + 1)
            c.isDigit() -> scanNumber()
            c == '_' || c.isLetter() -> scanIdentifier(null, tokenStart)
            c in "{}" -> single(FlangTokenTypes.BRACE)
            c in "()" -> single(FlangTokenTypes.PAREN)
            c in "[]" -> single(FlangTokenTypes.BRACKET)
            c in ",;:" -> single(FlangTokenTypes.PUNCTUATION)
            c in ".=+-*/%<>!&|$" -> scanOperator()
            else -> single(TokenType.BAD_CHARACTER)
        }
    }

    private fun scanWhitespace() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset && buffer[tokenEnd].isWhitespace()) tokenEnd++
        tokenType = TokenType.WHITE_SPACE
    }

    private fun scanLineComment() {
        tokenEnd = tokenStart + 2
        while (tokenEnd < endOffset && buffer[tokenEnd] !in "\r\n") tokenEnd++
        tokenType = FlangTokenTypes.LINE_COMMENT
    }

    private fun scanBlockComment() {
        tokenEnd = tokenStart + 2
        while (tokenEnd + 1 < endOffset && !(buffer[tokenEnd] == '*' && buffer[tokenEnd + 1] == '/')) tokenEnd++
        tokenEnd = (tokenEnd + 2).coerceAtMost(endOffset)
        tokenType = FlangTokenTypes.BLOCK_COMMENT
    }

    private fun scanString(type: IElementType, contentStart: Int) {
        tokenEnd = contentStart
        var escaping = false
        while (tokenEnd < endOffset) {
            val ch = buffer[tokenEnd++]
            if (escaping) {
                escaping = false
            } else if (ch == '\\') {
                escaping = true
            } else if (ch == '"') {
                break
            }
        }
        tokenType = type
    }

    private fun scanEmitContent() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset && buffer[tokenEnd] != '`') tokenEnd++
        if (tokenEnd < endOffset) tokenEnd++
        tokenType = FlangTokenTypes.EMIT_CONTENT
    }

    private fun scanNumber() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset && buffer[tokenEnd].isDigit()) tokenEnd++
        tokenType = FlangTokenTypes.NUMBER
    }

    private fun scanIdentifier(forcedType: IElementType?, from: Int) {
        tokenEnd = from + 1
        while (tokenEnd < endOffset && buffer[tokenEnd].let { it == '_' || it.isLetterOrDigit() }) tokenEnd++
        tokenType = forcedType ?: if (buffer.subSequence(tokenStart, tokenEnd).toString() in keywords) {
            FlangTokenTypes.KEYWORD
        } else {
            FlangTokenTypes.IDENTIFIER
        }
    }

    private fun scanOperator() {
        tokenEnd = tokenStart + 1
        if (tokenEnd < endOffset) {
            val two = buffer.subSequence(tokenStart, tokenEnd + 1).toString()
            if (two in setOf("->", "==", "!=", "<=", ">=", "&&", "||", "..")) tokenEnd++
        }
        tokenType = FlangTokenTypes.OPERATOR
    }

    private fun single(type: IElementType) {
        tokenEnd = tokenStart + 1
        tokenType = type
    }

    private fun peek(delta: Int): Char? = buffer.getOrNull(tokenStart + delta)?.takeIf { tokenStart + delta < endOffset }

    companion object {
        private val keywords = setOf(
            "inline", "package", "import", "private", "fn", "emit", "val", "var", "true", "false",
            "return", "if", "else", "for", "in", "while", "when", "struct", "interface", "default", "enum", "impl", "object", "args", "tags",
        )
    }
}
