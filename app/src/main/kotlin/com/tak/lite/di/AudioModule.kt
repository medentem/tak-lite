package com.tak.lite.di

import com.tak.lite.service.AudioStreamingService
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    // Audio-related bindings will go here
} 