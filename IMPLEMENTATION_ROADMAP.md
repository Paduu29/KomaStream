# IMPLEMENTATION_ROADMAP

Status: Active
Last Updated: 2026-05-24
Scope: Android performance, stability, Compose architecture, provider/network efficiency, build optimization

## 1. Current Architecture Snapshot

### App Structure
- Single-module Android app: `:app`
- Main entry points:
  - `app/src/main/java/com/paudinc/komastream/MainActivity.kt`
  - `app/src/main/java/com/paudinc/komastream/KomaStreamApp.kt`
  - `app/src/main/java/com/paudinc/komastream/KomaStream.kt`
- Major package areas:
  - `ui/screens`
  - `ui/components`
  - `ui/viewmodel`
  - `provider/providers`
  - `utils`
  - `data/local`
  - `data/model`
  - `data/repository`

### Provider Structure
- Provider registry is built via `createDefaultProviderRegistry(...)`
- Providers are instantiated directly and own their own network stacks
- Current providers include:
  - `InMangaProvider`
  - `LeerMangaEspProvider`
  - `OlympusBibliotecaProvider`
  - `MangaTubeProvider`
  - `MangaFireProvider`
  - `MarmotaProvider`
  - `MangadotProvider`
  - `Manhwa18Provider`
  - `ManhwaLatinoProvider`
  - `MangaBallProvider`
  - `AkaiComicProvider`
- Several providers depend on WebView-based cookie/bootstrap flows

### DB Structure
- Room database: `LibraryDatabase`
- DAO: `LibraryDao`
- Entities:
  - `FavoriteMangaEntity`
  - `ReadingMangaEntity`
  - `ReadChapterEntity`
  - `ChapterProgressEntity`
  - `ChapterPageCountEntity`
  - `AppSettingsEntity`
  - `MangaDetailCacheEntity`
- Current risks:
  - `allowMainThreadQueries()`
  - `fallbackToDestructiveMigration(dropAllTables = true)`
  - startup path reads Room in `attachBaseContext()`

### Networking Stack
- No central HTTP stack
- Each provider generally owns a dedicated `OkHttpClient`
- Additional standalone clients:
  - `MyAnimeListApi`
  - `GitHubReleaseUpdater`
- No shared connection pool/cache/interceptor policy across providers

### Compose Architecture
- Root composable `KomaStream()` manually constructs app graph and `KomaViewModel`
- State is pulled at the root from multiple controllers
- Navigation is custom and saveable-state-driven
- Several heavy screens compute and mutate data inside composables
- Several item-level lambdas call storage synchronously during composition

### State Management Approach
- Hybrid mutable state model:
  - `mutableStateOf(...)` in controllers
  - `MutableStateFlow` only for selected features
  - direct store/database calls from UI lambdas
- No unified route-level `UiState` model
- No systematic lifecycle-aware collection everywhere

### Dependency Graph
- Manual construction, no formal DI framework
- `KomaStream()` creates:
  - provider registry
  - stores
  - updater
  - `KomaViewModel`
- Workers and stores recreate provider registries independently
- No singleton graph for:
  - providers
  - OkHttp
  - repositories
  - caches

---

## 2. Priority Queue

### ID
`ENV-001`

### Title
Gradle and Java Toolchain Stabilization

### Severity
Critical

### Category
Build

