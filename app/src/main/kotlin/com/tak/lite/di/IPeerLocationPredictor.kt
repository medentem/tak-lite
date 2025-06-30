package com.tak.lite.di

import com.tak.lite.data.model.ConfidenceCone
import com.tak.lite.data.model.PredictionConfig
import com.tak.lite.data.model.LocationPrediction
import com.tak.lite.model.PeerLocationHistory

interface IPeerLocationPredictor {
    fun predictPeerLocation(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction?
    fun generateConfidenceCone(prediction: LocationPrediction,
                               history: PeerLocationHistory,
                               config: PredictionConfig) : ConfidenceCone?
}