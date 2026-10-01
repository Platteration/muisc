# Android app: build notes and first-build checklist

The `:app` module was written on a machine with no Android SDK (`dl.google.com` unreachable), by four
agents working in parallel and without sight of each other. It has therefore **never been compiled by
the Android toolchain**. What it *has* been through is a full offline type-check against the real,
tested engine classes plus a hand-written stub set for the platform APIs — see
[`app/src/typecheck-stubs/README.md`](../app/src/typecheck-stubs/README.md) for exactly what that
does and does not cover.

This file is the single checklist for the first real build. §1 says what is already known-good, §2
lists what the type-check fixed, §3 is what still has to be checked on an SDK, §4 is the set of real
gaps found by reading the code that no compiler will tell you about. §6 describes the DJ customization
(recipes, presets, styles, pins, ratings, the next-transition override) and §7 is its on-device checklist.

---

## 0. How to build

1. Android Studio (Ladybug or newer) with SDK Platform 35 + build-tools.
2. `local.properties` with `sdk.dir=/path/to/Android/sdk`, or `ANDROID_HOME` in the environment.
   `settings.gradle.kts` includes `:app` **only** when it finds one, so engine-only builds keep
   working without an SDK (and never touch `dl.google.com`).
3. `./gradlew :app:assembleDebug`.

To re-run the offline type-check (no SDK needed):

```
./gradlew :engine:audio:classes :engine:dsp:classes :engine:analysis:classes \
          :engine:transitions:classes :engine:metrics:classes :engine:player:classes
                                         # once: engine classes + resources (the built-in recipes)
app/typecheck.sh                         # non-UI packages + unit tests  -> must be clean
app/typecheck.sh ui                      # everything                    -> currently clean too
app/typecheck.sh test                    # core, then runs app/src/test  -> must pass
```

`classes` (not just `compileKotlin`) matters since the recipes: it also runs `processResources`, and `test`
puts `engine/*/build/resources/main` on the run-time classpath so the built-in recipes load through the class
loader the way they do in the APK.

---

## 1. What the type-check proves today

Both modes are **clean — zero errors** over all 90 Kotlin files in `app/src/main/kotlin` and
`app/src/test/kotlin` (the harness runs the compiler with `-nowarn`, so warnings are not reported).

* Every use of the engine API is type-correct against the **real compiled engine classes**
  (`dev.muisc.player`, `dev.muisc.transitions`, `dev.muisc.analysis`, `dev.muisc.audio`,
  `dev.muisc.metrics`, `dev.muisc.dsp`): names, arities, argument/return types, nullability,
  `suspend`-ness, every `override`, every abstract member.
* The four packages agree with each other. `PlaybackService` ↔ `EngineGraph` ↔ `EngineControllerImpl`
  ↔ `QueueManager` ↔ `AndroidAnalysisService` ↔ `TransitionLabImpl` ↔ `CustomizationImpl` ↔
  `DjCustomization`, and `ui/**` ↔ `di/AppGraph` ↔ `data/**`, all resolve. The two frozen contracts
  (`playback/EngineController.kt`, `playback/TransitionLabApi.kt`) are implemented in full by
  `EngineControllerImpl`, `NoOpEngineController`, `DelegatingEngineController`, `TransitionLabImpl`,
  `NoOpTransitionLab` and `DelegatingTransitionLab`; the new `playback/CustomizationApi.kt` by
  `CustomizationImpl`, `NoOpCustomization` and `DelegatingCustomization`.
* The DJ customization is type-correct against the real engine `recipe` and `custom` packages
  (`RecipeLibrary`, `RecipeCatalog`, `RecipeCodec`, `RecipeValidator`, `RecipeResolver`, `UserProfile`,
  `PlannerCustomization`, `PresetResolution`, `StyleProfile`, `PairPin`, `FeedbackLearner`, the planner's
  `planExplained`).
* Every `R.string` / `R.drawable` / `R.plurals` / `R.color` / `R.style` reference resolves against a
  name that really exists in `app/src/main/res` (the harness generates `R` from the resource files).
