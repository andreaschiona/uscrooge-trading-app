package com.uscrooge.app.di

import android.content.Context
import com.google.gson.Gson
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.api.CoinGeckoApiClient
import com.uscrooge.app.data.local.AuditLogDao
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.local.TradingDatabase
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.strategy.PositionSelectionStrategy
import com.uscrooge.app.update.UpdateChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("AppScope", "Unhandled coroutine exception", throwable)
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingLevel = if (android.util.Log.isLoggable("OkHttp", android.util.Log.DEBUG)) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .dispatcher(Dispatcher().apply { maxRequests = 10; maxRequestsPerHost = 5 })
            .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
            .build()
    }

    @Provides
    @Singleton
    fun provideTechnicalAnalyzer(): TechnicalAnalyzer = TechnicalAnalyzer()

    @Provides
    @Singleton
    fun provideConfigRepository(
        @ApplicationContext context: Context
    ): ConfigRepository = ConfigRepository(context)

    @Provides
    @Singleton
    fun provideGitHubIssueReporter(
        @ApplicationContext context: Context
    ): GitHubIssueReporter = GitHubIssueReporter(context)

    @Provides
    @Singleton
    fun provideUpdateChecker(): UpdateChecker = UpdateChecker()

    @Provides
    @Singleton
    fun provideCoinGeckoApiClient(): CoinGeckoApiClient = CoinGeckoApiClient()

    @Provides
    @Singleton
    fun providePositionSelectionStrategy(
        coinGeckoApi: CoinGeckoApiClient
    ): PositionSelectionStrategy = PositionSelectionStrategy(coinGeckoApi)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): TradingDatabase = TradingDatabase.getDatabase(context)

    @Provides
    fun provideSignalDao(db: TradingDatabase): TradingSignalDao = db.signalDao()

    @Provides
    fun provideOrderDao(db: TradingDatabase): OrderDao = db.orderDao()

    @Provides
    fun providePositionDao(db: TradingDatabase): PositionDao = db.positionDao()

    @Provides
    fun provideTradeJournalDao(db: TradingDatabase): TradeJournalDao = db.tradeJournalDao()

    @Provides
    fun provideAuditLogDao(db: TradingDatabase): AuditLogDao = db.auditLogDao()
}
