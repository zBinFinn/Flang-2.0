package com.zbinfinn.flangidea

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.zbinfinn.frontend.FlangCompletionKind
import com.zbinfinn.frontend.FlangCompletionRequest
import com.zbinfinn.frontend.FlangFrontend
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
        val request = FlangCompletionRequest(
            source = text,
            offset = offset,
            filePath = file.virtualFile?.toPath(),
            projectRoots = listOfNotNull(file.project.basePath?.let { Path.of(it) }),
        )

        FlangFrontend.completions(request).forEach { completion ->
            val element = LookupElementBuilder
                .create(completion.insertText)
                .withLookupString(completion.lookup)
                .withPresentableText(completion.lookup)
                .withTailText(completion.tailText, true)
                .withTypeText(completion.typeText, true)
                .withBoldness(completion.kind == FlangCompletionKind.ENUM_ENTRY)
            matcher.addElement(element)
        }
    }

    private fun VirtualFile.toPath(): Path? =
        runCatching { Path.of(path) }.getOrNull()

    private fun identifierPrefix(text: String, offset: Int): String {
        var index = offset - 1
        while (index >= 0 && text[index].let { it == '_' || it.isLetterOrDigit() || it == '.' }) index--
        return text.substring(index + 1, offset).removePrefix(".")
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