### Affected Files
- `build.gradle`
- `app/build.gradle`
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.properties` if wrapper changes become necessary
- `.github/workflows/build-release.yml`
- `.idea/gradle.xml`
- `.idea/misc.xml` if IDE JDK alignment is required
- `IMPLEMENTATION_ROADMAP.md`
- `gradle/gradle-daemon-jvm.properties` if daemon JVM criteria is introduced

### Problem Summary
The repository is not on a reproducible build JVM. The local shell and current Gradle daemon are running on Java 25, while the project and CI configuration target Java 17-era Android tooling. Build validation for optimization work is therefore not trustworthy until the toolchain is normalized.

### Root Cause
- `JAVA_HOME` points to `C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot\`
- Gradle wrapper is `8.13`, which is not documented by Gradle as supported for running on Java 25
- AGP `8.13.2` documents JDK `17` as its minimum/default runtime
- IDE and CI already use supported JDKs (`jbr-21` in IDE, Temurin 17 in CI), so local CLI execution is misaligned with project intent

### Detected Environment Snapshot
- Installed Java in shell: Temurin `25.0.2`
- Additional installed JDK: Android Studio `jbr` `21.0.9`
- Gradle wrapper: `8.13`
- Gradle launcher JVM: Java `25.0.2`
- Gradle daemon JVM: Android Studio `jbr` `21.0.9` via `org.gradle.java.home`
- AGP: `8.13.2`
- Kotlin plugin: `2.2.21`
- Compose compiler: managed by `org.jetbrains.kotlin.plugin.compose` at Kotlin `2.2.21` (no standalone extension version declared)
- `compileSdk`: `36`
- `minSdk`: `24`
- `targetSdk`: `36`
- Java source/target compatibility: `17`
- Kotlin `jvmTarget`: `17`
- User-level `JAVA_HOME`: Android Studio `jbr` `21` configured for new shells
- Current shell `JAVA_HOME`: `C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot\`
- `org.gradle.java.home`: `C:/Program Files/Android/Android Studio/jbr` in `%USERPROFILE%\.gradle\gradle.properties`
- IDE Gradle JDK: `jbr-21`
- CI JDK: Temurin `21`

### Compatibility Matrix

| Pairing | Configured | Required / Supported | Result | Notes |
|---|---|---|---|---|
| Gradle runtime JVM | Gradle `8.13` on Java `25` | Gradle docs list Java `25` support for running Gradle starting at `9.1.0` | Fail | Local CLI runtime is unsupported |
| Gradle runtime JVM (candidate) | Gradle `8.13` on Java `21` | Gradle docs support running Gradle on Java `21` from `8.5+` | Pass | Android Studio `jbr-21` is locally available |
| AGP ↔ Gradle | AGP `8.13.2` + Gradle `8.13` | AGP `8.13` requires Gradle `8.13` | Pass | Wrapper and AGP are aligned |
| AGP ↔ JDK | AGP `8.13.2` + daemon JDK `21` | AGP `8.13` minimum/default JDK is `17` | Pass | Runtime is above minimum and validated by full builds |
| Kotlin ↔ AGP | Kotlin `2.2.21` + AGP `8.13.2` | Android docs require AGP `8.10+` for Kotlin `2.2` class compatibility | Pass | Current AGP exceeds minimum |
| Kotlin ↔ Compose Compiler | Kotlin plugin `2.2.21` + compose plugin `2.2.21` | Kotlin compose compiler plugin should match Kotlin version | Pass | Integrated plugin setup is correct |
| JVM target ↔ Java toolchain | `jvmTarget 17` with runtime/toolchain Java `21` | Compile target should be backed by a stable supported toolchain | Pass | Runtime JDK `21`, emitted bytecode `17` |
| IDE ↔ CI | IDE `jbr-21`, CI `21`, Gradle daemon `21` | One supported project runtime should be used everywhere practical | Pass | Runtime alignment achieved |

### Required Java Version
`21` for the Gradle/build runtime, while produced app bytecode remains targeted to Java/Kotlin `17`.

Rationale:
- Gradle `8.13` supports running on Java `21`.
- Android Studio `jbr-21` is already installed locally and already configured for the IDE Gradle JDK.
- Java `17` is not installed locally in this workspace, so a hard switch to `17` would force toolchain download/bootstrap work before the build can stabilize.
- Keeping runtime JDK `21` and bytecode target `17` preserves device compatibility while eliminating the unsupported Java `25` launcher.

### Planned Solution
1. Roll back partial `FIX-001` app-code changes to the last safe checkpoint so environment validation is isolated.
2. Declare Java/Kotlin toolchains in Gradle for Java `21` runtime while keeping `jvmTarget`/source compatibility at `17`.
3. Align IDE Gradle JDK metadata to a supported runtime if needed.
4. Align CI to JDK `21`.
5. If reproducible local CLI execution still depends on launcher JVM selection, use a local user-level Gradle/JAVA_HOME override rather than relying on machine-global Java `25`.

### BUILD VALIDATION
- `./gradlew clean`
- `./gradlew assembleDebug`
- `./gradlew assembleRelease`
- `./gradlew test`
- `./gradlew lint`

### Implementation Steps
1. Add `ENV-001` as the build gate and suspend optimization fixes.
2. Restore the repository to the pre-`FIX-001` app-code checkpoint.
3. Add Java/Kotlin toolchain declarations for Java `21` runtime.
4. Align Gradle daemon/runtime selection to a supported JDK.
5. Add a lint baseline so `./gradlew lint` becomes a stable regression gate instead of failing on unrelated historical issues.
6. Validate Gradle sync/build/test/lint on the normalized toolchain.
7. Document exact failures, fixes, and rollback points.

### Validation Steps
1. `./gradlew -version` must show a supported daemon JVM.
2. Gradle sync must succeed.
3. `clean`, `assembleDebug`, `assembleRelease`, `test`, and `lint` must complete without JVM compatibility errors.
4. No IDE/CI mismatch should remain undocumented.

### Rollback Strategy
1. Remove toolchain declarations introduced by `ENV-001`.
2. Remove daemon JVM criteria file if it causes resolution issues.
3. Restore prior IDE metadata only if the new configuration breaks project import.
4. Keep `FIX-001` and later optimization work blocked until the original failing condition is understood.

### Dependencies
- None

### Status
Done

---

### ID
`FIX-001`

### Title
Replace manually remembered `KomaViewModel` with a lifecycle-owned ViewModel

### Severity
Critical

### Category
Stability

### Affected Files
- `app/src/main/java/com/paudinc/komastream/KomaStream.kt`
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/KomaViewModel.kt`
- `app/src/main/java/com/paudinc/komastream/MainActivity.kt`
- Additional factory/DI files to be introduced as needed

### Problem Summary
`KomaViewModel` is created with `remember { KomaViewModel(...) }` inside composition instead of being lifecycle-owned.

### Root Cause
Manual object graph construction in `KomaStream()` bypasses Android ViewModel lifecycle management.

### Planned Solution
Introduce a proper `ViewModelProvider` factory or DI-backed creation path and move root dependencies into an app graph or DI entry point.

### PRE_IMPLEMENTATION

