@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// --- text & icons ----------------------------------------------------------

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = TextStyle.Default,
) {
}

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
}

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
}

// --- containers ------------------------------------------------------------

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShapeCompat,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: Any? = null,
    content: @Composable () -> Unit,
) {
    content()
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: Any? = null,
    content: @Composable () -> Unit,
) {
    content()
}

internal val RectangleShapeCompat: Shape = androidx.compose.ui.graphics.RectangleShape

@Immutable
class CardColors internal constructor()

@Immutable
class CardElevation internal constructor()

object CardDefaults {
    @Composable
    fun cardColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): CardColors = CardColors()

    @Composable
    fun elevatedCardColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): CardColors = CardColors()

    @Composable
    fun outlinedCardColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
    ): CardColors = CardColors()

    @Composable
    fun elevatedCardElevation(
        defaultElevation: Dp = 0.dp,
        pressedElevation: Dp = 0.dp,
        focusedElevation: Dp = 0.dp,
        hoveredElevation: Dp = 0.dp,
        draggedElevation: Dp = 0.dp,
        disabledElevation: Dp = 0.dp,
    ): CardElevation = CardElevation()

    @Composable
    fun outlinedCardElevation(defaultElevation: Dp = 0.dp): CardElevation = CardElevation()

    @Composable
    fun cardElevation(
        defaultElevation: Dp = 0.dp,
        pressedElevation: Dp = 0.dp,
        focusedElevation: Dp = 0.dp,
        hoveredElevation: Dp = 0.dp,
        draggedElevation: Dp = 0.dp,
        disabledElevation: Dp = 0.dp,
    ): CardElevation = CardElevation()
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShapeCompat,
    colors: CardColors = CardColors(),
    elevation: CardElevation = CardElevation(),
    border: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    colors: CardColors = CardColors(),
    elevation: CardElevation = CardElevation(),
    border: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
}

@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = Color.Unspecified,
) {
}

@Composable
fun VerticalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = Color.Unspecified,
) {
}

@Immutable
class ListItemColors internal constructor()

object ListItemDefaults {
    @Composable
    fun colors(
        containerColor: Color = Color.Unspecified,
        headlineColor: Color = Color.Unspecified,
        leadingIconColor: Color = Color.Unspecified,
        overlineColor: Color = Color.Unspecified,
        supportingColor: Color = Color.Unspecified,
        trailingIconColor: Color = Color.Unspecified,
    ): ListItemColors = ListItemColors()
}

@Composable
fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    colors: ListItemColors = ListItemColors(),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
) {
}

// --- buttons ---------------------------------------------------------------

@Immutable
class ButtonColors internal constructor()

@Immutable
class ButtonElevation internal constructor()

object ButtonDefaults {
    @Composable
    fun buttonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): ButtonColors = ButtonColors()

    @Composable
    fun textButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
    ): ButtonColors = ButtonColors()

    @Composable
    fun filledTonalButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
    ): ButtonColors = ButtonColors()

    @Composable
    fun outlinedButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
    ): ButtonColors = ButtonColors()

    val ContentPadding: PaddingValues get() = PaddingValues.Zero
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    colors: ButtonColors = ButtonColors(),
    elevation: ButtonElevation? = null,
    border: Any? = null,
    contentPadding: PaddingValues = PaddingValues.Zero,
    content: @Composable RowScope.() -> Unit,
) {
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    colors: ButtonColors = ButtonColors(),
    elevation: ButtonElevation? = null,
    border: Any? = null,
    contentPadding: PaddingValues = PaddingValues.Zero,
    content: @Composable RowScope.() -> Unit,
) {
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    colors: ButtonColors = ButtonColors(),
    elevation: ButtonElevation? = null,
    border: Any? = null,
    contentPadding: PaddingValues = PaddingValues.Zero,
    content: @Composable RowScope.() -> Unit,
) {
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    colors: ButtonColors = ButtonColors(),
    elevation: ButtonElevation? = null,
    border: Any? = null,
    contentPadding: PaddingValues = PaddingValues.Zero,
    content: @Composable RowScope.() -> Unit,
) {
}

