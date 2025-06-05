package com.tak.lite.di

import android.content.Context
import com.tak.lite.network.Layer2MeshNetworkManager
import com.tak.lite.network.Layer2MeshNetworkManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkProvidesModule {
    
    @Provides
    @Singleton
    fun provideMeshNetworkManager(
        @ApplicationContext context: Context
    ): Layer2MeshNetworkManager {
        return Layer2MeshNetworkManagerImpl(context)
    }
}