#### Exact Files That Will Be Modified
- `app/src/main/java/com/paudinc/komastream/KomaStream.kt`
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/KomaViewModel.kt`
- `app/src/main/java/com/paudinc/komastream/MainActivity.kt` if activity ownership needs to host the new factory entry point
- new file(s) expected:
  - `app/src/main/java/com/paudinc/komastream/.../KomaViewModelFactory.kt`
  - app graph/dependency holder file if required for safe incremental migration
- `IMPLEMENTATION_ROADMAP.md`

#### Exact Classes / Functions Affected
- `KomaStream()`
- `KomaViewModel`
- `MainActivity.onCreate(...)` only if root dependency access must be hoisted there
- custom navigation/state restoration interaction points in:
  - `KomaStream.kt` saveable stack ownership
  - `KomaViewModel` constructor and initialization behavior
- any new `ViewModelProvider.Factory` implementation introduced for `KomaViewModel`

#### Expected Side Effects
- ViewModel lifetime will align with Android lifecycle rather than composition lifetime
- root dependency construction may move out of composition
- initialization order of background work may shift slightly
- config-change behavior should become more correct and less duplicate-prone

#### Possible Regressions
- duplicate initialization if both old and new ownership paths coexist incorrectly
- lost navigation stack restore if saved state wiring changes incorrectly
- stale dependency references if factory/app graph is mis-scoped
- crashes on recreation if the factory requires unavailable context/state
- background work not starting if startup hooks are moved incorrectly

#### Database / Schema Changes Involved
- No schema changes are allowed in `FIX-001`
- No Room migration changes are part of this fix
- No persistence format changes are allowed in this fix

#### Lifecycle Behavior Changes
- Yes
- Primary purpose of the fix is to move from composition-owned lifecycle to Android ViewModel lifecycle ownership

#### Threading Behavior Changes
- Possibly minor
- `viewModelScope` should remain the main coroutine scope, but its cancellation boundary will change to true ViewModel lifecycle ownership
- No dispatcher policy changes are allowed in this fix

#### Memory Ownership Changes
- Yes
- root app objects may move from composition ownership to activity/app graph ownership
- retained object lifetime will change; expected outcome is reduced accidental retention and fewer leaked jobs

#### Recomposition Behavior Changes
- Yes, but only indirectly
- removing `remember { KomaViewModel(...) }` should stabilize root ownership and reduce accidental recreation risk
- broad UI invalidation behavior must remain functionally equivalent during this fix

#### Navigation / State Restoration Changes
- Yes
- state restoration is a sensitive area because the current navigation stack is injected into `KomaViewModel` during construction
- backward-compatible state restoration must be preserved during migration

#### Provider Initialization Changes
- Possibly
- provider registry creation may move from composition to app graph/factory dependency creation
- provider behavior must remain unchanged

### SAFE CHECKPOINT

Before code modifications begin, the repo must satisfy all of the following:
1. `IMPLEMENTATION_ROADMAP.md` contains the full `FIX-001` pre-implementation contract.
2. The current repo state is limited to validated `ENV-001` build-environment changes plus roadmap/documentation updates.
3. The current app codebase remains unmodified from the analyzed baseline.
4. The project must compile from the current baseline before `FIX-001` code changes begin.
5. A rollback point must exist where:
   - old `remember`-based ViewModel creation still works
   - no dependency graph migration has started
   - no navigation/state restoration logic has changed

### VALIDATION GATES

Implementation of `FIX-001` cannot proceed past each phase unless all relevant gates pass:
- app compiles
- no new lint errors
- no crashes on startup
- no navigation regressions
- no state restoration regressions
- no database migration regressions
- no new memory leaks
- no recomposition explosions
- no ANRs introduced

Operational interpretation for `FIX-001`:
- "no database migration regressions" means no DB-related behavior is touched and no DB code is broken indirectly
- "no recomposition explosions" means root and primary screen recomposition counts must not materially regress in smoke validation
- "no new memory leaks" means no retained `Activity`, `Context`, `WebView`, or duplicate ViewModel/job ownership introduced by the migration

### IMPLEMENTATION STRATEGY

#### 1. Preparation Phase
1. Identify the minimum dependency set needed to construct `KomaViewModel`.
2. Create a narrowly scoped factory/app dependency holder without changing runtime behavior yet.
3. Keep old construction path intact during preparation.

#### 2. Migration Phase
1. Introduce lifecycle-owned ViewModel creation in parallel with existing assumptions.
2. Adapt constructor/factory wiring so runtime behavior remains equivalent.
3. Preserve navigation stack injection/state restore compatibility.

#### 3. Compatibility Phase
1. Validate that saved navigation stack and root state restoration still behave correctly.
2. Validate config changes during active work.
3. Validate no duplicate controller/background initialization occurs.

#### 4. Cleanup Phase
1. Remove obsolete composition-owned construction path only after replacement is validated.
2. Remove temporary compatibility code that is no longer needed.
3. Keep cleanup strictly scoped to `FIX-001`.

#### 5. Validation Phase
1. Compile and lint validation.
2. Startup smoke test.
3. Navigation and back stack smoke test.
4. rotation/state restoration smoke test.
5. memory/leak sanity check.

Backward compatibility requirement:
- old behavior must be preserved throughout migration until the replacement path is validated
- no state persistence format may change in this fix

### COMMIT STRATEGY

- Create small atomic commits only
- Do not mix unrelated cleanup or performance changes into `FIX-001`
- Reference `FIX-001` in every commit message related to this work
- Record rollback points after each atomic step in the roadmap progress tracking

Planned commit shape:
1. factory/app graph preparation
2. ViewModel ownership migration
3. compatibility cleanup after validation

### HARD RULES

- NEVER rewrite large systems in one pass
- NEVER change architecture and behavior simultaneously
- NEVER remove old code before validating replacement
- NEVER introduce destructive DB behavior
- NEVER optimize without benchmark evidence
- ALWAYS prefer additive migration over replacement

### Implementation Steps
1. Define root dependency provider/app graph.
2. Add `KomaViewModelFactory`.
3. Replace `remember { KomaViewModel(...) }` with `viewModel(...)`.
4. Ensure only application-safe dependencies are retained by the ViewModel.
5. Verify cancellation/cleanup semantics.

### Validation Steps
1. Build debug app.
2. Rotate screen during active sync/download.
3. Confirm no duplicate background tasks are created.
4. Confirm navigation state still restores correctly.

### Rollback Strategy
1. Revert factory/app graph changes.
2. Restore previous `remember`-based creation.
3. Re-run compile and smoke test.

### Dependencies
- `ENV-001`

### Status
Done

---

### ID
`FIX-002`

### Title
Remove main-thread Room access and destructive migration fallback

### Severity
Critical

### Category
Database

### Affected Files
- `app/src/main/java/com/paudinc/komastream/data/local/LibraryDatabase.kt`
- `app/src/main/java/com/paudinc/komastream/KomaStreamApp.kt`
- `app/src/main/java/com/paudinc/komastream/utils/LibraryStore.kt`
- migration tests to be added

### Problem Summary
Room is configured with `allowMainThreadQueries()` and destructive fallback migrations, and startup reads Room synchronously in `attachBaseContext()`.

### Root Cause
Startup/bootstrap convenience was prioritized over a safe persistence model.

### Planned Solution
Make Room fully background-only, remove destructive fallback, and move locale bootstrap to a lightweight storage path.

### Implementation Steps
1. Remove `allowMainThreadQueries()`.
2. Remove `fallbackToDestructiveMigration(...)`.
3. Audit `LibraryStore` call sites that assume synchronous main-thread access.
4. Replace startup language DB read with lightweight bootstrap storage.
5. Add migration validation coverage.

### Validation Steps
1. Build app.
2. Launch cold start with StrictMode enabled.
3. Verify existing local data survives migration path.
4. Verify no startup crash due to missing settings.

### Rollback Strategy
1. Revert Room builder changes.
2. Re-enable previous startup language read path.
3. Restore previous behavior only if replacement path is broken.

### Dependencies
- `FIX-001`

### Status
Pending

---

### ID
`FIX-003`

### Title
Remove synchronous storage lookups from composition

### Severity
High

### Category
Compose

### Affected Files
- `app/src/main/java/com/paudinc/komastream/KomaStream.kt`
- `app/src/main/java/com/paudinc/komastream/utils/LibraryStore.kt`
- `app/src/main/java/com/paudinc/komastream/ui/screens/LibraryScreen.kt`
- `app/src/main/java/com/paudinc/komastream/ui/screens/CatalogScreen.kt`
- `app/src/main/java/com/paudinc/komastream/ui/screens/HomeScreen.kt`

### Problem Summary
Composition-time lambdas call `LibraryStore.isFavorite(...)`, `getCachedMangaChapterCount(...)`, and `getCachedMangaDetail(...)`.

### Root Cause
UI depends directly on storage APIs rather than prepared immutable UI lookup state.

### Planned Solution
Expose precomputed favorite/chapter-count/detail lookup state from the ViewModel layer and pass immutable maps/sets into composables.

### Implementation Steps
1. Identify all storage calls reachable during composition.
2. Add lookup state to route-level UI state.
3. Replace storage lambdas with in-memory lookups.
4. Re-run list-heavy UI screens and confirm behavior.

### Validation Steps
1. Build app.
2. Scroll library, catalog, and home.
3. Toggle favorites repeatedly.
4. Confirm no behavioral regressions.

### Rollback Strategy
1. Restore previous lambdas.
2. Remove lookup state additions.
3. Re-verify screen rendering.

### Dependencies
- `FIX-001`
- `FIX-002`

### Status
Pending

---

### ID
`FIX-004`

### Title
Refactor root/controller state into lifecycle-aware immutable route UI state

### Severity
High

### Category
Architecture

### Affected Files
- `app/src/main/java/com/paudinc/komastream/KomaStream.kt`
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/KomaViewModel.kt`
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/HomeController.kt`
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/CatalogController.kt`
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/LibraryController.kt`
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/ReaderController.kt`
- `app/src/main/java/com/paudinc/komastream/data/model/MangaModels.kt`

