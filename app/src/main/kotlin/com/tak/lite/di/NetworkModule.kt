package com.tak.lite.di

import android.content.Context
import com.tak.lite.network.Layer2MeshNetworkManager
import com.tak.lite.network.Layer2MeshNetworkManagerImpl
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
}