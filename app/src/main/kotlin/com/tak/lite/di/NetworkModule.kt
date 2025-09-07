package com.tak.lite.di

import android.content.Context
import com.tak.lite.network.HybridSyncManager
import com.tak.lite.network.Layer2MeshNetworkManager
import com.tak.lite.network.Layer2MeshNetworkManagerImpl
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.network.ServerApiService
import com.tak.lite.network.SocketService
import com.tak.lite.repository.AnnotationRepository
import com.tak.lite.rtc.RtcEngine
import com.tak.lite.rtc.webrtc.WebRtcEngine
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
    fun provideRtcEngine(
        @ApplicationContext context: Context
    ): RtcEngine = WebRtcEngine(context)

    @Provides
    @Singleton
    fun provideMeshNetworkManager(
        @ApplicationContext context: Context,
        rtcEngine: RtcEngine
    ): Layer2MeshNetworkManager = Layer2MeshNetworkManagerImpl(context, rtcEngine)

    @Provides
    @Singleton
    fun provideServerApiService(
        @ApplicationContext context: Context
    ): ServerApiService = ServerApiService(context)

    @Provides
    @Singleton
    fun provideSocketService(
        @ApplicationContext context: Context
    ): SocketService = SocketService(context)

    @Provides
    @Singleton
    fun provideHybridSyncManager(
        @ApplicationContext context: Context,
        meshProtocolProvider: MeshProtocolProvider,
        serverApiService: ServerApiService,
        socketService: SocketService,
        annotationRepository: AnnotationRepository
    ): HybridSyncManager = HybridSyncManager(
        context,
        meshProtocolProvider,
        serverApiService,
        socketService,
        annotationRepository
    )
}