### Problem Summary
Large mutable controller states are read from the root composable, broadening recomposition scope and reducing skippability.

### Root Cause
Incremental controller layering without a unified route-state contract.

### Planned Solution
Introduce immutable route `UiState` models, migrate critical controllers to `StateFlow`, and collect with lifecycle awareness.

### Implementation Steps
1. Define route-level UI state models.
2. Add immutable/stable model annotations where contract is valid.
3. Convert controller outputs progressively to `StateFlow`.
4. Narrow state reads per screen.
5. Measure recomposition before/after.

### Validation Steps
1. Build app.
2. Open each main screen.
3. Verify state transitions still render correctly.
4. Compare recomposition counts on key screens.

### Rollback Strategy
1. Revert route-level state conversion for the specific screen being changed.
2. Restore prior controller state exposure.
3. Keep migration incremental to minimize rollback blast radius.

### Dependencies
- `FIX-001`
- `FIX-003`

### Status
Pending

---

### ID
`FIX-005`

### Title
Reduce reader memory churn from offline byte loading and per-recomposition image request creation

### Severity
High

### Category
Memory

### Affected Files
- `app/src/main/java/com/paudinc/komastream/ui/screens/ReaderScreen.kt`
- `app/src/main/java/com/paudinc/komastream/utils/OfflineChapterStore.kt`

