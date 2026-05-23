package com.zbinfinn.flangidea

import com.intellij.lang.ASTNode
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

private val FILE = IFileElementType(FlangLanguage)

class FlangParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = FlangLexerAdapter()

    override fun createParser(project: Project?): PsiParser =
        PsiParser { root, builder ->
            FlangPsiBuilderParser(builder).parse(root)
        }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = TokenSet.create(FlangTokenTypes.LINE_COMMENT, FlangTokenTypes.BLOCK_COMMENT)

    override fun getStringLiteralElements(): TokenSet = TokenSet.create(FlangTokenTypes.STRING, FlangTokenTypes.STYLED_STRING)

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = object : PsiFileBase(viewProvider, FlangLanguage) {
        override fun getFileType() = FlangFileType.INSTANCE
        override fun toString(): String = "Flang File"
    }
}

private class FlangPsiBuilderParser(private val builder: PsiBuilder) {
    fun parse(root: IElementType): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            skipTrivia()
            when (builder.tokenType) {
                FlangTokenTypes.PACKAGE -> parseLineDecl(FlangElementTypes.PACKAGE_DECL)
                FlangTokenTypes.IMPORT -> parseLineDecl(FlangElementTypes.IMPORT_DECL)
                FlangTokenTypes.AT, FlangTokenTypes.PRIVATE, FlangTokenTypes.INLINE, FlangTokenTypes.FN -> parseTopLevel(FlangElementTypes.FUNCTION_DECL)
                FlangTokenTypes.STRUCT -> parseTopLevel(FlangElementTypes.STRUCT_DECL)
                FlangTokenTypes.INTERFACE -> parseTopLevel(FlangElementTypes.INTERFACE_DECL)
                FlangTokenTypes.ENUM -> parseTopLevel(FlangElementTypes.ENUM_DECL)
                FlangTokenTypes.IMPL -> parseTopLevel(FlangElementTypes.IMPL_DECL)
                FlangTokenTypes.OBJECT -> parseTopLevel(FlangElementTypes.OBJECT_DECL)
                null -> Unit
                else -> builder.advanceLexer()
            }
        }
        file.done(root)
        return builder.treeBuilt
    }

    private fun parseLineDecl(type: IElementType) {
        val marker = builder.mark()
        while (!builder.eof() && builder.tokenType != FlangTokenTypes.SEMI && builder.tokenType != FlangTokenTypes.RBRACE) {
            builder.advanceLexer()
        }
        if (builder.tokenType == FlangTokenTypes.SEMI) builder.advanceLexer()
        marker.done(type)
    }

    private fun parseTopLevel(type: IElementType) {
        val marker = builder.mark()
        var depth = 0
        var sawBody = false
        while (!builder.eof()) {
            when (builder.tokenType) {
                FlangTokenTypes.LBRACE -> {
                    sawBody = true
                    parseBlock()
                    if (depth == 0) break
                }
                FlangTokenTypes.SEMI -> {
                    builder.advanceLexer()
                    if (!sawBody) break
                }
                FlangTokenTypes.LPAREN, FlangTokenTypes.LBRACKET, FlangTokenTypes.LT -> {
                    depth++
                    builder.advanceLexer()
                }
                FlangTokenTypes.RPAREN, FlangTokenTypes.RBRACKET, FlangTokenTypes.GT -> {
                    if (depth > 0) depth--
                    builder.advanceLexer()
                }
                FlangTokenTypes.EMIT_CONTENT -> {
                    val emit = builder.mark()
                    builder.advanceLexer()
                    emit.done(FlangElementTypes.EMIT_BODY)
                }
                else -> {
                    parsePossibleTypeOrExpression()
                }
            }
            if (sawBody && depth == 0 && builder.tokenType != FlangTokenTypes.LBRACE) break
        }
        marker.done(type)
    }

    private fun parseBlock() {
        val block = builder.mark()
        builder.advanceLexer()
        while (!builder.eof() && builder.tokenType != FlangTokenTypes.RBRACE) {
            val stmt = builder.mark()
            when (builder.tokenType) {
                FlangTokenTypes.LBRACE -> parseBlock()
                FlangTokenTypes.EMIT_CONTENT -> {
                    val emit = builder.mark()
                    builder.advanceLexer()
                    emit.done(FlangElementTypes.EMIT_BODY)
                }
                else -> parsePossibleTypeOrExpression()
            }
            stmt.done(FlangElementTypes.STMT)
        }
        if (builder.tokenType == FlangTokenTypes.RBRACE) builder.advanceLexer()
        block.done(FlangElementTypes.BLOCK)
    }

    private fun parsePossibleTypeOrExpression() {
        val token = builder.tokenType
        when (token) {
            FlangTokenTypes.COLON, FlangTokenTypes.ARROW, FlangTokenTypes.AS, FlangTokenTypes.AMP -> parseTypeContextLead()
            FlangTokenTypes.ENUM_SHORTHAND -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(FlangElementTypes.ENUM_SHORTHAND_EXPR)
            }
            FlangTokenTypes.IDENTIFIER -> parseIdentifierShape()
            else -> builder.advanceLexer()
        }
    }

    private fun parseTypeContextLead() {
        builder.advanceLexer()
        val marker = builder.mark()
        while (!builder.eof() && builder.tokenType in typeTokens) builder.advanceLexer()
        marker.done(FlangElementTypes.TYPE_REF)
    }

    private fun parseIdentifierShape() {
        val marker = builder.mark()
        builder.advanceLexer()
        when (builder.tokenType) {
            FlangTokenTypes.DOT -> {
                builder.advanceLexer()
                if (builder.tokenType == FlangTokenTypes.IDENTIFIER) builder.advanceLexer()
                if (builder.tokenType == FlangTokenTypes.LPAREN) consumeBalanced()
                marker.done(FlangElementTypes.MEMBER_ACCESS)
            }
            FlangTokenTypes.LPAREN -> {
                consumeBalanced()
                marker.done(FlangElementTypes.CALL)
            }
            FlangTokenTypes.LBRACE -> {
                parseBlock()
                marker.done(FlangElementTypes.STRUCT_LITERAL)
            }
            else -> marker.done(FlangElementTypes.EXPR)
        }
    }

    private fun consumeBalanced() {
        val open = builder.tokenType
        val close = when (open) {
            FlangTokenTypes.LPAREN -> FlangTokenTypes.RPAREN
            FlangTokenTypes.LBRACKET -> FlangTokenTypes.RBRACKET
            FlangTokenTypes.LBRACE -> FlangTokenTypes.RBRACE
            else -> null
        }
        var depth = 0
        while (!builder.eof()) {
            if (builder.tokenType == open) depth++
            if (builder.tokenType == close) {
                depth--
                builder.advanceLexer()
                if (depth <= 0) break
                continue
            }
            builder.advanceLexer()
        }
    }

    private fun skipTrivia() {
        while (builder.tokenType == TokenType.WHITE_SPACE || builder.tokenType == FlangTokenTypes.LINE_COMMENT || builder.tokenType == FlangTokenTypes.BLOCK_COMMENT) {
            builder.advanceLexer()
        }
    }

    private companion object {
        private val typeTokens = setOf(
            FlangTokenTypes.IDENTIFIER,
            FlangTokenTypes.AMP,
            FlangTokenTypes.LT,
            FlangTokenTypes.GT,
            FlangTokenTypes.COMMA,
            FlangTokenTypes.LPAREN,
            FlangTokenTypes.RPAREN,
        )
    }
}
