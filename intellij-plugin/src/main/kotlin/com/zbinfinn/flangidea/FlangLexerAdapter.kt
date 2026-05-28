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
                tokenType = FlangTokenTypes.AT
            }
            c == '.' && peek(1)?.let { it == '_' || it.isLetter() } == true -> scanIdentifier(FlangTokenTypes.ENUM_SHORTHAND, tokenStart + 1)
            c.isDigit() -> scanNumber()
            c == '_' || c.isLetter() -> scanIdentifier(null, tokenStart)
            c == '{' -> single(FlangTokenTypes.LBRACE)
            c == '}' -> single(FlangTokenTypes.RBRACE)
            c == '(' -> single(FlangTokenTypes.LPAREN)
            c == ')' -> single(FlangTokenTypes.RPAREN)
            c == '[' -> single(FlangTokenTypes.LBRACKET)
            c == ']' -> single(FlangTokenTypes.RBRACKET)
            c == ',' -> single(FlangTokenTypes.COMMA)
            c == ';' -> single(FlangTokenTypes.SEMI)
            c == ':' -> single(FlangTokenTypes.COLON)
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
        tokenType = forcedType ?: keywordTypes[buffer.subSequence(tokenStart, tokenEnd).toString()] ?: run {
            FlangTokenTypes.IDENTIFIER
        }
    }

    private fun scanOperator() {
        tokenEnd = tokenStart + 1
        if (tokenEnd < endOffset) {
            val two = buffer.subSequence(tokenStart, tokenEnd + 1).toString()
            val twoType = operatorTypes[two]
            if (twoType != null) {
                tokenEnd++
                tokenType = twoType
                return
            }
        }
        tokenType = operatorTypes[buffer.subSequence(tokenStart, tokenEnd).toString()] ?: FlangTokenTypes.OPERATOR
    }

    private fun single(type: IElementType) {
        tokenEnd = tokenStart + 1
        tokenType = type
    }

    private fun peek(delta: Int): Char? = buffer.getOrNull(tokenStart + delta)?.takeIf { tokenStart + delta < endOffset }

    companion object {
        private val keywordTypes = mapOf(
            "inline" to FlangTokenTypes.INLINE,
            "package" to FlangTokenTypes.PACKAGE,
            "import" to FlangTokenTypes.IMPORT,
            "private" to FlangTokenTypes.PRIVATE,
            "pc" to FlangTokenTypes.PC,
            "fn" to FlangTokenTypes.FN,
            "emit" to FlangTokenTypes.EMIT,
            "val" to FlangTokenTypes.VAL,
            "var" to FlangTokenTypes.VAR,
            "true" to FlangTokenTypes.TRUE,
            "false" to FlangTokenTypes.FALSE,
            "return" to FlangTokenTypes.RETURN,
            "start" to FlangTokenTypes.START,
            "if" to FlangTokenTypes.IF,
            "else" to FlangTokenTypes.ELSE,
            "for" to FlangTokenTypes.FOR,
            "in" to FlangTokenTypes.IN,
            "while" to FlangTokenTypes.WHILE,
            "when" to FlangTokenTypes.WHEN,
            "as" to FlangTokenTypes.AS,
            "struct" to FlangTokenTypes.STRUCT,
            "interface" to FlangTokenTypes.INTERFACE,
            "enum" to FlangTokenTypes.ENUM,
            "impl" to FlangTokenTypes.IMPL,
            "object" to FlangTokenTypes.OBJECT,
            "args" to FlangTokenTypes.ARGS,
            "tags" to FlangTokenTypes.TAGS,
        )

        private val operatorTypes = mapOf(
            "->" to FlangTokenTypes.ARROW,
            "==" to FlangTokenTypes.EQ_EQ,
            "!=" to FlangTokenTypes.NOT_EQ,
            "<=" to FlangTokenTypes.LT_EQ,
            ">=" to FlangTokenTypes.GT_EQ,
            "&&" to FlangTokenTypes.AND_AND,
            "||" to FlangTokenTypes.OR_OR,
            ".." to FlangTokenTypes.DOT_DOT,
            "=" to FlangTokenTypes.EQ,
            "&" to FlangTokenTypes.AMP,
            "+" to FlangTokenTypes.PLUS,
            "-" to FlangTokenTypes.MINUS,
            "*" to FlangTokenTypes.STAR,
            "/" to FlangTokenTypes.SLASH,
            "%" to FlangTokenTypes.PERCENT,
            "." to FlangTokenTypes.DOT,
            "<" to FlangTokenTypes.LT,
            ">" to FlangTokenTypes.GT,
            "$" to FlangTokenTypes.DOLLAR,
        )
    }
}
