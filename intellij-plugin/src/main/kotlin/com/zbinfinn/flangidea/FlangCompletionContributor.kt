package com.zbinfinn.flangidea

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

class FlangCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (file.fileType != FlangFileType.INSTANCE) return
        val cleaned = file.text.withoutIntellijDummy(parameters.offset)
        val text = cleaned.text
        val offset = cleaned.offset
        val prefix = identifierPrefix(text, offset)
        val matcher = result.withPrefixMatcher(prefix)
        val context = FlangCompletionRequest(
            source = text,
            offset = offset,
            filePath = file.virtualFile?.toPath(),
            projectRoots = listOfNotNull(file.project.basePath?.let { Path.of(it) }),
        )

        FlangCompletionEngine.complete(context).forEach { completion ->
            val element = LookupElementBuilder
                .create(completion.insertText)
                .withLookupString(completion.lookup)
                .withPresentableText(completion.lookup)
                .withTailText(completion.tailText, true)
                .withTypeText(completion.typeText, true)
                .withInsertHandler(insertHandler(completion))
            matcher.addElement(element)
        }
    }

    private fun insertHandler(completion: FlangCompletionItem): InsertHandler<LookupElement>? =
        when (completion.kind) {
            FlangCompletionKind.FUNCTION,
            FlangCompletionKind.MEMBER_FUNCTION,
            FlangCompletionKind.STATIC_FUNCTION,
            -> InsertHandler { context, _ ->
                val document = context.document
                var caretTarget = context.tailOffset
                if (completion.insertText.endsWith("()")) {
                    caretTarget = (context.tailOffset - 1).coerceAtLeast(context.startOffset)
                }
                val importText = completion.importToAdd?.let { module ->
                    if (document.text.contains(Regex("""(?m)^\s*import\s+\Q$module\E\s*;"""))) null else "import $module;\n"
                }
                if (importText != null) {
                    val insertOffset = document.importInsertOffset()
                    document.insertString(insertOffset, importText)
                    if (insertOffset <= caretTarget) caretTarget += importText.length
                }
                context.editor.caretModel.moveToOffset(caretTarget)
            }
            else -> null
        }

    private fun com.intellij.openapi.editor.Document.importInsertOffset(): Int {
        val text = charsSequence.toString()
        val declarations = Regex("""(?m)^\s*(?:package|import)\s+[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*\s*;\s*\R?""")
            .findAll(text)
            .toList()
        return declarations.lastOrNull()?.range?.last?.plus(1) ?: 0
    }

    private fun VirtualFile.toPath(): Path? =
        runCatching { Path.of(path) }.getOrNull()

    private fun identifierPrefix(text: String, offset: Int): String {
        var index = offset - 1
        while (index >= 0 && text[index].let { it == '_' || it.isLetterOrDigit() || it == '.' }) index--
        return text.substring(index + 1, offset).substringAfterLast('.')
    }

    private data class CleanedCompletionText(val text: String, val offset: Int)

    private fun String.withoutIntellijDummy(offset: Int): CleanedCompletionText {
        val dummy = "IntellijIdeaRulezzz"
        val dummyIndex = indexOf(dummy)
        if (dummyIndex < 0) return CleanedCompletionText(this, offset.coerceIn(0, length))
        val cleaned = removeRange(dummyIndex, dummyIndex + dummy.length)
        val adjustedOffset = if (dummyIndex < offset) offset - dummy.length else offset
        return CleanedCompletionText(cleaned, adjustedOffset.coerceIn(0, cleaned.length))
    }
}
