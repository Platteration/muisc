@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.material3

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.PROPERTY_GETTER,
)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalMaterial3Api

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalMaterial3ExpressiveApi

/** Material 3 colour roles (all of them, so a typo in a role name is caught). */
@Immutable
class ColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerLowest: Color,
) {
    fun copy(
        primary: Color = this.primary,
        onPrimary: Color = this.onPrimary,
        primaryContainer: Color = this.primaryContainer,
        onPrimaryContainer: Color = this.onPrimaryContainer,
        inversePrimary: Color = this.inversePrimary,
        secondary: Color = this.secondary,
        onSecondary: Color = this.onSecondary,
        secondaryContainer: Color = this.secondaryContainer,
        onSecondaryContainer: Color = this.onSecondaryContainer,
        tertiary: Color = this.tertiary,
        onTertiary: Color = this.onTertiary,
        tertiaryContainer: Color = this.tertiaryContainer,
        onTertiaryContainer: Color = this.onTertiaryContainer,
        background: Color = this.background,
        onBackground: Color = this.onBackground,
        surface: Color = this.surface,
        onSurface: Color = this.onSurface,
        surfaceVariant: Color = this.surfaceVariant,
        onSurfaceVariant: Color = this.onSurfaceVariant,
        surfaceTint: Color = this.surfaceTint,
        inverseSurface: Color = this.inverseSurface,
        inverseOnSurface: Color = this.inverseOnSurface,
        error: Color = this.error,
        onError: Color = this.onError,
        errorContainer: Color = this.errorContainer,
        onErrorContainer: Color = this.onErrorContainer,
        outline: Color = this.outline,
        outlineVariant: Color = this.outlineVariant,
        scrim: Color = this.scrim,
        surfaceBright: Color = this.surfaceBright,
        surfaceDim: Color = this.surfaceDim,
        surfaceContainer: Color = this.surfaceContainer,
        surfaceContainerHigh: Color = this.surfaceContainerHigh,
        surfaceContainerHighest: Color = this.surfaceContainerHighest,
        surfaceContainerLow: Color = this.surfaceContainerLow,
        surfaceContainerLowest: Color = this.surfaceContainerLowest,
    ): ColorScheme = ColorScheme(
        primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
        secondary, onSecondary, secondaryContainer, onSecondaryContainer,
        tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
        background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint,
        inverseSurface, inverseOnSurface, error, onError, errorContainer, onErrorContainer,
        outline, outlineVariant, scrim, surfaceBright, surfaceDim,
        surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest,
    )
}

private val U = Color.Unspecified

fun lightColorScheme(
    primary: Color = U,
    onPrimary: Color = U,
    primaryContainer: Color = U,
    onPrimaryContainer: Color = U,
    inversePrimary: Color = U,
    secondary: Color = U,
    onSecondary: Color = U,
    secondaryContainer: Color = U,
    onSecondaryContainer: Color = U,
    tertiary: Color = U,
    onTertiary: Color = U,
    tertiaryContainer: Color = U,
    onTertiaryContainer: Color = U,
    background: Color = U,
    onBackground: Color = U,
    surface: Color = U,
    onSurface: Color = U,
    surfaceVariant: Color = U,
    onSurfaceVariant: Color = U,
    surfaceTint: Color = U,
    inverseSurface: Color = U,
    inverseOnSurface: Color = U,
    error: Color = U,
    onError: Color = U,
    errorContainer: Color = U,
    onErrorContainer: Color = U,
    outline: Color = U,
    outlineVariant: Color = U,
    scrim: Color = U,
    surfaceBright: Color = U,
    surfaceDim: Color = U,
    surfaceContainer: Color = U,
    surfaceContainerHigh: Color = U,
    surfaceContainerHighest: Color = U,
    surfaceContainerLow: Color = U,
    surfaceContainerLowest: Color = U,
): ColorScheme = ColorScheme(
    primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
    secondary, onSecondary, secondaryContainer, onSecondaryContainer,
    tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
    background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint,
    inverseSurface, inverseOnSurface, error, onError, errorContainer, onErrorContainer,
    outline, outlineVariant, scrim, surfaceBright, surfaceDim,
    surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest,
)

