# Offline type-check for `:app`

This directory is **not a Gradle source set**. It exists so the Android application module can be
type-checked on a machine with no Android SDK (the engine and the CLI build anywhere; `dl.google.com`
is unreachable in the authoring sandbox, so the Android Gradle Plugin cannot be resolved and
`settings.gradle.kts` leaves `:app` out of the build).

It contains 70 hand-written Kotlin files (~5 000 lines) declaring the *signatures* of the platform and
library APIs the app uses — `android.*`, `androidx.*` (activity, annotation, core, lifecycle,
navigation, palette, room, datastore, work), Compose (runtime, ui, foundation, material3, material
icons), Media3 (common + session), Coil 2 and the two Guava types Media3 exposes.

Run it with [`app/typecheck.sh`](../../typecheck.sh):

```
app/typecheck.sh          # data, data.db, data.prefs, di, playback + app/src/test  -> must be clean
app/typecheck.sh ui       # the above plus ui/**, MainActivity, MuiscApplication    -> best effort
```

The harness invokes the Kotlin 2.1.20 compiler (`kotlin-compiler-embeddable`, the same version the
Gradle build uses) directly, with:

* these stubs,
* a `dev.muisc.app.R` object **generated from the real `app/src/main/res`** (so every `R.string.*`,
  `R.drawable.*`, `R.plurals.*`, `R.color.*` and `R.style.*` reference in the sources is checked
  against the resources that actually exist),
* and the **real compiled engine classes** (`engine/*/build/classes/kotlin/main`) — not stubs.

It needs `./gradlew :engine:player:compileKotlin` (and siblings) to have run once, which also
populates the Gradle module cache the script pulls kotlin-stdlib / coroutines / serialization /
kotlin-test / junit-jupiter-api from.

## What a clean run proves

* Every file under `app/src/main/kotlin` and `app/src/test/kotlin` parses and resolves.
* Every use of the **engine** API — `EngineController`, `ProgramPlayer`, `TransitionCoordinator`,
  `TransitionPlanner`, `TransitionRenderer`, `AnalysisCache`, `TrackAnalysis`, `TransitionPrefs`,
  `PlaybackProgram`/`Segment`, `ArtifactMetrics`, `WavIo`, … — is type-correct against the real,
  tested classes: names, arities, argument and return types, nullability, `suspend`-ness, interface
  conformance (every `override` really overrides something, every abstract member is implemented).
* Every cross-package call *inside* `:app` is consistent: the UI, the DI graph, the media-session
  adapter and the controller/coordinator packages agree on names, arities and types.
* Every resource id the code references exists in `res/`.
* Kotlin opt-in propagation is real for the Compose markers: `ExperimentalMaterial3Api`,
  `ExperimentalMaterial3ExpressiveApi` and `ExperimentalFoundationApi` are declared with
  `@RequiresOptIn`, so a composable that uses `TopAppBar`, `TopAppBarScrollBehavior`,
  `SwipeToDismissBox`, `ExposedDropdownMenuBox`, `MenuAnchorType` or `TooltipBox` without opting in
  is an error here exactly as it would be on a real build.

## What it does NOT prove

Read this list before trusting a green run.

1. **The stub signatures are written from memory.** They are close to the real Android / AndroidX /
   Media3 / Coil APIs, but they are not generated from `android.jar` or from the AAR class files. A
   stub that is wrong in a way the app happens to match will hide a real error; a stub that is
   missing an overload will produce a false one. Anything that fails on a real SDK build and looks
   like a platform signature mismatch is a stub bug, not necessarily an app bug.
2. **Compose is not really compiled.** The Compose compiler plugin is not applied, so `@Composable`
   is an ordinary annotation here: composable-calling-context rules, `remember`/state inference,
   recomposition inference, stability and the `Modifier` chain's real generic machinery are *not*
   checked. `ui/**` is a best-effort pass.
3. **Room does nothing.** The `androidx.room` annotations are inert. Room's KSP processor — SQL
   parsing, column/entity validation, `@Query` return-type checks, index and foreign-key checks,
   schema export — has never run over `data/db`. Expect the first real build to be the first time
   `Daos.kt` is validated.
4. **No resource compilation, no manifest merge, no lint.** `aapt2` has not run; the generated `R`
   only mirrors the names found in `res/values` and `res/drawable*`/`mipmap*`. Resource *contents*
   (a `%d` in a format string, a missing `@style` parent, a bad vector path) and everything in
   `AndroidManifest.xml` are unchecked, as are all lint rules (`NewApi`, `InlinedApi`,
   `UnusedResources`, …).
5. **No KSP, no R8/ProGuard, no packaging, no dependency resolution.** The real versions in
   `gradle/libs.versions.toml` are never resolved, so an API that moved between the assumed version
   and the pinned one will only show up on a real build.
6. **Nothing is executed.** This is a type-check. The app's own unit tests (`FolderTreeTest`,
   `GaplessTagParserTest`) are pure Kotlin and do run — see `docs/ANDROID_BUILD_NOTES.md` — but no
   Android behaviour is exercised.

## Maintaining the stubs

When the app starts using a new platform API, add its signature here rather than changing the app to
fit an incomplete stub. Match the real signature as faithfully as you can, including nullability and
the marker annotations (`@RequiresOptIn` markers matter: they are what catches missing `@OptIn`s).
Once the project builds against a real SDK this whole directory can be deleted — it is a scaffold,
not part of the app.
