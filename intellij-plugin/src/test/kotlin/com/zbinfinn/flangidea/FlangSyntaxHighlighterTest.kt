package com.zbinfinn.flangidea

import com.intellij.psi.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlangSyntaxHighlighterTest {
    @Test
    fun tokenizesCoreSyntax() {
        val lexer = FlangLexerAdapter()
        lexer.start("""@Event fn Main() { val styled = s"<green>Hi"; while (1 <= 2) {} // hi${'\n'}gval("Name", .Selection); }""")
        val tokenTypes = buildList {
            while (lexer.tokenType != null) {
                add(lexer.tokenType)
                lexer.advance()
            }
        }

        assertTrue(FlangTokenTypes.AT in tokenTypes)
        assertTrue(FlangTokenTypes.FN in tokenTypes)
        assertTrue(FlangTokenTypes.VAL in tokenTypes)
        assertTrue(FlangTokenTypes.STYLED_STRING in tokenTypes)
        assertTrue(FlangTokenTypes.LINE_COMMENT in tokenTypes)
        assertTrue(FlangTokenTypes.ENUM_SHORTHAND in tokenTypes)
        assertTrue(TokenType.BAD_CHARACTER !in tokenTypes)
    }

    @Test
    fun tokenizesOpeningAndClosingDelimitersSeparately() {
        val lexer = FlangLexerAdapter()
        lexer.start("{}()[]")
        val tokenTypes = buildList {
            while (lexer.tokenType != null) {
                add(lexer.tokenType)
                lexer.advance()
            }
        }

        assertEquals(
            listOf(
                FlangTokenTypes.LBRACE,
                FlangTokenTypes.RBRACE,
                FlangTokenTypes.LPAREN,
                FlangTokenTypes.RPAREN,
                FlangTokenTypes.LBRACKET,
                FlangTokenTypes.RBRACKET,
            ),
            tokenTypes,
        )
    }

    @Test
    fun highlightsEnumShorthandSeparately() {
        val highlighter = FlangSyntaxHighlighter()

        assertEquals(
            FlangSyntaxHighlighter.ENUM,
            highlighter.getTokenHighlights(FlangTokenTypes.ENUM_SHORTHAND).single(),
        )
    }

    @Test
    fun highlightsAllDelimitersAsBraces() {
        val highlighter = FlangSyntaxHighlighter()

        listOf(
            FlangTokenTypes.LBRACE,
            FlangTokenTypes.RBRACE,
            FlangTokenTypes.LPAREN,
            FlangTokenTypes.RPAREN,
            FlangTokenTypes.LBRACKET,
            FlangTokenTypes.RBRACKET,
        ).forEach { tokenType ->
            assertEquals(FlangSyntaxHighlighter.BRACES, highlighter.getTokenHighlights(tokenType).single())
        }
    }
}
