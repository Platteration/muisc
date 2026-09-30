package dev.muisc.transitions.recipe

import kotlinx.serialization.json.Json

/** The JSON dialect recipes are read and written in. Strict about unknown keys, because in a hand-written file an unknown key is almost always a typo. */
object RecipeFormat {
    val json: Json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    fun decode(text: String): TransitionRecipe = json.decodeFromString(TransitionRecipe.serializer(), text)
    fun encode(recipe: TransitionRecipe): String = json.encodeToString(TransitionRecipe.serializer(), recipe)
}
