# Agent Notes

## Repository shape
- Single-module Android app (`:app`) using Kotlin + Jetpack Compose; no monorepo/package boundaries (`settings.gradle.kts`).
- Main runtime wiring starts in `app/src/main/java/com/uscrooge/app/UScroogeApplication.kt` and UI entry is `app/src/main/java/com/uscrooge/app/MainActivity.kt`.
- Core flow: `MarketAnalysisWorker` -> exit monitoring -> circuit breaker check -> `TradingRepository.analyzeAllPairs` -> `TradingStrategy.generateSignal` -> optional `OrderExecutor.executeSignal` -> protective orders on exchange.

## Verified build/test commands
- Windows: use `./gradlew.bat`; Unix/macOS: `./gradlew`.
- Build debug APK: `./gradlew.bat assembleDebug`
- Build release APK: `./gradlew.bat assembleRelease`
- Unit tests (all): `./gradlew.bat test`
- Single unit test class: `./gradlew.bat :app:testDebugUnitTest --tests "com.uscrooge.app.TechnicalAnalyzerTest"`
- Single unit test method: `./gradlew.bat :app:testDebugUnitTest --tests "com.uscrooge.app.TechnicalAnalyzerTest.RSI calculation with oversold condition"`

## Environment/toolchain constraints
- Project targets AGP `8.6.1`, Kotlin `2.1.0`, KSP `2.1.0-1.0.29`, Hilt `2.53`, Gradle `8.10`, `compileSdk 35`, `targetSdk 35`, `minSdk 26`, Java/Kotlin JVM target 17.
- `local.properties` is required for local SDK path (`sdk.dir=...`) and should stay local-only.
- If Gradle fails early with `IllegalArgumentException: 25.0.3`, the process is running on an unsupported JDK; switch to JDK 17.

## Data/storage and background behavior
- Persistent data is split between Room (`TradingDatabase`, DB name `uscrooge_database`) and DataStore preferences (`trading_config`).
- Room uses `fallbackToDestructiveMigration()`; schema changes without migrations will wipe local DB data.
- Background analysis is scheduled via WorkManager unique periodic work name `market_analysis_work`; rescheduling uses `ExistingPeriodicWorkPolicy.REPLACE`.

## Key architectural components
- `CircuitBreaker`: halts trading on max daily drawdown, consecutive failures, or max daily trades. Configurable cooldown.
- `OrderExecutor.monitorExitConditions()`: evaluates stop-loss, take-profit, trailing stop on every Worker cycle.
- Protective orders: after each BUY, stop-loss and take-profit orders are placed directly on Kraken as safety net.
- `BrokerRegistry.applyConfig()`: propagates config changes to KrakenApiClient, TradingStrategy, and OrderExecutor.

## CI/CD
- **Release workflow** (`.github/workflows/release.yml`): on push to `main`, runs tests, bumps patch version, builds signed release APK, creates GitHub Release with APK attached.
- **OpenCode workflow** (`.github/workflows/opencode.yml`): responds to issue/PR comments via opencode agent.

## OpenCode workflow resilience
- `opencode.json`: provider timeout (`timeout: 120000ms`, `chunkTimeout: 30000ms`) prevents indefinite blocking on LLM calls
- `opencode.yml`:
  - `timeout-minutes: 60`: overall job timeout
  - `variant: minimal`: reduces reasoning effort for faster replies
  - Bash retry loop (3 attempts, 15s delay) around `opencode github run`
  - Action steps replicated inline (version fetch, cache, install, PATH) from composite action for full retry control
- **Alternative**: set `GEMINI_API_KEY` secret and change model to `gemini/gemini-2.5-flash` for a more reliable free model

## High-impact implementation gotchas
- `OrderExecutor.updatePositionPrices()` uses `positionDao.getOpenPositions().first()` (single snapshot). This is safe in Worker context.
- Room uses destructive migration: any schema change (adding columns to entities) wipes DB. Use proper migrations for production data preservation.
- `Position.peakPrice` tracks the highest price since entry for trailing stop. It must be updated via `calculateCurrentValue()` on every price refresh.
- Kraken `stop-loss` and `take-profit` order types require the `price` parameter (trigger price).
