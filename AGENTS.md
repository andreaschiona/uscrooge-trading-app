# Agent Notes

## Repository shape
- Single-module Android app (`:app`) using Kotlin + Jetpack Compose; no monorepo/package boundaries (`settings.gradle.kts`).
- Main runtime wiring starts in `app/src/main/java/com/uscrooge/app/UScroogeApplication.kt` and UI entry is `app/src/main/java/com/uscrooge/app/MainActivity.kt`.
- Core flow: `MarketAnalysisWorker` -> `TradingRepository.analyzeAllPairs` -> `TradingStrategy.generateSignal` -> optional `OrderExecutor.executeSignal`.

## Verified build/test commands
- Windows: use `./gradlew.bat`; Unix/macOS: `./gradlew`.
- Build debug APK: `./gradlew.bat assembleDebug`
- Build release APK: `./gradlew.bat assembleRelease`
- Unit tests (all): `./gradlew.bat test`
- Single unit test class: `./gradlew.bat :app:testDebugUnitTest --tests "com.uscrooge.app.TechnicalAnalyzerTest"`
- Single unit test method: `./gradlew.bat :app:testDebugUnitTest --tests "com.uscrooge.app.TechnicalAnalyzerTest.RSI calculation with oversold condition"`

## Environment/toolchain constraints
- Project targets AGP `8.2.0`, Kotlin `1.9.20`, Gradle `8.2`, `compileSdk 34`, `minSdk 26`, Java/Kotlin JVM target 17.
- `local.properties` is required for local SDK path (`sdk.dir=...`) and should stay local-only.
- If Gradle fails early with `IllegalArgumentException: 25.0.3`, the process is running on an unsupported JDK; switch to JDK 17.

## Data/storage and background behavior
- Persistent data is split between Room (`TradingDatabase`, DB name `uscrooge_database`) and DataStore preferences (`trading_config`).
- Room uses `fallbackToDestructiveMigration()`; schema changes without migrations will wipe local DB data.
- Background analysis is scheduled via WorkManager unique periodic work name `market_analysis_work`; rescheduling uses `ExistingPeriodicWorkPolicy.REPLACE`.

## High-impact implementation gotchas
- `UScroogeApplication` initializes `KrakenApiClient`, `TradingStrategy`, and `OrderExecutor` with default/empty config values and does not rebuild them when `ConfigRepository` updates. Do not assume Settings changes are automatically reflected in those instances.
- `OrderExecutor.updatePositionPrices()` collects a Flow indefinitely; calling it from a Worker (`MarketAnalysisWorker.doWork`) can keep work running longer than expected.