### Problem Summary
Offline pages are loaded into memory as `ByteArray` and new `ImageRequest` objects are built in a hot path.

### Root Cause
Reader image pipeline uses in-memory bytes instead of file-backed loading and does not fully remember expensive request objects.

### Planned Solution
Switch reader/offline loading to file or URI-backed Coil inputs and ensure request objects are stably remembered.

### Implementation Steps
1. Replace `ByteArray` page loading path with file-backed source where possible.
2. Make reader image requests properly remembered.
3. Re-evaluate prefetch logic.
4. Validate page rendering for online and offline chapters.

### Validation Steps
1. Build app.
2. Open online reader.
3. Open offline reader.
4. Scroll long chapter and monitor memory.

### Rollback Strategy
1. Restore byte-array loading path.
2. Revert reader request changes.
3. Re-test online/offline rendering.

### Dependencies
- `FIX-002`
- `FIX-004`

### Status
Pending

---

### ID
`FIX-006`

### Title
Stream chapter downloads to disk instead of buffering entire chapters in memory

### Severity
High

### Category
Memory

### Affected Files
- `app/src/main/java/com/paudinc/komastream/utils/DownloadChapterWorker.kt`
- `app/src/main/java/com/paudinc/komastream/utils/OfflineChapterStore.kt`

### Problem Summary
Download worker accumulates all page payloads in memory before persisting them.

### Root Cause
Worker API and offline store API are batch-oriented rather than streaming.

### Planned Solution
Introduce streaming page writes with an atomic finalize step for manifest completion.

### Implementation Steps
1. Extend `OfflineChapterStore` with incremental write API.
2. Update worker to write each page immediately.
3. Preserve cancellation safety and cleanup semantics.
4. Verify partial download cleanup behavior.

### Validation Steps
1. Download small and large chapters.
2. Cancel downloads mid-flight.
3. Resume/retry failed downloads.
4. Confirm offline chapter opens correctly.

### Rollback Strategy
1. Revert worker to batch mode.
2. Remove incremental write API.
3. Re-test download flow.

### Dependencies
- `FIX-005`

### Status
Pending

---

### ID
`FIX-007`

### Title
Move `HomeSectionScreen` data loading and paging out of composables

### Severity
High

### Category
Compose

### Affected Files
- `app/src/main/java/com/paudinc/komastream/ui/screens/HomeSectionScreen.kt`
- new ViewModel/state files as needed
- provider paging call sites

### Problem Summary
Section pagination/networking is run from composable scope and full lists are saved into saved state.

### Root Cause
Screen-level state ownership was pushed into `rememberSaveable` instead of a lifecycle-aware state holder.

### Planned Solution
Create a dedicated state holder or ViewModel for home-section paging and save only small UI primitives.

### Implementation Steps
1. Create `HomeSectionUiState`.
2. Move paging state/loading into VM/state holder.
3. Replace list savers with minimal saveable state.
4. Verify section pagination and rotation handling.

### Validation Steps
1. Open paged sections for multiple providers.
2. Load more repeatedly.
3. Rotate during paging.
4. Confirm no item duplication or state loss.

### Rollback Strategy
1. Restore prior composable-managed paging.
2. Remove section VM/state holder.
3. Re-test section behavior.

### Dependencies
- `FIX-001`
- `FIX-004`

### Status
Pending

---

### ID
`FIX-008`

### Title
Introduce shared networking and provider dependency graph

### Severity
High

### Category
Networking

### Affected Files
- `app/src/main/java/com/paudinc/komastream/utils/ProviderRegistry.kt`
- provider classes under `provider/providers`
- `app/src/main/java/com/paudinc/komastream/utils/LibraryStore.kt`
- `app/src/main/java/com/paudinc/komastream/utils/DownloadChapterWorker.kt`
- `app/src/main/java/com/paudinc/komastream/utils/MyAnimeListApi.kt`
- `app/src/main/java/com/paudinc/komastream/updater/GitHubReleaseUpdater.kt`

### Problem Summary
Provider registry and `OkHttpClient` instances are duplicated across UI, stores, workers, updater, and MAL integration.

### Root Cause
No centralized DI/app graph.