@Immutable
class IconButtonColors internal constructor()

object IconButtonDefaults {
    @Composable
    fun iconButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): IconButtonColors = IconButtonColors()

    @Composable
    fun filledIconButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): IconButtonColors = IconButtonColors()

    @Composable
    fun filledTonalIconButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
    ): IconButtonColors = IconButtonColors()

    @Composable
    fun outlinedIconButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
    ): IconButtonColors = IconButtonColors()
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonColors(),
    content: @Composable () -> Unit,
) {
}

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    colors: IconButtonColors = IconButtonColors(),
    content: @Composable () -> Unit,
) {
}

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShapeCompat,
    colors: IconButtonColors = IconButtonColors(),
    content: @Composable () -> Unit,
) {
}

@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShapeCompat,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
}

@Composable
fun ExtendedFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShapeCompat,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
}

// --- selection controls ----------------------------------------------------

@Immutable
class SwitchColors internal constructor()

object SwitchDefaults {
    @Composable
    fun colors(
        checkedThumbColor: Color = Color.Unspecified,
        checkedTrackColor: Color = Color.Unspecified,
        uncheckedThumbColor: Color = Color.Unspecified,
        uncheckedTrackColor: Color = Color.Unspecified,
    ): SwitchColors = SwitchColors()
}

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchColors(),
) {
}

@Immutable
class SliderColors internal constructor()

object SliderDefaults {
    @Composable
    fun colors(
        thumbColor: Color = Color.Unspecified,
        activeTrackColor: Color = Color.Unspecified,
        inactiveTrackColor: Color = Color.Unspecified,
    ): SliderColors = SliderColors()
}

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderColors(),
) {
}

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
}

@Composable
fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
}

// --- progress --------------------------------------------------------------

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Unspecified,
) {
}

@Composable
fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Unspecified,
) {
}

@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
) {
}

@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
) {
}

// --- chips -----------------------------------------------------------------

@Immutable
class ChipColors internal constructor()

@Immutable
class SelectableChipColors internal constructor()

@Immutable
class ChipBorder internal constructor()

@Immutable
class BorderStroke internal constructor()

object AssistChipDefaults {
    @Composable
    fun assistChipColors(
        containerColor: Color = Color.Unspecified,
        labelColor: Color = Color.Unspecified,
        leadingIconContentColor: Color = Color.Unspecified,
        trailingIconContentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledLabelColor: Color = Color.Unspecified,
        disabledLeadingIconContentColor: Color = Color.Unspecified,
        disabledTrailingIconContentColor: Color = Color.Unspecified,
    ): ChipColors = ChipColors()

    @Composable
    fun assistChipBorder(enabled: Boolean, borderColor: Color = Color.Unspecified, borderWidth: Dp = 1.dp): BorderStroke? = null
}

object FilterChipDefaults {
    @Composable
    fun filterChipColors(
        containerColor: Color = Color.Unspecified,
        labelColor: Color = Color.Unspecified,
        selectedContainerColor: Color = Color.Unspecified,
        selectedLabelColor: Color = Color.Unspecified,
    ): SelectableChipColors = SelectableChipColors()
}

@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = RectangleShapeCompat,
    colors: ChipColors = ChipColors(),
    border: BorderStroke? = null,
) {
}

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = RectangleShapeCompat,
    colors: SelectableChipColors = SelectableChipColors(),
    border: BorderStroke? = null,
) {
}

// --- dialogs & menus -------------------------------------------------------

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RectangleShapeCompat,
    containerColor: Color = Color.Unspecified,
) {
}

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: androidx.compose.ui.unit.DpSize? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
}

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues.Zero,
) {
}

@ExperimentalMaterial3Api
@JvmInline
value class MenuAnchorType private constructor(private val name: String) {
    companion object {
        val PrimaryNotEditable = MenuAnchorType("PrimaryNotEditable")
        val PrimaryEditable = MenuAnchorType("PrimaryEditable")
        val SecondaryEditable = MenuAnchorType("SecondaryEditable")
    }
}

