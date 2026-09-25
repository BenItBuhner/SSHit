package app.berth.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings › Licences against what ships: every licence file under `assets/licenses/` is opened by
 * exactly one row and every row's file is there, so a text added or renamed cannot go unlisted, and
 * every library on the release runtime classpath answers to a row, so a dependency cannot either;
 * and a file's hard wraps are joined while its headings, title block and rules keep their lines, and a
 * table's rows stand as paragraphs of their own. Of the shipped texts only the icon sets' table has any,
 * so a text added later that the rule would split fails here first.
 */
class LicencesTest {
    private val shipped = File("src/main/assets/licenses")

    @Test
    fun `every shipped licence file has one row, and every row's file ships`() {
        val named = SHIPPED_NOTICES.flatMap { (_, notices) -> notices.flatMap { it.files } }
        assertEquals("a file named by two rows", named.size, named.toSet().size)
        val files = shipped.list().orEmpty().toSet()
        assertTrue("the licence folder is empty", files.isNotEmpty())
        assertEquals(files, named.toSet())
    }

    @Test
    fun `a credit with no licence file names its terms whole`() {
        val gruvbox = SHIPPED_NOTICES.flatMap { it.second }.single { it.name.startsWith("Gruvbox") }
        assertTrue(gruvbox.files.isEmpty())
        assertEquals("Colours from gruvbox by Pavel Pertsev (github.com/morhetz/gruvbox) under the MIT/X11 licence its README states", gruvbox.terms)
    }

    @Test
    fun `hard wraps are joined, and headings, title blocks and rules keep their lines`() {
        val ofl = """
            |-----------------------------------------------------------
            |SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
            |-----------------------------------------------------------
            |
            |PREAMBLE
            |The goals of the Open Font License (OFL) are to stimulate worldwide
            |development of collaborative font projects, to support the font creation
            |with others.
        """.trimMargin()
        assertEquals(
            listOf(
                "-----------------------------------------------------------\nSIL OPEN FONT LICENSE Version 1.1 - 26 February 2007\n-----------------------------------------------------------",
                "PREAMBLE\nThe goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects, to support the font creation with others.",
            ),
            licenceParagraphs(ofl),
        )
        val apache = "                                 Apache License\r\n                           Version 2.0, January 2004\r\n\r\n   1. Definitions.\r\n"
        assertEquals(listOf("Apache License\nVersion 2.0, January 2004", "1. Definitions."), licenceParagraphs(apache))
    }

    @Test
    fun `a table's rows are paragraphs with their cells set apart, while one sentence's double space still runs on`() {
        val table = """
            |The font as a whole is under the MIT licence (nerd-fonts-symbols-MIT.txt). The icon sets it
            |collects keep their own licences:
            |Codicons                https://github.com/microsoft/vscode-codicons           0.0.45           CC BY 4.0
            |extraglyphs             https://github.com/source-foundry/Hack                 -                MIT
            |Font Logos              https://github.com/lukas-w/font-logos                  1.3.0            The Unlicense
        """.trimMargin()
        assertEquals(
            listOf(
                "The font as a whole is under the MIT licence (nerd-fonts-symbols-MIT.txt). The icon sets it collects keep their own licences:",
                "Codicons \u00B7 https://github.com/microsoft/vscode-codicons \u00B7 0.0.45 \u00B7 CC BY 4.0",
                "extraglyphs \u00B7 https://github.com/source-foundry/Hack \u00B7 - \u00B7 MIT",
                "Font Logos \u00B7 https://github.com/lukas-w/font-logos \u00B7 1.3.0 \u00B7 The Unlicense",
            ),
            licenceParagraphs(table),
        )
        val sentences = "      the brackets!)  The text should be enclosed in the appropriate\n      comment syntax for the file format."
        assertEquals(
            listOf("the brackets!) The text should be enclosed in the appropriate comment syntax for the file format."),
            licenceParagraphs(sentences),
        )
    }

    @Test
    fun `a justified text reads as prose, though its lines hold three padded runs each`() {
        val justified = """
            |THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
            |EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
            |MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
            |NONINFRINGEMENT.
        """.trimMargin()
        assertEquals(
            listOf("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT."),
            licenceParagraphs(justified),
        )
    }