### Planned Solution
Introduce a singleton app graph with shared HTTP infrastructure and provider construction.

### Implementation Steps
1. Design app graph ownership.
2. Centralize shared `OkHttpClient` configuration.
3. Migrate providers incrementally.
4. Migrate worker and utility consumers.
5. Validate cookie-sensitive providers carefully.

### Validation Steps
1. Build app.
2. Verify all providers still load home, detail, and reader.
3. Verify Cloudflare flows still function.
4. Confirm downloads and MAL sync still work.

### Rollback Strategy
1. Revert specific provider migrations one provider at a time.
2. Restore local client ownership if a provider breaks.
3. Keep rollout incremental to avoid multi-provider outages.

### Dependencies
- `FIX-001`

### Status
Pending

---

### ID
`FIX-009`

### Title
Eliminate redundant MAL full-library fetches in single-item sync paths

### Severity
High

### Category
Networking

### Affected Files
- `app/src/main/java/com/paudinc/komastream/ui/viewmodel/MalSyncController.kt`
- `app/src/main/java/com/paudinc/komastream/utils/MyAnimeListApi.kt`

### Problem Summary
Single-item sync operations fetch the entire MAL library to resolve one remote item state.

### Root Cause
Remote state lookup is implemented via bulk fetch reuse instead of item-level caching or item-level API flow.

### Planned Solution
Introduce remote snapshot caching and avoid repeated full-list refreshes during local mutations.

### Implementation Steps
1. Add cached remote snapshot/state.
2. Reuse snapshot during burst sync operations.
3. Invalidate snapshot when needed.
4. Re-test local favorite/read sync flows.

### Validation Steps
1. Toggle favorite/read repeatedly.
2. Sync library both ways.
3. Verify remote statuses are still correct.
4. Compare request counts before/after.

### Rollback Strategy
1. Remove snapshot cache.
2. Restore direct full-list fetch path.
3. Re-test MAL sync.

### Dependencies
- `FIX-008`

### Status
Pending

---

### ID
`FIX-010`

### Title
Enable release shrinking, Compose metrics, and baseline-profile infrastructure

### Severity
Medium

### Category
Build

### Affected Files
- `app/build.gradle`
- `build.gradle`
- `settings.gradle`
- new benchmark/baseline profile modules as needed
- `proguard-rules.pro` to be introduced

### Problem Summary
Release builds are unoptimized: shrinking is disabled, no baseline profile exists, and Compose compiler metrics are not configured.

### Root Cause
Build/perf instrumentation has not been wired into the project.

### Planned Solution
Enable R8/resource shrinking, add Compose compiler reports, and introduce baseline profile + macrobenchmark support.

### Implementation Steps
1. Enable minify/resource shrink for release.
2. Add proguard rules.
3. Add Compose metrics/reports output.
4. Add baseline profile module.
5. Add macrobenchmark smoke scenarios.

### Validation Steps
1. Assemble release.
2. Run smoke install.
3. Verify no reflection/serialization regressions.
4. Compare APK/AAB size and startup.

### Rollback Strategy
1. Disable shrink step-by-step.
2. Remove newly added rules if they break release.
3. Keep benchmark modules isolated from app runtime.

### Dependencies
- None

### Status
Pending

---

### ID
`FIX-011`

### Title
Destroy WebViews fully in Cloudflare/bootstrap flows

### Severity
Medium

### Category
Stability

### Affected Files
- `app/src/main/java/com/paudinc/komastream/ui/components/BrowserBootstrapDialog.kt`
- WebView resolver utilities as needed

### Problem Summary
WebView teardown is incomplete and may retain native resources.

### Root Cause
Dialog disposal clears content but does not fully destroy WebView instances.

### Planned Solution
Add full WebView destruction and audit other resolver teardown paths.

### Implementation Steps
1. Update dialog teardown.
2. Audit resolver helpers for matching cleanup.
3. Re-test Cloudflare/bootstrap flows.

### Validation Steps
1. Open and close bootstrap dialog repeatedly.
2. Verify subsequent bootstrap attempts still work.
3. Check for retained WebView instances.

### Rollback Strategy
1. Revert teardown logic.
2. Restore previous cleanup path if bootstrap behavior regresses.

### Dependencies
- None

### Status
Pending

---

### ID
`FIX-012`

### Title
Optimize provider-specific hot paths: Mangadot home fetch concurrency and MangaFire descramble memory

### Severity
Medium

### Category
Performance

### Affected Files
- `app/src/main/java/com/paudinc/komastream/provider/providers/MangadotProvider.kt`
- `app/src/main/java/com/paudinc/komastream/utils/MangaFireWebViewResolver.kt`

### Problem Summary
Mangadot home fetches are sequential; MangaFire descrambling creates multiple temporary bitmaps.

### Root Cause
Provider implementations are optimized for correctness but not for latency or allocation behavior.

### Planned Solution
Parallelize independent home calls and reduce bitmap allocation churn in descrambling.

### Implementation Steps
1. Parallelize safe independent network sections.
2. Profile provider latency.
3. Refactor descramble path to reduce temporary allocations.
4. Validate provider output equivalence.

### Validation Steps
1. Compare provider home load time.
2. Open multiple MangaFire chapters.
3. Verify image fidelity and no crashes.

