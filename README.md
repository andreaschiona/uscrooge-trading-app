# UScrooge Trading App

Applicazione Android (Kotlin + Jetpack Compose) per analisi tecnica e trading crypto su Kraken.

## Panoramica

- Architettura a strati con UI Compose, ViewModel, strategy engine, repository, Room e API Kraken.
- Flusso principale: `MarketAnalysisWorker` -> `TradingRepository.analyzeAllPairs` -> `TradingStrategy.generateSignal` -> eventuale `OrderExecutor.executeSignal`.
- Analisi tecnica multi-indicatore: RSI, MACD, volume, trend, pattern candlestick, livelli di supporto/resistenza.
- Modalita operative: esecuzione manuale (default) o automatica per segnali sopra soglia.
- Persistenza: Room (`uscrooge_database`) + DataStore (`trading_config`).

## Funzionalita principali

- Dashboard portfolio con P/L, posizioni aperte e aggiornamenti periodici.
- Schermata segnali con motivazioni, livello di forza e azioni Execute/Ignore.
- Configurazione completa da Settings: coppie, rischio, soglie, API Kraken, notifiche.
- Worker periodico WorkManager (unique work `market_analysis_work`) per analisi e notifiche.
- Gestione rischio con stop loss, take profit, trailing stop, limiti posizioni/trade.

## Requisiti ambiente

- Android Studio Hedgehog o superiore.
- JDK 17 (obbligatorio).
- Android SDK 34.
- AGP `8.2.0`, Kotlin `1.9.20`, Gradle `8.2`.
- `local.properties` locale con `sdk.dir=...` (non committare).

Se Gradle fallisce con `IllegalArgumentException: 25.0.3`, il processo sta usando una JDK non supportata: passare a JDK 17.

## Build e test

Su Windows usare `gradlew.bat`; su macOS/Linux usare `./gradlew`.

```powershell
# Windows
./gradlew.bat assembleDebug
./gradlew.bat assembleRelease
./gradlew.bat test

# Esempi test mirati
./gradlew.bat :app:testDebugUnitTest --tests "com.uscrooge.app.TechnicalAnalyzerTest"
./gradlew.bat :app:testDebugUnitTest --tests "com.uscrooge.app.TechnicalAnalyzerTest.RSI calculation with oversold condition"
```

Output APK:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Deploy rapido emulatore (Windows)

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Deploy-AppToEmulator.ps1 -AvdName "Pixel_8_API_34"
```

Alternative utili:

```powershell
# Emulatore gia avviato
powershell -ExecutionPolicy Bypass -File .\scripts\Deploy-AppToEmulator.ps1 -DeviceId emulator-5554

# Reinstall senza build
powershell -ExecutionPolicy Bypass -File .\scripts\Deploy-AppToEmulator.ps1 -AvdName "Pixel_8_API_34" -SkipBuild
```

## Configurazione Kraken

1. Creare API key da Kraken (`Settings -> API`).
2. Abilitare solo i permessi necessari:
   - Query Funds
   - Query Open Orders & Trades
   - Query Closed Orders & Trades
   - Create & Modify Orders
   - Cancel/Close Orders
3. Non abilitare `Withdraw Funds`.
4. Inserire API key/secret in Settings dell'app.

## Configurazione trading (default applicativi)

- Coppie: `BTC/EUR, ETH/EUR, SOL/EUR, XRP/EUR`
- Min signal strength: `65%`
- Strong signal threshold: `80%`
- Stop loss: `2%`
- Take profit: `4%`
- Trailing stop: `1.5%`
- Max slippage: `0.5%`
- Intervallo analisi: `5 minuti`

## Note operative importanti

- `UScroogeApplication` istanzia `KrakenApiClient`, `TradingStrategy` e `OrderExecutor` con config di default/empty e non li ricostruisce automaticamente ai cambi Settings.
- `OrderExecutor.updatePositionPrices()` legge da Flow; chiamate improprie da worker possono allungare l'esecuzione del job.
- Room usa `fallbackToDestructiveMigration()`: cambi schema senza migration possono cancellare i dati locali.
- Il reschedule del worker usa `ExistingPeriodicWorkPolicy.REPLACE`.

## Risoluzione problemi (build)

- `resource ... ic_launcher_foreground not found`: verificare riferimenti `@drawable/ic_launcher_foreground` nelle adaptive icon.
- `file name must end with .xml or .png`: rimuovere file non supportati da `app/src/main/res/**`.
- `duplicate resources`: eliminare definizioni duplicate in `values/*`.
- Errore Kotlin/KSP: controllare output completo (`--stacktrace`) e verificare import mancanti o annotazioni Room incoerenti.

## Struttura progetto

```text
app/
  src/main/java/com/uscrooge/app/
    analysis/
    data/
      api/
      local/
      model/
      repository/
    executor/
    notification/
    strategy/
    ui/
      screen/
      theme/
      viewmodel/
    worker/
    MainActivity.kt
    UScroogeApplication.kt
  build.gradle.kts
build.gradle.kts
settings.gradle.kts
scripts/
```

## Sicurezza e disclaimer

- Non committare mai API key o segreti.
- Usare 2FA su Kraken e permessi API minimi.
- Il trading crypto comporta rischi elevati: usare budget ridotti in fase iniziale.
- Questo software e fornito "as is" senza garanzie.