    @Test
    fun `a box drawn in stars loses its drawing and its words read as prose, while a bullet outside keeps its star`() {
        val boxed = """
            |* A bullet outside any box keeps its star.
            |
            |************************************************************************
            |*                                                                      *
            |*  6. Disclaimer of Warranty                                           *
            |*  -------------------------                                           *
            |*                                                                      *
            |*  Covered Software is provided under this License on an "as is"       *
            |*  basis, without warranty of any kind, either expressed, implied, or  *
            |*  statutory.                                                          *
            |*                                                                      *
            |************************************************************************
            |
            |8. Litigation
        """.trimMargin()
        assertEquals(
            listOf(
                "* A bullet outside any box keeps its star.",
                "6. Disclaimer of Warranty\n-------------------------",
                "Covered Software is provided under this License on an \"as is\" basis, without warranty of any kind, either expressed, implied, or statutory.",
                "8. Litigation",
            ),
            licenceParagraphs(boxed),
        )
    }

    @Test
    fun `a box drawn in equals signs loses its drawing, while a heading's underline keeps its line`() {
        val notice = """
            |   =========================================================================
            |   ==  NOTICE file corresponding to the section 4 d of                    ==
            |   ==  the Apache License, Version 2.0,                                   ==
            |   ==  in this case for the Kotlin Compiler distribution.                 ==
            |   =========================================================================
            |
            |   Kotlin Compiler
        """.trimMargin()
        assertEquals(
            listOf(
                "NOTICE file corresponding to the section 4 d of\nthe Apache License, Version 2.0,\nin this case for the Kotlin Compiler distribution.",
                "Kotlin Compiler",
            ),
            licenceParagraphs(notice),
        )
        val mpl = "Mozilla Public License Version 2.0\n==================================\n\n1. Definitions\n--------------"
        assertEquals(listOf("Mozilla Public License Version 2.0\n==================================", "1. Definitions\n--------------"), licenceParagraphs(mpl))
    }

    @Test
    fun `every font's and library's text names where it came from`() {
        for ((section, notices) in SHIPPED_NOTICES.filter { (section, _) -> section == "Fonts" || section == "Libraries" }) {
            for (notice in notices) assertTrue("$section: ${notice.name} names no origin", !notice.origin.isNullOrBlank() && notice.origin.startsWith("github.com/"))
        }
        assertEquals(listOf("Fonts", "Libraries"), SHIPPED_NOTICES.map { it.first }.filter { it == "Fonts" || it == "Libraries" })
    }

    /**
     * Every module the release APK carries answers to a Libraries row that opens its text, one row
     * per upstream licence file; the one text several rows' worth share is theirs only because none
     * of them carries a NOTICE, and each library whose upstream does (Apache 2.0 §4(d); the build
     * strips the jars' copies) ships it. The build passes the release runtime classpath's modules.
     */
    @Test
    fun `every library the release APK carries has a row with its text, and its NOTICE where it has one`() {
        val modules = System.getProperty("berth.releaseModules").orEmpty().split(',').filter(String::isNotBlank)
        assertTrue("the build passed no release modules (berth.releaseModules)", modules.size > 50)
        val rows = SHIPPED_NOTICES.single { it.first == "Libraries" }.second.associateBy { it.name }
        assertEquals("modules on the release classpath with no row", emptyList<String>(), modules.filter { m -> ROW_BY_MODULE.none { m.startsWith(it.first) } })
        for ((prefix, row) in ROW_BY_MODULE) {
            assertTrue("$prefix's row is not among the Libraries: $row", rows[row]?.files.orEmpty().isNotEmpty())
            assertTrue("$prefix: no module the release carries; its row outlived it", modules.any { it.startsWith(prefix) })
        }
        for (row in WITH_NOTICE) assertTrue("$row ships no NOTICE", rows.getValue(row).files.any { "NOTICE" in it })
        assertTrue("the shared text's libraries carry no NOTICE", rows.getValue(SHARED).files.none { "NOTICE" in it })
    }

    /**
     * A row's texts were taken at the tag its origin names, and that tag is the version of each of its
     * modules the release resolves, so a bump that moves a licence or a NOTICE fails here until the
     * files are taken again at the new tag and the origin says so.
     */
    @Test
    fun `every library's origin names the tag of the version the release carries`() {
        val modules = System.getProperty("berth.releaseModules").orEmpty().split(',').filter(String::isNotBlank)
        assertTrue("the build passed no release modules (berth.releaseModules)", modules.size > 50)
        val origins = SHIPPED_NOTICES.single { it.first == "Libraries" }.second.associate { it.name to it.origin.orEmpty() }
        assertEquals("rows with no tag to hold", ROW_BY_MODULE.map { it.first }.filter { it != ANDROIDX }, PIN_BY_MODULE.keys.toList())
        val stale = modules.mapNotNull { module ->
            val (prefix, row) = ROW_BY_MODULE.firstOrNull { module.startsWith(it.first) } ?: return@mapNotNull null
            val tag = PIN_BY_MODULE[prefix]?.invoke(module.substringAfterLast(':')) ?: return@mapNotNull null
            "$module: $row's origin names no \"$tag\"".takeIf { tag !in origins.getValue(row) }
        }
        assertEquals("modules whose row's origin is another version's", emptyList<String>(), stale)
    }

