package com.tak.lite.di

import android.content.Context
import android.content.SharedPreferences
import com.tak.lite.network.MeshNetworkProtocol
import com.tak.lite.network.MeshNetworkService
import com.tak.lite.network.MeshNetworkManager
import com.tak.lite.network.MeshNetworkManagerImpl
import com.tak.lite.repository.AnnotationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.tak.lite.network.MeshtasticBluetoothProtocol
import com.tak.lite.network.MeshProtocolProvider

@Module
@InstallIn(SingletonComponent::class)
object NetworkProvidesModule {
    
    @Provides
    @Singleton
    fun provideMeshNetworkService(): MeshNetworkService {
        return MeshNetworkService()
    }
    
    @Provides
    @Singleton
    fun provideMeshNetworkManager(
        @ApplicationContext context: Context
    ): MeshNetworkManager {
        return MeshNetworkManagerImpl(context)
    }
    
    @Provides
    @Singleton
    fun provideAnnotationRepository(): AnnotationRepository {
        return AnnotationRepository()
    }
}