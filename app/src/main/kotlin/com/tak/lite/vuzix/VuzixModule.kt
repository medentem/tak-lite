package com.tak.lite.vuzix

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * Hilt module for Vuzix Z100 Smart Glasses dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object VuzixModule {

    @Provides
    @Singleton
    fun provideVuzixManager(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope
    ): VuzixManager {
        return VuzixManager(context, coroutineScope)
    }

    @Provides
    @Singleton
    fun provideMinimapService(
        @ApplicationContext context: Context,
        meshNetworkService: com.tak.lite.network.MeshNetworkService,
        vuzixManager: VuzixManager
    ): MinimapService {
        return MinimapService(context, meshNetworkService, vuzixManager)
    }

    @Provides
    @Singleton
    fun provideCoroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Main)
    }
}
