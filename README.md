# UScrooge Trading App

An Android application for automated trading across cryptocurrency (Kraken) and stock markets (Alpaca), built with Kotlin and Jetpack Compose. UScrooge analyzes market conditions using multi-indicator technical analysis and executes trades autonomously to maximize capital returns.

## Features

- **Multi-Broker Support** - Trade crypto on Kraken and stocks on Alpaca from a single dashboard
- **Automated Trading** - Fully autonomous trade execution based on configurable signal strength thresholds
- **Dynamic Symbol Discovery** - Crypto pairs fetched live from Kraken's public API; stocks from Alpaca's asset catalogue. User wishlist is always scanned first; remaining slots filled dynamically up to a configurable cap
- **Multi-Indicator Strategy** - RSI, MACD, Bollinger Bands, ADX, Stochastic RSI, volume analysis, candlestick patterns, support/resistance
- **Multi-Timeframe Confirmation** - Signals validated against higher timeframe trends (1h, 4h, daily) to reduce false entries
- **Smart Order Execution** - Limit orders for moderate signals (lower fees), market orders for strong signals (immediate fill)
- **Real-Time WebSocket Streaming** - Live crypto price updates via Kraken WebSocket v2 API
- **Circuit Breaker** - Automatic trading halt on max daily drawdown, consecutive failures, or daily trade limit
- **Active Exit Monitoring** - Stop-loss, take-profit, and trailing stop evaluated every cycle
- **Exchange-Level Protection** - Stop-loss and take-profit orders placed directly on exchanges as safety net
- **Configurable Risk Management** - Position sizing, max open positions, slippage guards, and more
- **Push Notifications** - Real-time alerts for signals, executions, and errors
- **Broker-Agnostic Dashboard** - Unified view of all positions with visual broker indicators (CRYPTO/STOCK)

## Supported Brokers

| Broker     | Market           | Assets                               | Discovery                                      | Paper Trading    |
|------------|------------------|--------------------------------------|------------------------------------------------|------------------|
| **Kraken** | Cryptocurrency   | Any EUR pair listed on Kraken        | Dynamic via `/0/public/AssetPairs` (1 h cache) | N/A (spot only)  |
| **Alpaca** | US Stocks        | All active, fractionable US equities | Dynamic via `/v2/assets` (1 h cache)           | Yes (paper mode) |

Each broker operates independently. If credentials for a broker are not configured, that market is simply disabled — the app continues to function normally with the configured brokers.

### Symbol Discovery Model

For both brokers the scanning list is built as:

```text
scan_list = union(user_wishlist, dynamic_api_list)
            sorted: wishlist items first
            capped at maxCryptoPairsToScan / maxStockPairsToScan
```

User-configured pairs are always scanned regardless of whether they appear in the live API list (useful for pairs added to Kraken/Alpaca after the cache was populated). The dynamic list fills remaining capacity up to the configured cap. Both lists are cached for 1 hour to avoid excessive API calls.

## Trading Strategy

UScrooge uses a composite scoring system that combines multiple technical indicators:

| Indicator | Weight | Signal |
|-----------|--------|--------|
| RSI (14) | 20% | Oversold <30 = buy, Overbought >70 = sell |
| MACD (12/26/9) | 20% | Crossover detection and momentum direction |
| Bollinger Bands (20, 2σ) | 10% | Price near lower band = buy, upper = sell |
| ADX (14) | 10% | >25 confirms trend, <20 reduces confidence |
| Stochastic RSI | 10% | <20 = buy confirmation, >80 = sell confirmation |
| Volume | 10% | Above average confirms, below average weakens |
| Candlestick Patterns | 10% | Hammer, Engulfing, Morning/Evening Star, etc. |
| Trend | 10% | 20-period directional analysis |

**Signal generation requires:**
- Score >= 3.0 from buy/sell scoring
- Overall strength >= 65% (configurable)
- Auto-execution requires >= 80% strength
- Higher timeframe trend confirmation (when enabled)

**Risk controls:**
- Default 2% stop-loss, 4% take-profit (2:1 R/R ratio)
- 1.5% trailing stop from peak price
- Max 25% of available balance per trade
- Max 3 concurrent positions, 5 trades/day
- Circuit breaker: halts on 5% daily drawdown or 3 consecutive failures

## Project Structure

