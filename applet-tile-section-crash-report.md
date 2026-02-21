# Bug Report: AppletTileSectionProvider Runtime Crash

## Summary

Commenting out the `featureFlagManager: FeatureFlagManager` parameter from
`AppletTileSectionProvider`'s constructor causes a `NoSuchMethodError` crash at app startup. The
root cause is an incremental build mismatch: Metro's generated component code still calls
`MetroFactory$Companion.create()` with **12** `Provider` arguments (the original count), but after
the parameter was removed the recompiled `MetroFactory` only exposes an **11**-argument `create()`.

---

## Stack Trace

```
java.lang.NoSuchMethodError: No virtual method create(
  Ldev/zacsweers/metro/Provider; x12
)Lcom/squareup/cash/money/applets/sections/AppletTileSectionProvider$MetroFactory;
in class Lcom/squareup/cash/money/applets/sections/AppletTileSectionProvider$MetroFactory$Companion;
  at com.squareup.cash.VariantSandboxedComponent$Impl$SandboxedActivityComponentImpl
       .getSetOfFactoryProvider(Unknown Source:111)
  at com.squareup.cash.VariantSandboxedComponent$Impl$SandboxedActivityComponentImpl
       .getSetOfPresenterFactoryProvider(Unknown Source:298)
  at com.squareup.cash.VariantSandboxedComponent$Impl$SandboxedActivityComponentImpl
       .init19(Unknown Source:417)
  ...
  at com.squareup.cash.ui.MainActivity.onCreate(MainActivity.kt:187)
```

---

## Root Cause

