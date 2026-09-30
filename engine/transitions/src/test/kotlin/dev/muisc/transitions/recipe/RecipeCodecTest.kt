package dev.muisc.transitions.recipe

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeCodecTest {

    private fun only(r: RecipeParseResult): RecipeProblem {
        assertNull(r.recipe, "expected a failed parse")
        assertEquals(1, r.problems.size, r.problems.joinToString("\n"))
        return r.problems.single()
    }

    @Test fun validTextParsesAndRoundTrips() {
        val starter = RecipeLibrary.starter("my-mix", "My mix")
        val text = RecipeCodec.encode(starter)
        assertTrue(text.endsWith("\n"))
        val r = RecipeCodec.parse(text)
        assertEquals(starter, r.recipe)
        assertTrue(r.problems.isEmpty())
        // The same file through a stream and a file.
        assertEquals(starter, RecipeCodec.parse(ByteArrayInputStream(text.toByteArray())).recipe)
        val f = Files.createTempFile("recipe", ".json").toFile()
        try {
            f.writeText(text)
            assertEquals(starter, RecipeCodec.parse(f).recipe)
        } finally {
            f.delete()
        }
    }

    @Test fun syntaxErrorsCarryLineAndColumn() {
        val missingComma = "{\n  \"id\": \"x\",\n  \"name\": \"X\"\n  \"ambition\": 0.5\n}"
        val p = only(RecipeCodec.parse(missingComma))
        assertEquals(4 to 3, p.line to p.column)
        assertTrue(p.isError && p.message.contains("comma may be missing"), p.message)

        val trailing = only(RecipeCodec.parse("{\n  \"id\": \"x\",\n  \"name\": \"X\",\n}"))
        assertEquals(4 to 1, trailing.line to trailing.column)
        assertTrue(trailing.message.contains("trailing comma"), trailing.message)

        val unquoted = only(RecipeCodec.parse("{ \"id\": \"x\", \"name\": \"X\",\n  \"a\": { \"level\": [ { \"at\": bars, \"v\": 0 } ] } }"))
        assertEquals(2, unquoted.line)
        assertTrue(unquoted.message.contains("'bars'") && unquoted.message.contains("double quotes"), unquoted.message)

        val unquotedArithmetic = only(RecipeCodec.parse("{ \"id\": \"x\", \"name\": \"X\", \"timing\": { \"lengthBars\": 8bars } }"))
        assertTrue(unquotedArithmetic.message.contains("double quotes"), unquotedArithmetic.message)

        val comment = only(RecipeCodec.parse("{\n  // my recipe\n  \"id\": \"x\" }"))
        assertEquals(2 to 3, comment.line to comment.column)
        assertTrue(comment.message.contains("comments"))

        val single = only(RecipeCodec.parse("{ 'id': 'x' }"))
        assertTrue(single.message.contains("double quotes"))

        val dup = only(RecipeCodec.parse("{ \"id\": \"x\",\n \"id\": \"y\", \"name\": \"X\" }"))
        assertEquals(2 to 2, dup.line to dup.column)
        assertTrue(dup.message.contains("appears twice"))

        val open = only(RecipeCodec.parse("{ \"id\": \"x\", \"name\": \"X\""))
        assertTrue(open.message.contains("'}' is missing"), open.message)
    }

    @Test fun unknownKeysAreReportedWithPathAndSuggestion() {
        val text = """
            {
              "id": "x",
              "name": "X",
              "a": {
                "levle": [ { "at": 0, "v": 1 } ],
                "echo": { "sned": [] }
              },
              "timnig": {}
            }
        """.trimIndent()
        val r = RecipeCodec.parse(text)
        assertNull(r.recipe)
        val byPath = r.problems.associateBy { it.path }
        assertEquals(setOf("a.levle", "a.echo.sned", "timnig"), byPath.keys)
        val levle = byPath.getValue("a.levle")
        assertTrue(levle.message.contains("did you mean 'level'?"), levle.message)
        assertEquals(5 to 5, levle.line to levle.column)
        assertTrue(byPath.getValue("a.echo.sned").message.contains("did you mean 'send'?"))
        assertTrue(byPath.getValue("timnig").message.contains("did you mean 'timing'?"))
        // No suggestion when nothing is close: the valid keys are listed instead.
        val far = only(RecipeCodec.parse("""{ "id": "x", "name": "X", "zzzzzz": 1 }"""))
        assertTrue(far.message.contains("valid keys here:") && far.message.contains("ambition"), far.message)
    }

    @Test fun wrongTypesAreReportedWithPath() {
        val text = """
            {
              "id": "x",
              "name": "X",
              "ambition": "high",
              "tags": "blend",
              "timing": { "tempo": "matc", "lengthBars": [8] },
              "vars": { "len": { "default": "16", "min": 8, "max": true } },
              "a": { "level": [ { "at": 0, "v": { "x": 1 } } ] }
            }
        """.trimIndent()
        val r = RecipeCodec.parse(text)
        assertNull(r.recipe)
        val byPath = r.problems.associateBy { it.path }
        assertTrue(byPath.getValue("ambition").message.contains("expected a number, got the text \"high\""), byPath.getValue("ambition").message)
        assertTrue(byPath.getValue("tags").message.contains("expected a list"))
        assertTrue(byPath.getValue("timing.tempo").message.contains("did you mean 'match'?"))
        assertTrue(byPath.getValue("timing.lengthBars").message.contains("number or an expression"))
        assertTrue(byPath.getValue("vars.len.max").message.contains("expected a number, got true"))
        assertTrue(byPath.getValue("a.level[0].v").message.contains("number or an expression"))
        assertEquals(8, byPath.getValue("a.level[0].v").line)
        // A quoted number is accepted where a number is expected, as by RecipeFormat itself.
        assertTrue("vars.len.default" !in byPath)
    }

    @Test fun missingRequiredKeys() {
        val r = RecipeCodec.parse("""{ "id": "x", "a": { "level": [ { "at": 0 } ] } }""")
        val messages = r.problems.map { it.path to it.message }
        assertTrue(messages.contains("" to "missing required key 'name'"), messages.toString())
        assertTrue(messages.contains("a.level[0]" to "missing required key 'v'"), messages.toString())
    }

    @Test fun neverThrowsOnBadInput() {
        val inputs = listOf(
            "", "   ", "null", "[]", "42", "\"text\"", "{", "}", "{\"id\"", "{\"id\":}", "{\"id\": \"x\" ,}", "{\"id\": 1e}",
            "{\"id\": -}", "{\"id\": \"\\q\"}", "{\"id\": \"\\u12\"}", "{\"id\": \"a\nb\"}", "{\"id\": tru}", "{\"id\": nulll}",
            "{\"id\": \"x\", \"name\": \"X\", \"a\": null}", "{\"id\": \"x\", \"name\": \"X\", \"vars\": []}",
            "{\"id\": \"x\", \"name\": \"X\", \"format\": 1.5}", "\u0000\u0001", "{\"id\": \"x\"} trailing",
        )
        for (s in inputs) {
            val r = RecipeCodec.parse(s)
            assertNull(r.recipe, "should not parse: $s")
            assertTrue(r.problems.isNotEmpty() && r.problems.all { it.isError }, "no problem for: $s")
        }
    }

    @Test fun unreadableFileIsAProblem() {
        val r = RecipeCodec.parse(File("/definitely/not/here.json"))
        assertNull(r.recipe)
        assertTrue(r.problems.single().message.startsWith("cannot read here.json"))
    }

    @Test fun byteOrderMarkIsTolerated() {
        val r = RecipeCodec.parse("\uFEFF{ \"id\": \"x\", \"name\": \"X\" }")
        assertEquals("x", assertNotNull(r.recipe).id)
    }

    @Test fun validatorProblemsCanBeLocated() {
        val text = """
            {
              "id": "x",
              "name": "X",
              "a": {
                "low": [
                  { "at": 0, "v": -6 }
                ]
              }
            }
        """.trimIndent()
        val r = RecipeCodec.parse(text)
        val recipe = assertNotNull(r.recipe)
        val located = r.locate(RecipeValidator().validate(recipe).problems)
        val boundary = located.single { it.path == "a.low" && it.isError }
        assertEquals(5 to 5, boundary.line to boundary.column)
        // A path the file does not spell out falls back to its nearest written parent.
        assertEquals(TextPosition(6, 7), r.positionOf("a.low[0].curve"))
    }

    @Test fun suggestionsUseEditDistance() {
        assertEquals("level", RecipeCodec.suggest("levle", listOf("level", "low", "mid")))
        assertEquals("level", RecipeCodec.suggest("Level", listOf("level", "low")))
        assertEquals("lengthBars", RecipeCodec.suggest("lenghtBars", listOf("lengthBars", "tempo")))
        assertNull(RecipeCodec.suggest("zzz", listOf("level", "low")))
        assertEquals(1, RecipeCodec.editDistance("levle", "level"))
    }
}
