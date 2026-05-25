package com.uscrooge.app.di

import android.content.Context
import com.uscrooge.app.analysis.TechnicalAnalyzer
import com.uscrooge.app.data.local.OrderDao
import com.uscrooge.app.data.local.PositionDao
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.local.TradingDatabase
import com.uscrooge.app.data.local.TradingSignalDao
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.integration.GitHubIssueReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marker for the application-lifetime coroutine scope used by long-running
 * collectors such as [BrokerRegistry.start].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
}
