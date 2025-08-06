package com.tak.lite.di

import android.content.Context
import com.tak.lite.intelligence.CoverageCalculator
import com.tak.lite.intelligence.FresnelZoneAnalyzer
import com.tak.lite.intelligence.PeerNetworkAnalyzer
import com.tak.lite.intelligence.TerrainAnalyzer
import com.tak.lite.util.CryptoQRCodeGenerator
import com.tak.lite.util.DonationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }
    
    @Provides
    @Singleton
    fun provideFresnelZoneAnalyzer(): FresnelZoneAnalyzer {
        return FresnelZoneAnalyzer()
    }
    
    @Provides
    @Singleton
    fun provideTerrainAnalyzer(@ApplicationContext context: Context): TerrainAnalyzer {
        return TerrainAnalyzer(context)
    }
    
    @Provides
    @Singleton
    fun providePeerNetworkAnalyzer(): PeerNetworkAnalyzer {
        return PeerNetworkAnalyzer()
    }
    
    @Provides
    @Singleton
    fun provideCoverageCalculator(
        fresnelZoneAnalyzer: FresnelZoneAnalyzer,
        terrainAnalyzer: TerrainAnalyzer,
        peerNetworkAnalyzer: PeerNetworkAnalyzer,
        meshNetworkRepository: com.tak.lite.repository.MeshNetworkRepository,
        @ApplicationContext context: Context
    ): CoverageCalculator {
        return CoverageCalculator(
            fresnelZoneAnalyzer,
            terrainAnalyzer,
            peerNetworkAnalyzer,
            meshNetworkRepository,
            context
        )
    }

    @Provides
    @Singleton
    fun provideDonationManager(@ApplicationContext context: Context): DonationManager {
        return DonationManager(context)
    }

    @Provides
    @Singleton
    fun provideCryptoQRCodeGenerator(): CryptoQRCodeGenerator {
        return CryptoQRCodeGenerator()
    }
} 