* The app's own unit tests compile **and pass** (`app/typecheck.sh test`): 48 tests — `FolderTreeTest`,
  `GaplessTagParserTest`, and for the DJ customization `DjCustomizationTest` (21), `QueueReplanIdsTest` (3),
  `ReplanNextEdgeTest` (1, the real `TransitionCoordinator`) and `AbVoteTest` (5) — run on the JUnit 5 platform against the
  same classes and the engine's real resources.
* Kotlin opt-in propagation is real for the Compose markers, which is how §2's biggest batch of
  errors was found.

Not proven: Room's SQL, Compose semantics, resource contents, the manifest, lint, KSP, R8, and the
fidelity of the stub signatures themselves. Those are §3.

---

## 2. Previously-flagged items now resolved

From the four agents' own hand-over notes:

| Flagged | Status |
| --- | --- |
| `app/build.gradle.kts` may lack `useJUnitPlatform()`, silently skipping `FolderTreeTest` | **Resolved.** The line is present; the 18 tests were executed and pass. |
| `PlaybackService` must call `AppGraph.installPlayback` / `uninstallPlayback` | **Resolved.** `onCreate` installs, `onDestroy` uninstalls and releases. |
| `EngineControllerImpl` was constructed with a two-argument constructor that does not exist; two competing `AudioFocusHandler`s; a reflective shim for ducking | **Resolved** (commit "Wire the Android playback packages together at their integration seams"): the service builds the engine through `EngineGraph.create`, audio focus has one owner, and the controller implements `DuckableEngine` directly. |
| `MainActivity` imports `com.google.common.util.concurrent.ListenableFuture` | Type-checks. `MediaController.Builder.buildAsync()` really returns one and media3-session exposes Guava as `api`, so no extra dependency should be needed — but this is the one place the app depends on a transitive artifact, so confirm it resolves (§3). |
| `LibraryViewModel.searchResults` inference through `flatMapLatest` | **Resolved** — infers correctly, no `flowOf` wrapper needed. |
| `DelegatingEngineController` needs `@OptIn(ExperimentalCoroutinesApi::class)` for `flatMapLatest` | **Resolved** — present, and now actually enforced by the checker. |
| `Album.year` / `Song.year` are `Int`, album detail's `takeIf` | **Resolved.** |
| `SettingsRepository.updateUi(transform)` / `UiPrefs` field types / `AppGraph.requestScan`/`scanStatus` / `Song.albumArtist` nullable — names the UI consumed from the sibling package | **Resolved**, all resolve. |
| `TrackAnalysisEntity` composite key `(fingerprint, sampleRate, version)` vs a fingerprint-only PK | **Resolved by construction** — it matches `AnalysisCache.get(fingerprint, sampleRate, version)`, which `RoomAnalysisCache` implements against the real engine interface. |
| `NowPlayingScreen` Palette extraction via `coil.imageLoader` + `ImageRequest.size(200).allowHardware(false)` | Type-checks against Coil 2.7 signatures; still version-sensitive (§3). |

### Defects the type-check itself found and fixed

1. **14 missing `@OptIn(ExperimentalMaterial3Api::class)` call sites** — hard compile errors on a real
   build. `LibraryTopBar` exposes `scrollBehavior: TopAppBarScrollBehavior?` in its signature, and
   `TopAppBarScrollBehavior` is `@ExperimentalMaterial3Api` in material3 1.3.x, so **every screen that
   calls it** needs the opt-in: `HomeScreen`, `SongsScreen`, `AlbumsScreen`, `ArtistsScreen`,
   `GenresScreen`, `PlaylistsScreen`, `FoldersScreen`, `ArtistDetailScreen`, `GenreDetailScreen`,
   `PlaylistDetailScreen` (both screens), `TransitionLabScreen`, `SettingsScreen`, `QueueScreen`. On
   top of that `QueueScreen.QueueItem` uses `SwipeToDismissBox` / `rememberSwipeToDismissBoxState`,
   also experimental. All fixed with a per-function `@OptIn`, matching the style already used in
   `SongActions`, `SearchScreen`, `AlbumDetailScreen` and `ParamEditor`.
   *This class of error is now checked*: the stub markers carry `@RequiresOptIn`, so a future missing
   opt-in fails `app/typecheck.sh`.
2. **`EngineControllerImpl.play()` ignored the audio-focus result.** Both its own KDoc and
   `AudioFocusHandler.request()` ("Returns false when the request was denied — do not start playing")
   promised that playback only starts with focus; the return value was discarded, so the app would
   have opened the sink over an active phone call. It now reports an error and stays paused.