fun darkColorScheme(
    primary: Color = U,
    onPrimary: Color = U,
    primaryContainer: Color = U,
    onPrimaryContainer: Color = U,
    inversePrimary: Color = U,
    secondary: Color = U,
    onSecondary: Color = U,
    secondaryContainer: Color = U,
    onSecondaryContainer: Color = U,
    tertiary: Color = U,
    onTertiary: Color = U,
    tertiaryContainer: Color = U,
    onTertiaryContainer: Color = U,
    background: Color = U,
    onBackground: Color = U,
    surface: Color = U,
    onSurface: Color = U,
    surfaceVariant: Color = U,
    onSurfaceVariant: Color = U,
    surfaceTint: Color = U,
    inverseSurface: Color = U,
    inverseOnSurface: Color = U,
    error: Color = U,
    onError: Color = U,
    errorContainer: Color = U,
    onErrorContainer: Color = U,
    outline: Color = U,
    outlineVariant: Color = U,
    scrim: Color = U,
    surfaceBright: Color = U,
    surfaceDim: Color = U,
    surfaceContainer: Color = U,
    surfaceContainerHigh: Color = U,
    surfaceContainerHighest: Color = U,
    surfaceContainerLow: Color = U,
    surfaceContainerLowest: Color = U,
): ColorScheme = lightColorScheme(
    primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
    secondary, onSecondary, secondaryContainer, onSecondaryContainer,
    tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
    background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint,
    inverseSurface, inverseOnSurface, error, onError, errorContainer, onErrorContainer,
    outline, outlineVariant, scrim, surfaceBright, surfaceDim,
    surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest,
)

/** API 31+ only in the real artifact (`@RequiresApi(31)`). */
fun dynamicLightColorScheme(context: Context): ColorScheme = lightColorScheme()

/** API 31+ only in the real artifact (`@RequiresApi(31)`). */
fun dynamicDarkColorScheme(context: Context): ColorScheme = darkColorScheme()

@Immutable
class Typography(
    val displayLarge: TextStyle = TextStyle.Default,
    val displayMedium: TextStyle = TextStyle.Default,
    val displaySmall: TextStyle = TextStyle.Default,
    val headlineLarge: TextStyle = TextStyle.Default,
    val headlineMedium: TextStyle = TextStyle.Default,
    val headlineSmall: TextStyle = TextStyle.Default,
    val titleLarge: TextStyle = TextStyle.Default,
    val titleMedium: TextStyle = TextStyle.Default,
    val titleSmall: TextStyle = TextStyle.Default,
    val bodyLarge: TextStyle = TextStyle.Default,
    val bodyMedium: TextStyle = TextStyle.Default,
    val bodySmall: TextStyle = TextStyle.Default,
    val labelLarge: TextStyle = TextStyle.Default,
    val labelMedium: TextStyle = TextStyle.Default,
    val labelSmall: TextStyle = TextStyle.Default,
)

@Immutable
class Shapes(
    val extraSmall: Shape = RoundedCornerShape(),
    val small: Shape = RoundedCornerShape(),
    val medium: Shape = RoundedCornerShape(),
    val large: Shape = RoundedCornerShape(),
    val extraLarge: Shape = RoundedCornerShape(),
)

@Composable
fun MaterialTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    shapes: Shapes = MaterialTheme.shapes,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) {
    content()
}

object MaterialTheme {
    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = lightColorScheme()

    val typography: Typography
        @Composable @ReadOnlyComposable get() = Typography()

    val shapes: Shapes
        @Composable @ReadOnlyComposable get() = Shapes()
}
