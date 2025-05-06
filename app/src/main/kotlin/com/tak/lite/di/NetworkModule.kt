package com.tak.lite.di

import android.content.Context
import com.tak.lite.network.MeshNetworkProtocol
import com.tak.lite.network.MeshNetworkService
import com.tak.lite.repository.AnnotationRepository
import com.tak.lite.network.MeshNetworkManager
import com.tak.lite.network.MeshNetworkManagerImpl
import dagger.Module
import dagger.Provides
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkProvidesModule {
    
    @Provides
    @Singleton
    fun provideMeshNetworkProtocol(): MeshNetworkProtocol {
        return MeshNetworkProtocol()
    }
    
    @Provides
    @Singleton
    fun provideMeshNetworkService(
        @ApplicationContext context: Context,
        meshProtocol: MeshNetworkProtocol
    ): MeshNetworkService {
        return MeshNetworkService(context, meshProtocol)
    }
    
    @Provides
    @Singleton
    fun provideAnnotationRepository(
        meshProtocol: MeshNetworkProtocol
    ): AnnotationRepository {
        val repo = AnnotationRepository(meshProtocol)
        meshProtocol.setAnnotationProvider { repo.annotations.value }
        return repo
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {
    
    @Binds
    @Singleton
    abstract fun bindMeshNetworkManager(impl: MeshNetworkManagerImpl): MeshNetworkManager
} 