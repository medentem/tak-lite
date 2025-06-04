package com.tak.lite.di

import android.content.Context
import com.tak.lite.network.MeshNetworkManager
import com.tak.lite.network.MeshNetworkManagerImpl
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
    ): MeshNetworkManager {
        return MeshNetworkManagerImpl(context)
    }
}