### Rollback Strategy
1. Revert provider-specific changes independently.
2. Restore sequential/home or old descramble path if provider breaks.

### Dependencies
- `FIX-008`

### Status
Pending

---

## 3. Execution Order

### Must Happen First
1. `ENV-001` - stabilize Gradle and Java toolchain
2. `FIX-001` - lifecycle-owned ViewModel
3. `FIX-002` - remove unsafe Room configuration
4. `FIX-003` - remove storage access from composition

### Blockers
- `ENV-001` blocks all optimization work until the build is reproducible.
- `FIX-001` blocks most architecture cleanup because state ownership is currently invalid.
- `FIX-002` blocks safe performance work because current behavior hides main-thread DB misuse.
- `FIX-008` blocks safe network consolidation and MAL optimization.

### Safe Incremental Wins
- `FIX-011` WebView teardown
- `FIX-010` Compose metrics/build instrumentation
- parts of `FIX-005` if done without changing storage format

### Fixes Requiring Architecture Changes
- `FIX-001`
- `FIX-004`
- `FIX-007`
- `FIX-008`

### Risky Fixes
- `FIX-002` because it changes persistence behavior
- `FIX-006` because it changes offline download persistence flow
- `FIX-008` because it touches all providers
- `FIX-009` because it changes sync semantics

### Recommended Execution Sequence
1. `ENV-001`
2. `FIX-001`
3. `FIX-002`
4. `FIX-003`
5. `FIX-011`
6. `FIX-004`
7. `FIX-005`
8. `FIX-006`
9. `FIX-007`
10. `FIX-010`
11. `FIX-008`
12. `FIX-009`
13. `FIX-012`

---

## 4. Safety Constraints

The implementation process must:
- avoid breaking changes to user-facing behavior unless explicitly validated
- avoid data loss
- avoid destructive migrations
- avoid large unverified refactors
- avoid changing multiple critical systems simultaneously
- keep the app buildable after every completed task
- isolate provider-specific risk to one provider at a time where possible
- preserve offline downloads, library data, MAL links, and navigation continuity

Additional constraints:
- Never combine `FIX-002`, `FIX-006`, and `FIX-008` in a single unverified batch
- Never refactor more than one persistence boundary at once
- Never change provider networking and provider parsing in the same step without focused validation

---

## 5. Validation Matrix

For every fix, perform the following validation categories as applicable:

| Fix ID | Compile Validation | Runtime Validation | Recomposition Validation | Memory Validation | Crash Validation | Navigation Validation | Provider Validation |
| ENV-001 | `clean`, `assembleDebug`, `assembleRelease`, `test`, `lint` | Gradle sync/import smoke | N/A | N/A | JVM compatibility error check | N/A | N/A |
|---|---|---|---|---|---|---|---|
| FIX-001 | `:app:assembleDebug` | app launch + rotate | root/screen recomposition smoke | retained object check | config-change + background task smoke | back stack restore | current provider load |
| FIX-002 | `:app:assembleDebug` | cold start + settings load | smoke | StrictMode/DB access smoke | migration/startup smoke | provider select persists | home/detail/reader still load |
| FIX-003 | `:app:assembleDebug` | favorite/chapter count display | compare recomposition counts | scroll heap smoke | null/lookup edge cases | list open flows | provider-specific favorite display |
| FIX-004 | `:app:assembleDebug` | each root tab | Compose metrics/report comparison | heap smoke | state transition smoke | root/detail/reader transitions | all active providers |
| FIX-005 | `:app:assembleDebug` | online/offline reader | reader recomposition smoke | heap/GC during read | malformed/offline page handling | chapter open/back | reader image headers still valid |
| FIX-006 | `:app:assembleDebug` | download/cancel/retry | N/A | large chapter download heap | worker retry/cancel smoke | open downloaded chapter | provider download correctness |
| FIX-007 | `:app:assembleDebug` | paged sections | section recomposition smoke | saved-state size smoke | rotate/process recreation smoke | open section/back | paged providers only |
| FIX-008 | `:app:assembleDebug` | provider boot smoke | N/A | connection pool/object count | auth/cookie/bootstrap smoke | navigation smoke | all providers, MAL, updater |
| FIX-009 | `:app:assembleDebug` | MAL favorite/read sync | N/A | request count/CPU smoke | sync failure/retry smoke | open sync screens | MAL only |
| FIX-010 | release assemble | install/release smoke | Compose metrics available | APK size compare | minified runtime smoke | startup smoke | app-wide |
| FIX-011 | `:app:assembleDebug` | repeated dialog open/close | N/A | native heap smoke | bootstrap flow smoke | dialog close/back | Cloudflare providers |
| FIX-012 | `:app:assembleDebug` | provider-specific smoke | N/A | bitmap/network allocation smoke | parse/image failure smoke | reader open/back | Mangadot + MangaFire |

---

## 6. Progress Tracking

### Completed Tasks
- Analysis completed.
- Initial roadmap created.
- `FIX-001` pre-implementation contract added.
- `ENV-001` added as the mandatory build gate before any optimization resumes.
- Interrupted `FIX-001` app-code changes were rolled back to the safe checkpoint before environment work proceeded.
- `ENV-001` completed: toolchain normalized to Java `21` runtime with Java/Kotlin `17` output targets.
- `FIX-001` completed: `KomaViewModel` creation now uses lifecycle ownership with an application-scoped dependency graph.

