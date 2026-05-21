package com.uscrooge.app.di

import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.KrakenApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideKrakenApiClient(registry: BrokerRegistry): KrakenApiClient =
        registry.krakenApiClient

    @Provides
    @Singleton
    fun provideAlpacaApiClient(registry: BrokerRegistry): AlpacaApiClient =
        registry.alpacaApiClient
}