Metro (the project's DI framework) generates a `MetroFactory` companion class for every
`@AssistedInject` constructor. The factory's `create()` method takes one `Provider<T>` argument for
each **non-assisted** constructor parameter.

### Original constructor (13 params: 12 non-assisted + 1 `@Assisted`)

```
Provider #  | Parameter
----------- | ---------
 1          | applets: Set<Applet>
 2          | appletSpanTrackingService: MoneyAppletSpanTrackingService
 3          | clientRecommendationProvider: AppletTileClientRecommendationProvider
 4          | serverRecommendationProvider: AppletTileServerRecommendationProvider
 5          | promotedPriorityProvider: AppletTilePromotedPriorityProvider
 6          | contentSpanTrackingService: MoneyContentSpanTrackingService
 7          | familyProfileManager: FamilyProfileManager
 8          | balancePrivacy: BalancePrivacy
 9          | stringManager: StringManager
10          | featureFlagManager: FeatureFlagManager   ← REMOVED
11          | errorReporter: ErrorReporter
12          | ioDispatcher: CoroutineContext (@Io)
  —         | navigator: Navigator (@Assisted — not a Provider arg)
```

`MetroFactory$Companion.create()` therefore had **12** `Provider` parameters.

### After commenting out `featureFlagManager`

Metro recompiles `AppletTileSectionProvider` and its `MetroFactory`, producing a `create()` with
**11** `Provider` parameters. However, `VariantSandboxedComponent` — the graph entry point — is
**not** recompiled in the incremental build, so it still calls the old 12-parameter signature.

At runtime the JVM finds no matching method → `NoSuchMethodError`.

---

## Dependency Injection Setup

### 1. Class declaration

**File:**
`money/applets/sections/src/main/java/com/squareup/cash/money/applets/sections/AppletTileSectionProvider.kt`

```kotlin
class AppletTileSectionProvider
@AssistedInject
constructor(
  private val applets: Set<Applet>,
  // ... other dependencies ...
  @Assisted private val navigator: Navigator,
) : SectionProvider {

  @AssistedFactory
  interface Factory : SectionProvider.Factory {
    override fun create(navigator: Navigator): AppletTileSectionProvider
  }
}
```

### 2. Factory bound into `Set<SectionProvider.Factory>`

**File:**
`money/presenters/src/main/java/com/squareup/cash/money/presenters/MoneyPresentersModule.kt`

```kotlin
@BindingContainer
@ContributesTo(SandboxedActivityScope::class)
abstract class MoneyPresentersModule {

  @Binds
  @IntoSet
  abstract fun bindAppletTileSectionProviderFactory(
    real: AppletTileSectionProvider.Factory
  ): SectionProvider.Factory

  // Other factories also bound @IntoSet:
  //   DisclosureSectionProvider.Factory
  //   MoreWaysToAddMoneySectionProvider.Factory
  //   BannerSectionProvider.Factory
  //   ManagedAccountSectionProvider.Factory
}
```

The `@ContributesTo(SandboxedActivityScope::class)` annotation means these bindings are contributed
directly into the `SandboxedActivityScope` graph.

### 3. `Set<SectionProvider.Factory>` consumed by `RealMoneyProfileManager`

**File:**
`money/presenters/src/main/java/com/squareup/cash/money/presenters/RealMoneyProfileManager.kt`

```kotlin
@Inject
class RealMoneyProfileManager(
  private val sectionProviderFactories: Set<SectionProvider.Factory>,
  @ForScope(SandboxedActivityScope::class) private val activityCoroutineScope: CoroutineScope,
  // ...
) : MoneyProfileManager {

  override fun observeAvailableSections(navigator: Navigator): StateFlow<...> {
    // Calls factory.create(navigator) on each factory in the set,
    // including AppletTileSectionProvider.Factory
    val sectionProviders = sectionProviderFactories.map { it.create(navigator) }
    ...
  }
}
```

### 4. Graph entry point

**File:** `cash-os/src/main/java/com/squareup/cash/ui/SandboxedActivityComponent.kt`

```kotlin
@SingleIn(SandboxedActivityScope::class)
@GraphExtension(
  scope = SandboxedActivityScope::class,
  additionalScopes = [VariantSandboxedActivityScope::class],
)
interface SandboxedActivityComponent {
  @GraphExtension.Factory
  fun interface Factory {
    fun create(
      @Provides activity: MainActivity,
      @Provides @ForScope(SandboxedActivityScope::class) activityCoroutineScope: CoroutineScope,
      // ...
    ): SandboxedActivityComponent
  }
}
```

`VariantSandboxedComponent` extends this and contains the generated
`SandboxedActivityComponentImpl` seen in the stack trace.

### Complete DI chain

```
AppletTileSectionProvider  (@AssistedInject constructor)
    │
    ▼  Metro generates MetroFactory with N Provider params
AppletTileSectionProvider.Factory  (@AssistedFactory)
    │
    ▼  @Binds @IntoSet  (MoneyPresentersModule, @ContributesTo SandboxedActivityScope)
Set<SectionProvider.Factory>  in SandboxedActivityScope
    │
    ▼  constructor injection
RealMoneyProfileManager  (@Inject)
    │
    ▼  @Binds  (MoneyPresentersModule)
MoneyProfileManager  in SandboxedActivityScope
    │
    ▼  injected into
MoneyTabPresenter / MoneyProfilePresenter
    │
    ▼  created via
SandboxedActivityComponent  (graph root, built in MainActivity.onCreate)
```

---

## Gradle Module Setup

### Module under investigation

**Gradle path:** `:money:applets:sections`
**Build file:** `money/applets/sections/build.gradle`

Key dependencies declared in the module:

```gradle
api projects.featureFlags.api          // FeatureFlagManager lives here
api projects.family.profileSelection.api
api projects.money.applets.viewmodels
api projects.money.viewmodels.api
api projects.observability.types
// ...
```

### Modules that depend on `:money:applets:sections`

| Module | Dependency type |
|---|---|
| `:money:applets:sections:fakes` | `api` |
| `:money:presenters` | `api` ← **contains MoneyPresentersModule** |
| `:money:applets:common:presenters` | `api` |
| `:money:fakes` | `api` |
| `:savings:applets:presenters` | `testImplementation` (main + fakes) |
| `:family:applets:presenters` | `testImplementation` (main + fakes) |
| `:paychecks:applets:presenters` | `testImplementation` (fakes only) |
| `:afterpay-applet:applets:presenters` | `testImplementation` (fakes only) |
| `:tax:applets:presenters` | `testImplementation` (fakes only) |
| `:investing:applets:presenters` | `testImplementation` (fakes only) |
| `:earnings-tracker:applets:presenters` | `testImplementation` (fakes only) |
| `:borrow:applets:presenters` | `testImplementation` (fakes only) |
| `:bitcoin:applets:presenters` | `testImplementation` (fakes only) |
| `:wallet:presenters` | `testImplementation` (fakes only) |
| `:benefits:applets:presenters` | `testImplementation` (fakes only) |

The only module that has a **runtime** dependency on the main module and also contributes bindings
into the graph is **`:money:presenters`** (via `MoneyPresentersModule`).

---

## Fix

### Option A — Clean build (confirm the incremental build is the issue)

```bash
./gradlew clean :cash-os:installInternalDebug -q --console=plain
```

A clean build forces Metro to regenerate `VariantSandboxedComponent` against the updated
constructor, aligning both the factory and the call site to the 11-parameter signature.

### Option B — Keep the parameter removed (intended change)

If the intent is to permanently remove `featureFlagManager`, ensure:

1. The parameter is removed (already done on line 73).
2. Any remaining reference to `featureFlagManager` inside the class is removed or replaced (e.g.
   `useRedesignedTiles` is already hardcoded to `false` on line 264–265).
3. The unused import (`com.squareup.cash.featureflags.FeatureFlagManager` and
   `com.squareup.cash.featureflags.AmplitudeExperiments`) should be removed.
4. Run a **clean build** or at minimum invalidate the incremental state of
   `:money:applets:sections` and `:cash-os`.

### Option C — Restore the parameter (revert the change)

Un-comment line 73 to restore the original constructor signature, which will realign the
MetroFactory with the component-generated call site.