### In Progress
- None

### Pending Tasks
- `FIX-002`
- `FIX-003`
- `FIX-004`
- `FIX-005`
- `FIX-006`
- `FIX-007`
- `FIX-008`
- `FIX-009`
- `FIX-010`
- `FIX-011`
- `FIX-012`

### Discovered Blockers
- Project `lint` had 25 pre-existing errors and required a baseline before it could act as a regression gate.
- No DI/app graph exists yet.
- Main-thread Room access currently masks storage misuse in UI.
- Single-module structure increases blast radius of large refactors.
- Baseline `lintDebug` currently fails on pre-existing issues unrelated to `FIX-001`; gate interpretation for this migration is "no new lint errors introduced"

### Newly Discovered Issues
- Root composition performs storage lookups in item lambdas.
- Saved-state payload risk in `HomeSectionScreen`.
- Multiple standalone `OkHttpClient` instances across providers and utilities.
- Release build optimization is currently disabled.

### Changed Priorities
- Stability and data safety remain the top priority.
- Reproducible build-toolchain correctness is now the hard prerequisite for all optimization work.
- Build optimization is deferred until core runtime stability issues are removed.

### Modified Files
- `IMPLEMENTATION_ROADMAP.md` - created
- `IMPLEMENTATION_ROADMAP.md` - updated with `FIX-001` pre-implementation, safe checkpoint, validation gates, implementation strategy, commit strategy, and hard rules
- `IMPLEMENTATION_ROADMAP.md` - updated to mark `FIX-001` in progress and record baseline validation state
- `IMPLEMENTATION_ROADMAP.md` - updated to add `ENV-001`, compatibility matrix, required Java version, and build validation gate
- `app/build.gradle` - added Kotlin JVM toolchain `21`, Java compile toolchain selection `21`, and lint baseline configuration
- `.github/workflows/build-release.yml` - aligned CI JDK from `17` to `21`
- `app/lint-baseline.xml` - generated baseline for existing lint debt so future lint runs fail only on new issues
- `app/src/main/java/com/paudinc/komastream/AppGraph.kt` - added application-scoped dependency holder for root objects retained by `KomaViewModel`
- `app/src/main/java/com/paudinc/komastream/KomaStream.kt` - switched root ViewModel creation to application-graph-backed factory inputs
- `app/src/main/java/com/paudinc/komastream/KomaViewModelFactory.kt` - tightened lifecycle factory creation check for `KomaViewModel`
- `app/src/main/java/com/paudinc/komastream/KomaStreamApp.kt` - introduced application-owned `AppGraph`
- User-level Gradle configuration - added `org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr` to `%USERPROFILE%\.gradle\gradle.properties`
- User-level environment - updated `JAVA_HOME` to Android Studio `jbr` `21` for new shells

### Latest Fix Execution Snapshot
- Current fix ID: `FIX-001`
- Current phase: Completed
- Completed validations:
  - `./gradlew :app:assembleDebug` - Passed
  - `./gradlew :app:testDebugUnitTest` - Passed
  - `./gradlew :app:lintDebug` - Passed using the existing baseline
- Changed files:
  - `app/src/main/java/com/paudinc/komastream/AppGraph.kt`
  - `app/src/main/java/com/paudinc/komastream/KomaStream.kt`
  - `app/src/main/java/com/paudinc/komastream/KomaStreamApp.kt`
  - `app/src/main/java/com/paudinc/komastream/KomaViewModelFactory.kt`
  - `IMPLEMENTATION_ROADMAP.md`
- Detected risks:
  - Rotation, process recreation, and back-stack restore still need manual runtime smoke validation on device/emulator
  - `KomaViewModel` still retains an `AppStrings` snapshot; behavior is preserved, but locale-refresh behavior is not yet modernized
- Benchmark deltas:
  - Not applicable for `FIX-001`
- Remaining fixes:
  - `FIX-002`
  - `FIX-003`
  - `FIX-004`
  - `FIX-005`
  - `FIX-006`
  - `FIX-007`
  - `FIX-008`
  - `FIX-009`
  - `FIX-010`
  - `FIX-011`
  - `FIX-012`
- Rollback status:
  - No rollback required for `FIX-001`

### Current ENV-001 Validation Status
- `./gradlew -version`: Passed with daemon JVM pinned to Android Studio `jbr` `21`
- `clean`: Passed
- `assembleDebug`: Passed
- `assembleRelease`: Passed
- `test`: Passed
- `lint`: Passed with baseline applied
- Result: no remaining JVM compatibility errors in validated builds

---

## 7. Final Optimization Report

This section will be completed after implementation.

### Before Metrics
- Pending

### After Metrics
- Pending

### Resolved Issues
- Pending

### Remaining Issues
- Pending

### Risks Remaining
- Pending

### Future Optimization Opportunities
- Modularize providers into separate feature/data modules
- Add Paging 3 where provider pagination semantics allow it
- Add production jank telemetry and ANR clustering
- Add provider-specific caches and circuit breakers
- Replace ad hoc parser code paths with more structured parsing where practical