@ExperimentalMaterial3Api
@Stable
interface ExposedDropdownMenuBoxScope {
    fun Modifier.menuAnchor(type: MenuAnchorType, enabled: Boolean = true): Modifier = this

    @Composable
    fun ExposedDropdownMenu(
        expanded: Boolean,
        onDismissRequest: () -> Unit,
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit,
    ) {
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private object ExposedDropdownMenuBoxScopeImpl : ExposedDropdownMenuBoxScope

@ExperimentalMaterial3Api
@Composable
fun ExposedDropdownMenuBox(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ExposedDropdownMenuBoxScope.() -> Unit,
) {
    ExposedDropdownMenuBoxScopeImpl.content()
}

@ExperimentalMaterial3Api
object ExposedDropdownMenuDefaults {
    @Composable
    fun TrailingIcon(expanded: Boolean) {}

    @Composable
    fun textFieldColors(): TextFieldColors = TextFieldColors()

    @Composable
    fun outlinedTextFieldColors(): TextFieldColors = TextFieldColors()
}

// --- text fields -----------------------------------------------------------

@Immutable
class TextFieldColors internal constructor()

object TextFieldDefaults {
    @Composable
    fun colors(
        focusedTextColor: Color = Color.Unspecified,
        unfocusedTextColor: Color = Color.Unspecified,
        focusedContainerColor: Color = Color.Unspecified,
        unfocusedContainerColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        focusedIndicatorColor: Color = Color.Unspecified,
        unfocusedIndicatorColor: Color = Color.Unspecified,
        disabledIndicatorColor: Color = Color.Unspecified,
        cursorColor: Color = Color.Unspecified,
    ): TextFieldColors = TextFieldColors()
}

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = RectangleShapeCompat,
    colors: TextFieldColors = TextFieldColors(),
) {
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = RectangleShapeCompat,
    colors: TextFieldColors = TextFieldColors(),
) {
}

// --- scaffold, app bars, navigation ---------------------------------------

@Stable
class SnackbarHostState {
    suspend fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ): SnackbarResult = SnackbarResult.Dismissed
}

enum class SnackbarDuration { Short, Long, Indefinite }

enum class SnackbarResult { Dismissed, ActionPerformed }

@Composable
fun SnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {}

object ScaffoldDefaults {
    val contentWindowInsets: WindowInsets
        get() = WindowInsets.systemBars
}

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
}

@JvmInline
value class FabPosition private constructor(private val value: Int) {
    companion object {
        val Start = FabPosition(0)
        val Center = FabPosition(1)
        val End = FabPosition(2)
    }
}

@ExperimentalMaterial3Api
@Stable
interface TopAppBarScrollBehavior {
    val nestedScrollConnection: Any
}

@Immutable
class TopAppBarColors internal constructor()

@ExperimentalMaterial3Api
object TopAppBarDefaults {
    @Composable
    fun topAppBarColors(
        containerColor: Color = Color.Unspecified,
        scrolledContainerColor: Color = Color.Unspecified,
        navigationIconContentColor: Color = Color.Unspecified,
        titleContentColor: Color = Color.Unspecified,
        actionIconContentColor: Color = Color.Unspecified,
    ): TopAppBarColors = TopAppBarColors()

    @Composable
    fun enterAlwaysScrollBehavior(): TopAppBarScrollBehavior = ScrollBehaviorImpl

    @Composable
    fun pinnedScrollBehavior(): TopAppBarScrollBehavior = ScrollBehaviorImpl

    @Composable
    fun exitUntilCollapsedScrollBehavior(): TopAppBarScrollBehavior = ScrollBehaviorImpl
}

@OptIn(ExperimentalMaterial3Api::class)
private object ScrollBehaviorImpl : TopAppBarScrollBehavior {
    override val nestedScrollConnection: Any = Unit
}

@ExperimentalMaterial3Api
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    colors: TopAppBarColors = TopAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
}

