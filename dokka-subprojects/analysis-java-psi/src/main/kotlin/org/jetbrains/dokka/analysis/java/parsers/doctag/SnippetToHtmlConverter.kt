/*
 * Copyright 2014-2025 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.dokka.analysis.java.parsers.doctag

import com.intellij.codeInsight.javadoc.JavaDocUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.javadoc.PsiSnippetAttribute
import com.intellij.psi.javadoc.PsiSnippetDocTag
import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.analysis.java.JavaAnalysisPlugin
import org.jetbrains.dokka.analysis.java.util.from
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.querySingle
import org.jetbrains.dokka.utilities.htmlEscape

private typealias MarkupOperation = (String) -> String

public interface SnippetToHtmlConverter {
    public fun convertSnippet(
        snippet: PsiSnippetDocTag
    ): String
}

internal class DefaultSnippetToHtmlConverter(
    private val sourceSet: DokkaConfiguration.DokkaSourceSet,
    private val context: DokkaContext,
    private val docTagParserContext: DocTagParserContext
) : SnippetToHtmlConverter {

    private val logger = context.logger

    private val sampleFiles by lazy {
        context.plugin<JavaAnalysisPlugin>().querySingle { samplePsiFilesProvider }
            .getSamplePsiFiles(sourceSet, context)
    }

    private companion object {
        private val ALLOWED_ATTRS = mapOf(
            "start" to setOf("region"),
            "end" to setOf("region"),
            "highlight" to setOf("substring", "regex", "region", "type"),
            "replace" to setOf("substring", "regex", "region", "replacement"),
            "link" to setOf("substring", "regex", "region", "target", "type")
        )

        private const val ALLOWED_TAGS = "start|end|highlight|replace|link"

        // group1: comment syntax
        // group2: entire markup comment, @tag + attributes
        private val MARKUP_SPEC = Regex("(//|#|rem|REM|')\\s*(@(?:$ALLOWED_TAGS)(?:\\s.+)?)$")

        // group1: tag name only
        private val MARKUP_TAG = Regex("@($ALLOWED_TAGS)\\s*")

        // name=value
        // group 1: `name`
        // group 2: `=value` part
        // group 3: `value` part
        // only one of 4-6 groups will be not null
        // group 4: value inside single quotes
        // group 5: value inside double quotes
        // group 6: unquoted value
        private val ATTRIBUTE = Regex("(\\w+)\\s*(=\\s*('([^']*)'|\"([^\"]*)\"|(\\S*)))?\\s*")

        private const val SNIPPET_NOT_RESOLVED = "// snippet not resolved"
    }

    override fun convertSnippet(
        snippet: PsiSnippetDocTag
    ): String {
        val value = snippet.valueElement ?: return snippet.text // TODO mb in case of null change behaviour
        val attributeList = value.attributeList
//        val idAttribute = attributeList.getAttribute(PsiSnippetAttribute.ID_ATTRIBUTE) // TODO add id attribute to pre/code tag
// The first, id, is used to identify the snippet and serves as a reference anchor for both the documentation itself and the generated HTML output, becoming the id attribute of the corresponding <pre> tag.
//The latter, lang, identifies the language of the code snippet, adding a CSS class language-<value of lang> to the <code> tag of the output.


        val inlineSnippet = value.body?.content

        val externalSnippet = run {
            val fileAttr = attributeList.getAttribute(PsiSnippetAttribute.FILE_ATTRIBUTE)?.value
            val classAttr = attributeList.getAttribute(PsiSnippetAttribute.CLASS_ATTRIBUTE)?.value

            // Try to resolve the snippet file through PSI reference resolution (for files in `snippet-files`)
            // If that fails, search within sampleFiles (for snippets specified via Javadoc's --snippet-path, in Dokka's case in `sample` configuration option)
            when {
                fileAttr != null -> fileAttr.reference?.resolve() ?: sampleFiles.singleOrNull {
                    it.name == fileAttr.value
                }

                classAttr != null -> classAttr.reference?.resolve() ?: sampleFiles.singleOrNull {
                    it.name == "${classAttr.value}.java"
                }

                else -> null
            }
        }

        val region = attributeList.getAttribute(PsiSnippetAttribute.REGION_ATTRIBUTE)?.value?.value

        val processedSnippet = when {
            inlineSnippet != null && externalSnippet != null -> {
                "// hybrid snippet" // TODO("process hybrid snippets")
            }

            inlineSnippet != null -> {
                parseSnippet(inlineSnippet.map { it.text }, snippet)
            }

            externalSnippet != null -> {
                parseSnippet(externalSnippet.text.split("\n"), externalSnippet, region)
            }

            else -> {
                // TODO snippet is unresolved, log it
                SNIPPET_NOT_RESOLVED
            }
        }

        return "<pre>$processedSnippet</pre>"
    }

    /**
     * Parses markup for inline and external snippets. For external snippets, the snippet body is first extracted from the snippet file.
     *
     * @param lines lines of the snippet body for inline snippets, or of the snippet file for external snippets
     * @param externalSnippetRegionName name of the region to extract external snippet body from; null for inline snippets
     *
     * @return parsed snippet with applied markup
     */
    private fun parseSnippet(
        lines: List<String>,
        context: PsiElement,
        externalSnippetRegionName: String? = null
    ): String {
        // externalSnippetRegionName is null in 2 cases:
        // case 1: inline snippet, then we already snippetBody
        // case 2: external snippet where the region is not specified, then we take a whole file
        // externalSnippetRegionName is not null in external snippets with a specified region, then we need firstly to find start of the snippet body (`@start` with the appropriate region name)
        var snippetBodyStarted = externalSnippetRegionName == null

        val result = mutableListOf<String>()

        // Ordered list of active snippet regions
        data class ActiveRegion(
            val regionName: String?, // can be null for anonymous regions
            val operation: MarkupOperation? // can be null for non-markup tags - `@start` (regionName in this case is specified)
        )

        val activeRegions = mutableListOf<ActiveRegion>()

        val mutableLines = lines.toMutableList()

        for ((idx, line) in mutableLines.withIndex()) {
            val markupSpecMatch = MARKUP_SPEC.find(line)
            val markupSpec = markupSpecMatch?.groupValues?.get(2)

            if (snippetBodyStarted) {
                if (markupSpec != null) {
                    val lineWithMarkup = line
                        .replace(MARKUP_SPEC, "")
                        .trimEnd()
                        .applyMarkup(activeRegions.mapNotNull { it.operation })

                    // If the markup comment ends with `:`, it is treated as though it were an end-of-line comment on the following line
                    if (markupSpec.endsWith(":")) {
                        if (idx + 1 >= mutableLines.size) {
                            logger.warn("@snippet: don't place markup comment with ending `:` at the last line of snippet")
                            break
                        }
                        mutableLines[idx + 1] =
                            mutableLines[idx + 1] + " ${markupSpecMatch.groupValues[1]} ${markupSpec.removeSuffix(":")}"
                        result.addIfNotBlank(lineWithMarkup)
                        continue
                    }

                    val markupTagName = MARKUP_TAG.find(markupSpec)?.groupValues?.get(1) ?: continue
                    val attributes = getMarkupAttributes(markupSpec, markupTagName)

                    when (markupTagName) {
                        "start" -> {
                            val regionName = attributes["region"]
                            if (regionName == null) {
                                logger.warn("@snippet: tag @start without specified region attribute")
                                continue
                            }
                            activeRegions.add(ActiveRegion(regionName, null))
                        }

                        "end" -> {
                            val regionName = attributes["region"]
                            if (regionName != null) {
                                if (regionName == externalSnippetRegionName) {
                                    result.addIfNotBlank(lineWithMarkup)
                                    snippetBodyStarted = false
                                    break
                                }
                                var regionFound = false
                                val toRemove = activeRegions.lastOrNull { it.regionName == regionName }
                                if (toRemove != null) {
                                    activeRegions.remove(toRemove)
                                    regionFound = true
                                }
                                if (!regionFound) logger.warn("@snippet: invalid region \"$regionName\" in @end")
                            } else {
                                val lastActiveRegion = activeRegions.lastOrNull()
                                if (lastActiveRegion != null) {
                                    activeRegions.remove(lastActiveRegion)
                                } else if (externalSnippetRegionName != null) {
                                    result.addIfNotBlank(lineWithMarkup)
                                    snippetBodyStarted = false
                                    break
                                } else {
                                    logger.warn("@snippet: `@end` tag without a matching start of the region")
                                }
                            }

                            result.addIfNotBlank(lineWithMarkup)
                        }

                        "highlight", "replace", "link" -> {
                            val operation = when (markupTagName) {
                                "highlight" -> createHighlightOperation(attributes)
                                "replace" -> createReplaceOperation(attributes)
                                "link" -> createLinkOperation(attributes, context)
                                else -> null
                            }

                            if (operation == null) {
                                result.addIfNotBlank(lineWithMarkup)
                                continue
                            }

                            if (attributes.contains("region")) {
                                activeRegions.add(ActiveRegion(attributes["region"], operation))
                            }

                            val lineWithAppliedOperation = operation(lineWithMarkup).trimEnd()

                            result.addIfNotBlank(lineWithAppliedOperation)
                        }

                        else -> {
                            logger.warn("@snippet: unrecognized tag @$markupTagName in markup comment")
                        }
                    }

                } else {
                    result.add(line.applyMarkup(activeRegions.mapNotNull { it.operation }))
                }
            } else {
                if (markupSpec == null || MARKUP_TAG.find(markupSpec)?.groupValues?.get(1) != "start") continue
                val regionName = getMarkupAttributes(markupSpec, "start")["region"]
                if (regionName == null) {
                    logger.warn("@snippet: tag @start without specified region attribute")
                    continue
                }
                if (regionName == externalSnippetRegionName) snippetBodyStarted = true
            }
        }

        if (activeRegions.isNotEmpty()) {
            val names = activeRegions.joinToString(", ") { it.regionName ?: "anonymous" }
            logger.warn("@snippet: snippet body contains unclosed regions: $names")
        }

        if (snippetBodyStarted && externalSnippetRegionName != null) {
            logger.error("@snippet: external snippet don't contains closing @end tag")
        }

        return result.joinToString("\n").trimIndent()
    }

    private fun createHighlightOperation(attributes: Map<String, String?>): MarkupOperation? {
        val type = attributes["type"]?.toLowerCase()
        val (startTag, endTag) = when (type) {
            "bold", null -> "<b>" to "</b>"
            "italic" -> "<i>" to "</i>"
            "highlighted" -> "<mark>" to "</mark>"
            else -> {
                logger.warn("@snippet: invalid argument for `@highlight` type $type")
                return null
            }
        }

        fun String.wrapInTag() = "$startTag$this$endTag"

        val substring = attributes["substring"]
        val regex = attributes["regex"]?.toRegex()

        return { line ->
            var result = line

            if (substring != null) result = result.replace(substring, substring.wrapInTag())
            if (regex != null) result = result.replace(regex) { match -> match.value.wrapInTag() }

            if (substring == null && regex == null) result = (result + "\n").wrapInTag() // TODO check this with \n

            result
        }
    }

    private fun createReplaceOperation(attributes: Map<String, String?>): MarkupOperation? {
        val substring = attributes["substring"]
        val regex = attributes["regex"]?.toRegex()
        val replacement = attributes["replacement"] ?: run {
            logger.warn("@snippet: specify `replacement` attribute for @replace markup tag")
            return null
        }

        return { line ->
            var result = line

            if (substring != null) result = result.replace(substring, replacement)
            if (regex != null) result = result.replace(regex, replacement)

            if (substring == null && regex == null) result = replacement

            result
        }
    }

    private fun createLinkOperation(attributes: Map<String, String?>, context: PsiElement): MarkupOperation? {
        val substring = attributes["substring"]
        val regex = attributes["regex"]?.toRegex()
        val target = attributes["target"] ?: run {
            logger.warn("@snippet: specify `target` attribute for @link markup tag")
            return null
        }

        val resolvedTarget = JavaDocUtil.findReferenceTarget(context.manager, target, context) ?: run {
            logger.warn("@snippet: unresolved target for @link tag")
            return null
        }

        val dri = DRI.from(resolvedTarget)
        val driId = docTagParserContext.store(dri)

        fun String.wrapInLink(): String = """<a data-dri="${driId.htmlEscape()}">$this</a>"""

        return { line ->
            var result = line

            if (substring != null) result = result.replace(substring, substring.wrapInLink())
            if (regex != null) result = result.replace(regex) { match -> match.value.wrapInLink() }

            if (substring == null && regex == null) result = result.wrapInLink()

            result
        }
    }

    private fun String.applyMarkup(markupOperations: List<MarkupOperation>): String = markupOperations
        .fold(this.htmlEscape()) { acc, op -> op(acc) }
        .trimEnd()

    private fun MutableList<String>.addIfNotBlank(element: String) {
        if (element.isNotBlank()) this.add(element)
    }

    private fun getMarkupAttributes(markupSpec: String, tagName: String): Map<String, String?> =
        ATTRIBUTE.findAll(
            markupSpec.removePrefix(
                "@$tagName"
            ).trimStart()
        ).mapNotNull { match ->
            val attributeName = match.groupValues[1]
            if (ALLOWED_ATTRS[tagName]?.contains(attributeName) != true) {
                logger.warn("@snippet: invalid attribute $attributeName used in @$tagName tag")
                null
            } else {
                attributeName to (match.groupValues[4].takeIf { it.isNotBlank() }
                    ?: match.groupValues[5].takeIf { it.isNotBlank() }
                    ?: match.groupValues[6].takeIf { it.isNotBlank() })
            }
        }.toMap()
}