    @Test
    fun `only the icon sets' text holds table rows, and it holds all fourteen`() {
        val rows = shipped.listFiles().orEmpty()
            .associate { file -> file.name to licenceParagraphs(file.readText()).count { TABLE_CELL_SEPARATOR in it } }
            .filterValues { it > 0 }
        assertEquals(mapOf("nerd-fonts-symbols-icon-sets.txt" to 14), rows)
    }

    @Test
    fun `every shipped text keeps every word it has, and adds none but a table's separators`() {
        for (file in shipped.listFiles().orEmpty()) {
            val text = file.readText()
            val words = { s: String -> s.split(Regex("[ \t\r\n]+")).filter { it.isNotEmpty() && !it.all { c -> c == '*' } && !it.all { c -> c == '=' } } }
            val shown = licenceParagraphs(text).flatMap { words(it.replace(TABLE_CELL_SEPARATOR, " ")) }
            assertEquals(file.name, words(text), shown)
        }
    }

    private companion object {
        const val SHARED = "Dagger and Hilt, JSpecify, listenablefuture, javax.inject and JSR 305"
        const val KOTLIN = "Kotlin, the language's standard library"
        const val COROUTINES = "kotlinx.coroutines, the concurrency"
        const val SERIALIZATION = "kotlinx.serialization, the stored formats"
        const val JAKARTA = "Jakarta Dependency Injection, Hilt's annotations"
        const val SSHJ = "sshj, the SSH transport"
        const val ANDROIDX = "androidx."

        /** The release runtime classpath by group or group:name, each to the row that answers for it. */
        val ROW_BY_MODULE = listOf(
            ANDROIDX to "AndroidX, the interface and the database",
            "org.jetbrains.kotlin:kotlin-stdlib" to KOTLIN,
            "org.jetbrains.kotlinx:kotlinx-coroutines-" to COROUTINES,
            "org.jetbrains.kotlinx:kotlinx-serialization-" to SERIALIZATION,
            "org.jetbrains:annotations" to "JetBrains annotations",
            "jakarta.inject:" to JAKARTA,
            "com.google.dagger:" to SHARED,
            "org.jspecify:" to SHARED,
            "com.google.guava:listenablefuture" to SHARED,
            "javax.inject:" to SHARED,
            "com.google.code.findbugs:jsr305" to SHARED,
            "com.hierynomus:sshj" to SSHJ,
            "com.hierynomus:asn-one" to "asn-one, sshj's ASN.1 coding",
            "org.bouncycastle:" to "Bouncy Castle, the cryptography",
            "org.slf4j:" to "SLF4J, the transport's logging",
        )

        /**
         * By [ROW_BY_MODULE]'s prefixes, what a row's origin names for a module at a version, as that
         * upstream tags its releases. AndroidX has none: its text is the monorepo's at a commit, one
         * file whatever each library's version.
         */
        val PIN_BY_MODULE: Map<String, (String) -> String> = mapOf(
            "org.jetbrains.kotlin:kotlin-stdlib" to { v -> "JetBrains/kotlin at v$v:" },
            "org.jetbrains.kotlinx:kotlinx-coroutines-" to { v -> "kotlinx.coroutines at $v:" },
            "org.jetbrains.kotlinx:kotlinx-serialization-" to { v -> "kotlinx.serialization at v$v:" },
            "org.jetbrains:annotations" to { v -> "JetBrains/java-annotations at $v:" },
            "jakarta.inject:" to { v -> "jakartaee/inject at $v:" },
            "com.google.dagger:" to { v -> "google/dagger at dagger-$v:" },
            "org.jspecify:" to { v -> "jspecify/jspecify at v$v:" },
            "com.google.guava:listenablefuture" to { v -> "listenablefuture $v's parent" },
            "javax.inject:" to { v -> "javax.inject $v and" },
            "com.google.code.findbugs:jsr305" to { v -> "jsr305 $v," },
            "com.hierynomus:sshj" to { v -> "hierynomus/sshj at v$v:" },
            "com.hierynomus:asn-one" to { v -> "hierynomus/asn-one at v$v:" },
            "org.bouncycastle:" to { v -> "bcgit/bc-java at r${v.replace(".", "rv")} ($v):" },
            "org.slf4j:" to { v -> "qos-ch/slf4j at v_$v:" },
        )

        val WITH_NOTICE = listOf(SSHJ, KOTLIN, COROUTINES, SERIALIZATION, JAKARTA)
    }
}