@ExperimentalMaterial3Api
@Composable
fun CenterAlignedTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
}

@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable RowScope.() -> Unit,
) {
}

@Immutable
class NavigationBarItemColors internal constructor()

object NavigationBarItemDefaults {
    @Composable
    fun colors(
        selectedIconColor: Color = Color.Unspecified,
        selectedTextColor: Color = Color.Unspecified,
        indicatorColor: Color = Color.Unspecified,
        unselectedIconColor: Color = Color.Unspecified,
        unselectedTextColor: Color = Color.Unspecified,
    ): NavigationBarItemColors = NavigationBarItemColors()
}

@Composable
fun RowScope.NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    alwaysShowLabel: Boolean = true,
    colors: NavigationBarItemColors = NavigationBarItemColors(),
) {
}

// --- swipe to dismiss ------------------------------------------------------

enum class SwipeToDismissBoxValue { StartToEnd, EndToStart, Settled }

@Stable
class SwipeToDismissBoxState internal constructor() {
    val currentValue: SwipeToDismissBoxValue = SwipeToDismissBoxValue.Settled
    val targetValue: SwipeToDismissBoxValue = SwipeToDismissBoxValue.Settled
    val dismissDirection: SwipeToDismissBoxValue = SwipeToDismissBoxValue.Settled
    val progress: Float = 0f
    suspend fun reset() {}
    suspend fun snapTo(targetValue: SwipeToDismissBoxValue) {}
}

@ExperimentalMaterial3Api
@Composable
fun rememberSwipeToDismissBoxState(
    initialValue: SwipeToDismissBoxValue = SwipeToDismissBoxValue.Settled,
    confirmValueChange: (SwipeToDismissBoxValue) -> Boolean = { true },
    positionalThreshold: (totalDistance: Float) -> Float = { it * 0.5f },
): SwipeToDismissBoxState = SwipeToDismissBoxState()

@ExperimentalMaterial3Api
@Composable
fun SwipeToDismissBox(
    state: SwipeToDismissBoxState,
    backgroundContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    enableDismissFromStartToEnd: Boolean = true,
    enableDismissFromEndToStart: Boolean = true,
    gesturesEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
}

// --- tooltips --------------------------------------------------------------

@Stable
class TooltipState internal constructor() {
    val isVisible: Boolean = false
    suspend fun show() {}
    fun dismiss() {}
}

@ExperimentalMaterial3Api
@Composable
fun rememberTooltipState(
    initialIsVisible: Boolean = false,
    isPersistent: Boolean = false,
): TooltipState = TooltipState()

@Stable
interface TooltipScope

private object TooltipScopeImpl : TooltipScope

@Stable
interface TooltipPositionProvider

@ExperimentalMaterial3Api
object TooltipDefaults {
    val rememberPlainTooltipPositionProvider: TooltipPositionProvider
        @Composable get() = object : TooltipPositionProvider {}

    @Composable
    fun rememberPlainTooltipPositionProvider(spacingBetweenTooltipAndAnchor: Dp = 4.dp): TooltipPositionProvider =
        object : TooltipPositionProvider {}

    @Composable
    fun rememberRichTooltipPositionProvider(spacingBetweenTooltipAndAnchor: Dp = 4.dp): TooltipPositionProvider =
        object : TooltipPositionProvider {}
}

@ExperimentalMaterial3Api
@Composable
fun TooltipScope.PlainTooltip(
    modifier: Modifier = Modifier,
    caretSize: androidx.compose.ui.unit.DpSize? = null,
    shape: Shape = RectangleShapeCompat,
    contentColor: Color = Color.Unspecified,
    containerColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
}

@ExperimentalMaterial3Api
@Composable
fun TooltipBox(
    positionProvider: TooltipPositionProvider,
    tooltip: @Composable TooltipScope.() -> Unit,
    state: TooltipState,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    enableUserInput: Boolean = true,
    content: @Composable () -> Unit,
) {
}

// --- misc ------------------------------------------------------------------

object LocalContentColor {
    val current: Color
        @Composable get() = Color.Unspecified
}
