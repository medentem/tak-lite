package com.tak.lite.di

import android.content.Context
import android.content.SharedPreferences
import com.tak.lite.data.model.PredictionModel
import com.tak.lite.intelligence.KalmanPeerLocationPredictor
import com.tak.lite.intelligence.LinearPeerLocationPredictor
import com.tak.lite.intelligence.ParticlePeerLocationPredictor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PredictionModule {
    
    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    }
    
    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json { ignoreUnknownKeys = true }
    }
} 