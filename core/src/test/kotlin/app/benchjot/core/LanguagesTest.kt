package app.benchjot.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesTest {
    @Test fun sameLanguagesAsTheWeb() {
        val web = Fixtures.json("languages.json").jsonObject
        val expected = web["languages"]!!.jsonArray.map { it.jsonObject }.map { l: JsonObject ->
            Language(
                id = l["id"]!!.jsonPrimitive.content,
                label = l["label"]!!.jsonPrimitive.content,
                ext = l["ext"]!!.jsonArray.map { it.jsonPrimitive.content },
                indent = l["indent"]!!.jsonPrimitive.content,
                comment = l["comment"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                keywords = l["keywords"]!!.jsonArray.map { it.jsonPrimitive.content },
                keys = l["keys"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
        assertEquals(expected, LANGUAGES)
        assertEquals(web["common"]!!.jsonArray.map { it.jsonPrimitive.content }, COMMON_KEYS)
        assertEquals(web["default"]!!.jsonPrimitive.content, DEFAULT_LANGUAGE)
    }

    @Test fun newBlocksDefaultToPython() {
        assertEquals("python", DEFAULT_LANGUAGE)
        org.junit.Assert.assertFalse(languageChosen("python", ""))
        org.junit.Assert.assertFalse(languageChosen("text", "  "))
        org.junit.Assert.assertTrue(languageChosen("javascript", ""))
        org.junit.Assert.assertTrue(languageChosen("python", "print(1)"))
    }

    @Test fun filenames() {
        assertEquals("r", languageFromFilename("plot.R"))
        assertEquals("matlab", languageFromFilename("f.m"))
        assertEquals("python", languageFromFilename("Train.PY"))
        assertNull(languageFromFilename("README"))
        assertEquals("code-2.py", downloadName(" ", "python", 1))
        assertEquals("a.txt", downloadName("a.txt", "python", 0))
        assertEquals("text", languageConfig("nope").id)
        assertEquals(listOf("Tab", ":", "#", "_", "self", "=", "\"", "'", "{"), symbolKeys("python").take(9))
    }

    @Test fun detectionMatchesTheWeb() {
        for (case in Fixtures.json("detect.json").jsonArray.map { it.jsonObject }) {
            val code = case["code"]!!.jsonPrimitive.content
            val want = case["language"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(code, want, detectLanguage(code))
        }
    }
}
