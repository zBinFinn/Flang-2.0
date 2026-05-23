package com.zbinfinn.flangidea

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class FlangSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        FlangSyntaxHighlighter()
}

class FlangSyntaxHighlighter : SyntaxHighlighter {
    override fun getHighlightingLexer(): Lexer = FlangLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        when (tokenType) {
            FlangTokenTypes.KEYWORD,
            FlangTokenTypes.INLINE,
            FlangTokenTypes.PACKAGE,
            FlangTokenTypes.IMPORT,
            FlangTokenTypes.PRIVATE,
            FlangTokenTypes.FN,
            FlangTokenTypes.EMIT,
            FlangTokenTypes.VAL,
            FlangTokenTypes.VAR,
            FlangTokenTypes.TRUE,
            FlangTokenTypes.FALSE,
            FlangTokenTypes.RETURN,
            FlangTokenTypes.IF,
            FlangTokenTypes.ELSE,
            FlangTokenTypes.FOR,
            FlangTokenTypes.IN,
            FlangTokenTypes.WHILE,
            FlangTokenTypes.WHEN,
            FlangTokenTypes.AS,
            FlangTokenTypes.STRUCT,
            FlangTokenTypes.INTERFACE,
            FlangTokenTypes.ENUM,
            FlangTokenTypes.IMPL,
            FlangTokenTypes.OBJECT,
            FlangTokenTypes.ARGS,
            FlangTokenTypes.TAGS -> KEYWORD_KEYS
            FlangTokenTypes.IDENTIFIER -> IDENTIFIER_KEYS
            FlangTokenTypes.NUMBER -> NUMBER_KEYS
            FlangTokenTypes.STRING, FlangTokenTypes.STYLED_STRING -> STRING_KEYS
            FlangTokenTypes.LINE_COMMENT, FlangTokenTypes.BLOCK_COMMENT -> COMMENT_KEYS
            FlangTokenTypes.OPERATOR,
            FlangTokenTypes.ARROW,
            FlangTokenTypes.EQ,
            FlangTokenTypes.EQ_EQ,
            FlangTokenTypes.NOT_EQ,
            FlangTokenTypes.LT_EQ,
            FlangTokenTypes.GT_EQ,
            FlangTokenTypes.AND_AND,
            FlangTokenTypes.OR_OR,
            FlangTokenTypes.DOT_DOT,
            FlangTokenTypes.AMP,
            FlangTokenTypes.PLUS,
            FlangTokenTypes.MINUS,
            FlangTokenTypes.STAR,
            FlangTokenTypes.SLASH,
            FlangTokenTypes.PERCENT,
            FlangTokenTypes.DOT,
            FlangTokenTypes.LT,
            FlangTokenTypes.GT,
            FlangTokenTypes.DOLLAR -> OPERATOR_KEYS
            FlangTokenTypes.PUNCTUATION,
            FlangTokenTypes.COMMA,
            FlangTokenTypes.COLON,
            FlangTokenTypes.SEMI -> PUNCTUATION_KEYS
            FlangTokenTypes.LBRACE,
            FlangTokenTypes.RBRACE,
            FlangTokenTypes.LPAREN,
            FlangTokenTypes.RPAREN,
            FlangTokenTypes.LBRACKET,
            FlangTokenTypes.RBRACKET -> BRACES_KEYS
            FlangTokenTypes.ANNOTATION, FlangTokenTypes.AT -> ANNOTATION_KEYS
            FlangTokenTypes.ENUM_SHORTHAND -> ENUM_KEYS
            FlangTokenTypes.EMIT_CONTENT -> EMIT_KEYS
            TokenType.BAD_CHARACTER -> BAD_KEYS
            else -> TextAttributesKey.EMPTY_ARRAY
        }

    companion object {
        val KEYWORD = TextAttributesKey.createTextAttributesKey("FLANG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val IDENTIFIER = TextAttributesKey.createTextAttributesKey("FLANG_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
        val TYPE_IDENTIFIER = TextAttributesKey.createTextAttributesKey("FLANG_TYPE_IDENTIFIER", DefaultLanguageHighlighterColors.CLASS_NAME)
        val NUMBER = TextAttributesKey.createTextAttributesKey("FLANG_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val STRING = TextAttributesKey.createTextAttributesKey("FLANG_STRING", DefaultLanguageHighlighterColors.STRING)
        val COMMENT = TextAttributesKey.createTextAttributesKey("FLANG_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val OPERATOR = TextAttributesKey.createTextAttributesKey("FLANG_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val PUNCTUATION = TextAttributesKey.createTextAttributesKey("FLANG_PUNCTUATION", DefaultLanguageHighlighterColors.COMMA)
        val BRACES = TextAttributesKey.createTextAttributesKey("FLANG_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val ANNOTATION = TextAttributesKey.createTextAttributesKey("FLANG_ANNOTATION", DefaultLanguageHighlighterColors.METADATA)
        val ENUM = TextAttributesKey.createTextAttributesKey("FLANG_ENUM", DefaultLanguageHighlighterColors.STATIC_FIELD)
        val EMIT = TextAttributesKey.createTextAttributesKey("FLANG_EMIT", DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR)
        val BAD = TextAttributesKey.createTextAttributesKey("FLANG_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val STRING_KEYS = arrayOf(STRING)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val PUNCTUATION_KEYS = arrayOf(PUNCTUATION)
        private val BRACES_KEYS = arrayOf(BRACES)
        private val ANNOTATION_KEYS = arrayOf(ANNOTATION)
        private val ENUM_KEYS = arrayOf(ENUM)
        private val EMIT_KEYS = arrayOf(EMIT)
        private val BAD_KEYS = arrayOf(BAD)
    }
}