```
app/src/main/java/com/uscrooge/app/
├── USCroogeApplication.kt          # Application entry point
├── MainActivity.kt                 # Compose UI entry
├── analysis/
│   └── TechnicalAnalyzer.kt        # All indicator calculations
├── data/
│   ├── api/
│   │   ├── BrokerApi.kt            # Unified broker interface
│   │   ├── KrakenApiClient.kt      # Kraken REST API client
│   │   ├── KrakenApiService.kt     # Kraken Retrofit interface
│   │   ├── KrakenAuthInterceptor.kt # HMAC-SHA512 authentication
│   │   ├── KrakenWebSocketClient.kt # Real-time WebSocket streaming
│   │   ├── AlpacaApiClient.kt      # Alpaca REST API client
│   │   ├── AlpacaApiService.kt     # Alpaca Retrofit interface
│   │   └── AlpacaAuthInterceptor.kt # Header-based authentication
│   ├── local/
│   │   ├── TradingDao.kt           # Room DAOs
│   │   └── TradingDatabase.kt      # Room database
│   ├── model/                      # Data classes and enums
│   └── repository/
│       ├── ConfigRepository.kt     # DataStore preferences
│       └── TradingRepository.kt    # Multi-broker market data + signal orchestration
├── di/
│   ├── ApiModule.kt                # Hilt DI module
│   ├── AppModule.kt                # App-wide providers
│   └── BrokerRegistry.kt          # Multi-broker config propagation
├── executor/
│   ├── CircuitBreaker.kt           # Trading halt logic
│   └── OrderExecutor.kt           # Multi-broker order placement + exit monitoring
├── notification/
│   └── NotificationHelper.kt      # Push notifications
├── strategy/
│   └── TradingStrategy.kt         # Signal generation + exit conditions
├── ui/                            # Compose screens (Dashboard, Signals, Settings)
└── worker/
    └── MarketAnalysisWorker.kt    # Background periodic analysis
```

## CI/CD

The project uses GitHub Actions for continuous delivery:

### Release Workflow (`.github/workflows/release.yml`)
Triggered on every push to `main`:
1. Runs unit tests (creates GitHub issue on failure)
2. Increments patch version automatically
3. Builds signed release APK
4. Commits version bump
5. Creates a GitHub Release with the APK attached

### OpenCode Workflow (`.github/workflows/opencode.yml`)
Responds to issue and PR comments for AI-assisted development via the opencode agent.

## Installation

### From GitHub Releases (recommended)

1. Download the latest APK from [Releases](https://github.com/andreaschiona/uscrooge-trading-app/releases/latest)
2. On your Android device, enable **Settings > Security > Unknown Sources** (or per-app install permission)
3. Open the downloaded APK and install
4. Requires Android 8.0 (API 26) or higher

### Build from Source

```bash
# Clone
git clone https://github.com/andreaschiona/uscrooge-trading-app.git
cd uscrooge-trading-app

# Create local.properties with your Android SDK path
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build debug APK
./gradlew assembleDebug

# APK will be at app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK with API 34.

## Configuration

After installation, open the app and navigate to **Settings**:

### Kraken API (Cryptocurrency)
1. Create API keys on [Kraken](https://www.kraken.com/u/security/api) with permissions: Query Funds, Create & Modify Orders, Cancel/Close Orders, Query Open Orders & Trades
2. Enter your **API Key** and **API Secret** in the app settings
3. Leave empty to disable crypto trading

### Alpaca API (Stocks)
1. Create API keys on [Alpaca](https://app.alpaca.markets/) (paper or live account)
2. Enable **Stock Trading** toggle in settings
3. Choose **Paper Trading** mode for testing with simulated money
4. Enter your **API Key** and **API Secret**
5. Configure the list of stock symbols to monitor (e.g., `AAPL/USD, MSFT/USD, GOOGL/USD`)
6. Leave API keys empty to disable stock trading

### Key Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| Automatic Trading | OFF | Enable autonomous execution |
| Crypto Trading Pairs | BTC/EUR, ETH/EUR, SOL/EUR, XRP/EUR | Wishlist — always scanned, plus dynamic Kraken pairs fill remaining slots |
| Stock Symbols | AAPL/USD, MSFT/USD, GOOGL/USD | Wishlist — always scanned, plus dynamic Alpaca assets fill remaining slots |
| Max Crypto Pairs to Scan | 15 | Total cap for crypto pairs (wishlist + dynamic) |
| Max Stock Pairs to Scan | 20 | Total cap for stock symbols (wishlist + dynamic) |
| Risk Per Trade | 25% | Max percentage of balance per trade |
| Max Open Positions | 3 | Concurrent position limit |
| Max Daily Trades | 5 | Daily trade cap |
| Stop Loss | 2% | Loss threshold for exit |
| Take Profit | 4% | Profit target |
| Trailing Stop | 1.5% | Distance from peak price |
| Min Signal Strength | 65% | Minimum confidence to generate signal |
| Strong Signal Threshold | 80% | Minimum for auto-execution |
| Use Limit Orders | ON | Use limit orders for moderate signals |
| Circuit Breaker | ON | Auto-halt on excessive losses |
| Max Daily Drawdown | 5% | Drawdown threshold for halt |
| Check Interval | 300s | Analysis frequency |

### Dashboard

The dashboard displays all open positions from both brokers in a unified view. Each position card shows a broker badge:
- **CRYPTO** (orange) — position from Kraken
- **STOCK** (blue) — position from Alpaca

Portfolio summary aggregates total value, P/L, and available balance across all configured brokers.

### Risk Warning

This software is provided as-is for educational and personal use. Trading cryptocurrencies and stocks carries significant risk of capital loss. Past performance does not guarantee future results. Always start with small amounts and monitor the system closely. Use Alpaca's paper trading mode to test strategies without real money.

## License

Private repository. All rights reserved.