3. **Duplicate `DUCK_DB` constant** in `EngineControllerImpl`'s companion, unused and shadowing the
   one in `AudioFocusHandler` that actually drives ducking. Removed.
4. **Two folder-tree implementations.** `ui/viewmodel/LibraryViewModel.kt` declared its own
   `FolderNode` + `buildFolderTree` + `find`, and `FoldersScreen` re-implemented root-chain collapsing
   by hand — while `data/FolderTree.kt` (generic, unit-tested, collapses chains at *every* level) and
   `LibraryRepository.folders()` sat unused. The UI copy is gone; `LibraryViewModel.folderTree` is now
   `repo.folders()` and `FoldersScreen` uses `FolderNode<Song>` (`items` / `allItems` / `totalCount`).
   Folder names now collapse everywhere, not just at the root.
5. **"Analyse only while charging" did nothing until the next cold start.** The periodic
   `AnalysisWorker` is enqueued once, by `EngineGraph.create`, with the value read at that moment;
   flipping the switch in Settings never re-enqueued it. `MuiscApplication` now observes the pref and
   re-enqueues (`ExistingPeriodicWorkPolicy.UPDATE` rewrites the standing job's constraints in place).

Stub-side fixes made while doing this: `MediaController` / `SessionToken` were missing entirely (so
`MainActivity` never resolved), `ListenableFuture` did not extend `java.util.concurrent.Future` (so
`addListener` / `get` / `releaseFuture(Future<out MediaController>)` did not check), and the Compose
experimental markers were plain annotations.

---

## 3. Still to check on a real Android SDK

Ordered by how likely they are to stop the first build.

1. **Room 2.6.1 against KSP 2.1.20-1.0.32.** The catalogue pins Room 2.6.1 while KSP is on the
   Kotlin 2.1.20 line, where KSP2 is the default; Room's processor only gained KSP2 support in the
   2.7 line. If `:app:kspDebugKotlin` fails with a KSP/Room internal error, the fix is either
   `ksp.useKSP2=false` in `gradle.properties` or bumping `room = "2.7.x"` (the only source change that
   needs is `fallbackToDestructiveMigration()` → `fallbackToDestructiveMigration(dropAllTables = true)`
   in `MuiscDatabase.build`, the no-arg form being deprecated there). Not changed here because it
   cannot be verified offline.
2. **Room's SQL validation — the whole of `data/db/Daos.kt` is unverified.** Nothing in the offline
   pass parses a `@Query`. Watch in particular: the aliased-subquery joins in `SongDao.mostPlayed` /
   `recentlyPlayed`, the `songs.genre = genres.name` join in `SongDao.ofGenre`, the
   `playlist_entries` join in `PlaylistDao.songsOf`, the non-suspend `UPDATE … RETURNING Int` in
   `SongDao.markAnalysedBySourceBlocking`, the deliberately blocking `AnalysisDao` methods, and
   `PlaylistDao.maxPosition(): Int?` (`SELECT MAX(...)` over an empty table). An "unused Converters
   class" complaint is a warning, not an error.
3. **Room schema export.** `room.schemaLocation` is set, so the first build writes
   `app/schemas/dev.muisc.app.data.db.MuiscDatabase/1.json`. Commit it.
4. **Compose compilation proper.** `ui/**` was checked without the Compose compiler plugin, so
   composable-calling-context, `remember`/state inference, `Modifier` chain generics and stability are
   all unverified. Expect the first `:app:compileDebugKotlin` to surface Compose-only errors in the
   screens, particularly `NowPlayingScreen`, `TransitionLabScreen` and `WaveformStrip`/`Canvas`, and
   `ui/queue/ReorderHelper.kt`, which declares a `@Composable` `Modifier` extension.
5. **Library versions vs assumed signatures.** The stubs were written for material3 1.3.x (BOM
   2025.01.01), lifecycle 2.8.7, navigation 2.8.5, Media3 1.5.1, Coil 2.7, DataStore 1.1.2, WorkManager
   2.10. Signature-sensitive spots: `Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)` in
   `ParamEditor` (the 1.3 form; older material3 wants the deprecated no-arg `menuAnchor()`),
   `ColorScheme.copy(surfaceContainer*)` in `Theme.kt` (needs ≥ 1.2), the ~50 `Icons.Rounded.*` /
   `Icons.AutoMirrored.Rounded.*` names from `material-icons-extended`, `ScaffoldDefaults.contentWindowInsets`,
   and Coil's `context.imageLoader` / `ImageResult.drawable` / `ImageRequest.Builder.size(Int)`.
6. **`MainActivity`'s Guava import.** `ListenableFuture` comes transitively from media3-session. If it
   does not resolve, add `implementation("com.google.guava:guava")` — or delete the `MediaController`
   connect block entirely: `AppGraph`'s delegating controller already starts the service on the first
   command, so nothing else depends on it.
7. **Manifest, resources and lint.** None of `AndroidManifest.xml` was validated. Check: the
   `MediaLibraryService` intent filter and `foregroundServiceType="mediaPlayback"`, `Theme.Muisc`
   (`android:Theme.Material.NoActionBar`, so no AppCompat needed), the adaptive launcher icon
   (`mipmap-anydpi-v26` only — fine at `minSdk = 26`), and the `%d`/`%s` placeholders in
   `strings.xml` against each `stringResource(id, arg)` call site. Lint will emit `InlinedApi` on the
   `MediaStore` column constants that `MediaStoreScanner` guards with `Build.VERSION` checks
   (`ALBUM_ARTIST`/`GENRE` at 30, `RELATIVE_PATH` at 29, `getGeneration` at 30) — those are safe.
   No `backup_rules.xml` / `data_extraction_rules.xml` exist and nothing references them.
8. **Release build.** `release` has `isMinifyEnabled`/`isShrinkResources` on and only
   `proguard-rules.pro`'s kotlinx.serialization keeps. Verify a release APK actually starts: Room's
   generated `*_Impl` classes, `AnalysisWorker`'s WorkManager-reflected constructor and
   `Uri.parse`-driven Coil paths are the usual first casualties. Debug builds are unaffected.
9. **Runtime behaviour nothing here can touch**: `AudioTrack` in `ENCODING_PCM_FLOAT` at the device
   output rate, the `MediaCodec`/`MediaExtractor` decode path (`MediaCodecPcmStream`,
   `AndroidAudioDecoder`), gapless tag parsing on real files, MediaSession/Android Auto browse,
   WorkManager scheduling, and the audio-thread priority/underrun behaviour in `AudioTrackSink`.

---

## 4. Real gaps found by reading (no compiler will report these)

1. **"Keep album flow" only half works.** The Settings switch writes both
   `TransitionPrefs.keepAlbumFlowInShuffle` (engine, honoured) and `UiPrefs.keepAlbumFlowInPlaylists`
   (app-only, **read by nobody**), and its subtitle promises "Shuffle *and playlists*". The engine's
   `TransitionGating` returns `true` unconditionally for `PLAYLIST` and `QUEUE`, so a same-album pair
   inside a playlist always gets a transition. This cannot be fixed in `:app` — gating is per-edge
   inside the coordinator. It needs a `keepAlbumFlowInPlaylists` pref in
   `engine/transitions/.../PlaybackContext.kt` and one more branch in `TransitionGating`. Left as is
   rather than silently dropping the switch or faking it.
2. **`PowerModeMonitor.setStrictSaver` is never called.** `PowerMode.STRICT_SAVER` (the documented
   "no transitions on battery" user toggle, DESIGN §7.4) has no Settings UI, so only the automatic
   `SAVER` path (system power-save, or < 15 % and not charging) can ever fire. Either add the switch
   or drop the mode from the docs.
3. **Search never finds playlists, and the SQL search is dead code.** `LibraryViewModel` searches
   client-side over the hot library flows with its own `SearchResults` (songs/albums/artists), so
   `LibraryRepository.search` and the `search` queries in `SongDao`/`AlbumDao`/`ArtistDao`/`PlaylistDao`
   — which do include playlists, are indexed, and are limited — are unreachable. Two same-named
   `SearchResults` types now exist (`data` and `ui.viewmodel`), as do two `SongSort` enums
   (`data` and `ui.components`). Harmless to the compiler, confusing to a reader; pick one side.
4. **Lab export lands in app-private storage on API 26–28.** `TransitionLabApi.export` documents
   "the public Music/Muisc/Renders folder". On API 29+ that works through MediaStore; below it the
   code needs `WRITE_EXTERNAL_STORAGE`, which the manifest does not declare, so it always falls back
   to `getExternalFilesDir(...)`. Either declare the permission with `android:maxSdkVersion="28"` or
   relax the KDoc.
5. **`AnalysisWorker.enqueueNow` has no caller.** There is no "analyse my library now" action in
   Settings, only the periodic job. `getForegroundInfo()` is likewise unreachable while `expedited`
   defaults to false (correctly so — the manifest declares no `dataSync` foreground-service type).
6. **`EngineControllerImpl.transitionLog()`, `LibraryRepository.analysedCount()`/`songCount()` have no
   callers.** Settings shows no analysis progress and the Lab shows no decision log, although both
   sources exist. Small, additive UI work.
7. **`QueueManager.move` places the moved song in the *natural* order at the play-order index.** With
   shuffle on those indices are unrelated, so turning shuffle off after a drag gives an arbitrary (but
   valid — the index is coerced) position. Cosmetic; worth a test once the app runs.

---

## 5. Design notes worth keeping

* `AppGraph.engineController` / `AppGraph.lab` are process-stable delegating instances
  (`di/PlaybackBridge.kt`). The UI collects `engineController.state` once; when `PlaybackService`
  installs the real controller the flow switches over and commands issued before the service bound
  (a tap that started playback) are replayed in order from a bounded 32-entry buffer. The service is
  started by class name with plain `startService`; it promotes itself to foreground through Media3's
  default notification provider only once something plays.
* The scanner is incremental: `DATE_MODIFIED > last scan`, deletions reconciled with an id-only query,
  the blacklist applied to stored rows too, and album/artist/genre aggregates rebuilt in Kotlin inside
  one Room transaction. On API 30+ an unchanged `MediaStore.getGeneration` short-circuits the whole
  scan. Genres come from `MediaStore.Audio.Genres.Members` below API 30 and from the `GENRE` column
  above it; `Genre.id` is a stable hash of the normalised name, which is what the `genre/{id}` route
  and `SongDao.ofGenre`'s join rely on.
* `RoomAnalysisCache` keeps a 64-entry LRU in front of Room and flips `Song.hasAnalysis` by matching
  the analysis `sourceId` against `Song.uri` or `Song.path`. The playback layer passes
  `AudioSourceId(song.uri)` everywhere (`QueueManager.sourceOf`), so the indicator lines up.
* One audio thread at `THREAD_PRIORITY_URGENT_AUDIO` runs `render → interleave → write`; the blocking
  `AudioTrack` write is the clock. The coordinator gets its own single thread one notch below the UI,
  and analysis has two more (urgent / background). The controller never builds segments — the
  coordinator is the only writer of the program.
* The Lab runs the *same* planner, renderer and loader as playback, so what it auditions is what the
  coordinator would install and the exported `renderKey` is reproducible on the CLI.

---

## 6. DJ customization on the phone

What is wired, and how (the code is in `playback/DjCustomization.kt`, `playback/CustomizationImpl.kt`,
`playback/CustomizationApi.kt`, `ui/dj/**`, `ui/nowplaying/NextTransitionSheet.kt`, `ui/lab/LabExtras.kt`).

* **One profile directory**, `filesDir/dj`, laid out exactly like the CLI's `--profile-dir`: `presets/`,
  `styles/`, `recipes/`, `pins.json`, `feedback.json`, plus `style` (one line: the chosen style id, read
  synchronously when the engine starts so the first plan already has it). Nothing is created until something
  is saved. It is app-private storage, so Android's auto-backup rules apply to it as to every other file there
  (none are declared, see §3.7).
* **Registry** = `DefaultStrategyRegistry.default()` + one `recipe:<id>` per usable recipe
  (`RecipeCatalog` over `RecipeLibrary(filesDir/dj/recipes)`), held in a `LiveStrategyRegistry` that the
  planner, the renderer, the controller and the Lab share; importing, duplicating or deleting a recipe swaps
  its contents. Recipes are read (and validated at every knob setting) on a background thread after the
  service starts; until then — the first plan after a cold start — only the 14 built-in strategies compete.
* **Built-in recipes on Android.** They ship as Java resources in the `:engine:transitions` jar
  (`recipes/index.txt` + nine `.json` files) and are read with `RecipeLibrary::class.java.classLoader`. AGP
  packages the Java resources of JVM library dependencies into the APK, `packaging.resources.excludes` only
  drops two `META-INF` entries, and ART's app class loader serves APK entries through `getResourceAsStream`,
  so they should be found — **this is a belief, not a verified fact** (no device here). If they are not, the
  library reports "the built-in recipe index is missing" as a problem at the top of Settings → DJ and the
  app keeps working with the 14 strategies and the user's own recipes. The desktop test
  `builtInRecipesLoadThroughTheClassLoaderAndJoinTheRegistry` proves the same code path on the JVM.
* **Planner** = the engine's `DefaultTransitionPlanner` with a `PlannerCustomization` built from the profile:
  presets (built-in + `presets/`), pins (the in-memory one-off override first, then `pins.json`) and the
  learned multipliers (`feedback.json`). It sits behind `DjPlanner`. "Forget ratings" is
  `FileFeedbackStore.reset`: a read-modify-write under the same file lock as a rating, which empties the
  store's learner in place (the planner holds that learner), so a rating or skip saved at the same moment is
  neither lost nor brought back. The previous ratings file is kept as `feedback.json.bak`.
* **Prefs shaping.** The chosen style (`StyleProfile.apply`) and then every active preset
  (`PresetResolution.fold`) reshape the stored `TransitionPrefs` at the engine boundary only — in
  `EngineControllerImpl` and in `TransitionLabImpl` — the same order the CLI uses for `--style`. The stored
  prefs, what Settings shows and what `PlayerState.transitionPrefs` carries are never shaped, so a style is
  never baked into them. A recipe switched off lands in `prefs.disabledStrategies` (`recipe:<id>`); an
  active preset in `prefs.activePresets`; a style's preset replaces the user's for the same technique.
* **One-off override for the next transition** (Now Playing → "Next transition" chip → sheet). Built from two
  existing mechanisms: a *session pin* for the ordered pair current → next (in memory, keyed by both tracks'
  `TrackAnalysis.identity` and, because the coordinator may still hold the stand-in analysis it started the
  current track with, by that stand-in's `placeholder:<songId>` fingerprint), consulted by the planner before
  the stored pins; and a *re-plan of that edge*: `EngineControllerImpl.replanNext()` gives the next song a new
  engine-id generation (`QueueManager.queueItems(songs, generations)` → id `"<songId>~<n>"`) and re-installs
  the queue like any queue edit. The coordinator keys edges and retained renders by the two engine ids, so the
  edge (and any render already made for it) is dropped and planned again, now with the pin. The override is
  cleared as soon as the current or the next song changes. `ReplanNextEdgeTest` runs this against the real
  coordinator. Limits: it is refused while the transition is playing and with repeat-one; the planner still
  falls back (with the reason in the sheet's notes) when the chosen technique does not fit the analysis the
  coordinator holds; in battery-saver mode the coordinator skips non-live-capable techniques as always; and the
  coordinator gives way to the next candidate when the chosen technique would leave the current or the next song
  less than one bar of itself (`TransitionCoordinator`'s log says `room — <id> (your pick) ...`; the sheet does
  not show it). A pick that only costs the next song its own transition out is kept.
* **Ratings.** The Lab's thumbs keep their weight nudge and now *also* record a rating in the learned
  preferences; the Lab's blind A/B test renders two candidates, plays them as X and Y, and records up for the
  winner and down for the other. The Lab's "Pin for this pair" now also writes an engine pin (identity-keyed)
  into `pins.json`, so it steers playback; it still writes the Room `PairOverride` as before.
* **Learn from skips** (Settings → Transitions, on by default). `TransitionCoordinator` reports a skip ("Next")
  made while a planned, rendered transition plays, or within 20 s of B's body after it, as a `TransitionSkip`;
  `SkipFeedback` records it through `DjCustomization.recordSkip` → `FileFeedbackStore.recordImplicit` (same
  lock as ratings) when the toggle is on. Never counted: album and single-track playback, live fallbacks, a
  transition reached with a DJ skip, a skip later in B, and the same (A, B, technique) a second time while the
  service lives, among the last 256 transitions it reported (`MAX_SKIP_REPORT_KEYS` in `TransitionCoordinator`,
  an LRU: one reported before 256 others can count once more). A skip weighs a quarter of a thumbs-down and
  skips alone never take a technique below ×0.83 (`FeedbackLearner.IMPLICIT_WEIGHT`, `IMPLICIT_CAP`). A
  `feedback.json` holding skips is written as version 2 (`implicitN`, `implicitSum` per bucket); engines from
  before this change cannot read it.
* **Safety nets.** Nothing about customization can stop the service from starting: every file is read with
  the engine's skip-and-report loaders, and the problems are listed at the top of every DJ screen. Importing
  never overwrites one of the user's recipes without asking, keeps a recipe with errors only when the user says
  so (it is then never used), and refuses files over 512 KB (recipes are a few KB).

## 7. DJ customization: check on a device

None of these screens has been seen: there is no SDK and no device here. Each needs a look in light, dark
and black themes, with dynamic colour on and off, at the default and the largest font size.

1. **Settings → DJ** (index): "Connecting…" progress before the service is up; the problems card (collapsed,
   expanded); the five entries with their counts; the files path line.
2. **Style**: None selected (fresh install); each of the six built-in styles selected (card border +
   radio); "What it changes" expanded; a user style from `styles/` (the "Yours" label); a style file that no
   longer exists (problem line). Check that choosing a style changes the next planned transition.
3. **Recipes list**: loading; the nine built-ins; many recipes (import 20+ and scroll); an invalid recipe
   (error icon, "Not used: n errors", no switch); a user recipe shadowing a built-in (the built-in shows
   "Replaced…"); a recipe switched off. **Import**: picker cancelled (nothing happens, no dialog); a non-JSON
   file (report with line/column); a recipe with errors ("Keep it anyway" / Cancel); a recipe whose id is
   already one of the user's ("Replace" / Cancel); a file over 512 KB; success with warnings. Check that the
   picker offers `.json` files from Downloads and from a cloud provider (MIME types differ).
4. **Recipe detail**: description and meta line; enable switch; Export (SAF create, then open the file);
   Share (chooser, JSON as text); Duplicate (new "(copy)" appears, id `<id>-copy`); Delete (user recipes
   only, confirm, returns to the list); the problems list; the knob sliders (integer knobs step); the lane
   plot for both decks at several knob settings, a deck with no lanes ("Plays unchanged"), a recipe that
   cannot be resolved at some setting (red message). Check the lane colours against the surface in every
   theme — contrast was not measured.
5. **Presets**: grouped by technique; Defaults / a built-in / a user preset selected; a technique whose preset
   the style decides (radios disabled, note shown); delete confirm; while connecting (the list is filled
   once the engine is installed; the eight built-in presets are always there after that).
6. **Pinned pairs**: empty state; a pin made in the Lab (labels "Title — Artist → …"); remove confirm.
7. **Learned preferences**: empty; after several Lab ratings (multipliers above and below 1, counts);
   after skips ("n ratings · m skips", "1 skip"); Forget one technique; Forget all (confirm); the
   `feedback.json.bak` backup exists afterwards. **Learn from skips**: skip during a transition in a playlist
   and check a skip appears for that technique; skip 30 s into the next song, or in an album, and check none
   does; switch it off and check none does.
8. **Now Playing → Next transition sheet**: loading; the ranked list with scores, reasons (Why?/Less), the
   "Recipe", "Planned now" and "Your pick" tags; picking a technique (progress, snackbar, the queue badge
   changes to the new plan); "Let Muisc choose"; a gated pair (album playback: picking disabled, reason
   shown); during a transition (disabled); the last song (no next: error line); the unavailable list; "Open in
   Transition Lab". Verify by ear that the chosen technique is the one played, including when the old
   transition had already been rendered.
9. **Transition Lab**: the Presets menu (none yet / load / Save as preset… dialog); the Recipe tag on recipe
   candidates; the blind A/B card: rendering progress, Play X / Play Y / Stop, voting disabled until both were
   heard, the reveal text, Close; thumbs up/down message now includes the learned factor.
10. **Process death / service restart**: the style and all files survive; a one-off override does not (by
    design); the DJ screens reconnect after the service is recreated.

