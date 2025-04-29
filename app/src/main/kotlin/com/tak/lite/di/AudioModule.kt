package com.tak.lite.di

import com.tak.lite.network.MeshNetworkManager
import com.tak.lite.network.MeshNetworkManagerImpl
import com.tak.lite.service.AudioStreamingService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {
    @Binds
    @Singleton
    abstract fun bindMeshNetworkManager(impl: MeshNetworkManagerImpl): MeshNetworkManager
} 