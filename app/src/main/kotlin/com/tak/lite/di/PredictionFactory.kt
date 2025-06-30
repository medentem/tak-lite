package com.tak.lite.di

import android.content.SharedPreferences
import com.tak.lite.data.model.PredictionModel
import com.tak.lite.intelligence.KalmanPeerLocationPredictor
import com.tak.lite.intelligence.LinearPeerLocationPredictor
import com.tak.lite.intelligence.ParticlePeerLocationPredictor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory class that provides the appropriate predictor based on the current model selection
 */
@Singleton
class PredictionFactory @Inject constructor(
    private val linearPredictor: LinearPeerLocationPredictor,
    private val kalmanPredictor: KalmanPeerLocationPredictor,
    private val particlePredictor: ParticlePeerLocationPredictor
) {
    /**
     * Get a specific predictor by model type
     */
    fun getPredictor(model: PredictionModel): IPeerLocationPredictor {
        return when (model) {
            PredictionModel.LINEAR -> linearPredictor
            PredictionModel.KALMAN_FILTER -> kalmanPredictor
            PredictionModel.PARTICLE_FILTER -> particlePredictor
        }
    }
} 