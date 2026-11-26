package com.nuvio.tv.di

import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.livetv.lucid.LucidPluginBridge
import com.nuvio.tv.livetv.lucid.NoOpLucidPluginBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {
    @Provides
    @Singleton
    fun providePluginManager(): PluginManager {
        return PluginManager()
    }

    @Provides
    @Singleton
    fun provideLucidPluginBridge(): LucidPluginBridge = NoOpLucidPluginBridge
}
