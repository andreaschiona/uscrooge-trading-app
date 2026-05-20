package com.uscrooge.app.di

import com.uscrooge.app.data.api.KrakenApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Exposes the mutable broker API clients owned by [BrokerRegistry] to the
 * Hilt graph. We provide a single shared instance per app — its credentials
 * are kept in sync with [com.uscrooge.app.data.repository.ConfigRepository]
 * by [BrokerRegistry.start].
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideKrakenApiClient(registry: BrokerRegistry): KrakenApiClient =
        registry.krakenApiClient
}
