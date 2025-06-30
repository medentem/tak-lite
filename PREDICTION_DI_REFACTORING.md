# Peer Location Prediction DI Refactoring

## Overview

This refactoring implements dependency injection for the peer location prediction system, allowing the application to dynamically select the appropriate prediction algorithm based on user settings.

## Changes Made

### 1. Created PredictionModule (app/src/main/kotlin/com/tak/lite/di/PredictionModule.kt)

- **Purpose**: Provides dependency injection for prediction-related components
- **Components**:
  - `SharedPreferences` for storing user settings
  - `Json` serializer for configuration persistence
  - Individual predictor instances (Linear, Kalman, Particle)
  - `PredictionFactory` for dynamic predictor selection

### 2. Created PredictionFactory (app/src/main/kotlin/com/tak/lite/di/PredictionFactory.kt)

- **Purpose**: Factory class that provides the appropriate predictor based on current model selection
- **Methods**:
  - `getCurrentPredictor()`: Returns predictor based on user's selected model
  - `getPredictor(model)`: Returns specific predictor by model type
  - `getCurrentModel()`: Returns the currently selected model from preferences

### 3. Updated PeerLocationHistoryRepository

- **Changes**:
  - Removed direct instantiation of `LocationPredictionEngine`
  - Added DI dependencies: `PredictionFactory`, `Json`, `SharedPreferences`
  - Updated `updatePrediction()` method to use factory-selected predictor
  - Simplified prediction logic by using the common `IPeerLocationPredictor` interface

### 4. Fixed ParticlePeerLocationPredictor

- **Issue**: Particle filter wasn't including `predictedParticles` in `LocationPrediction`
- **Fix**: Added `predictedParticles = predictedParticles` to the `LocationPrediction` constructor

## Architecture Benefits

### 1. Separation of Concerns
- Each predictor is now a separate, injectable component
- Repository focuses on data management, not prediction algorithm selection
- Factory handles the logic of selecting the right predictor

### 2. Runtime Model Switching
- Users can change prediction models in settings
- Changes are immediately reflected in new predictions
- No need to restart the application

### 3. Testability
- Each predictor can be tested independently
- Mock predictors can be easily injected for testing
- Factory can be tested for correct predictor selection

### 4. Maintainability
- Adding new prediction algorithms is straightforward
- Changes to one predictor don't affect others
- Clear dependency graph through DI

## Usage

### In Repository
```kotlin
@Inject constructor(
    private val predictionFactory: PredictionFactory,
    // ... other dependencies
) {
    private fun updatePrediction(peerId: String, history: PeerLocationHistory) {
        val predictor = predictionFactory.getPredictor(currentModel)
        val prediction = predictor.predictPeerLocation(history, config)
        val confidenceCone = predictor.generateConfidenceCone(prediction, history, config)
        // ... update state
    }
}
```

### Model Switching
```kotlin
fun setPredictionModel(model: PredictionModel) {
    _selectedModel.value = model
    prefs.edit().putString("prediction_model", model.name).apply()
    updateAllPredictions() // Uses new model immediately
}
```

## Available Predictors

1. **LinearPeerLocationPredictor**
   - Simple linear extrapolation
   - Best for straight, constant movement
   - Fastest and least resource-intensive

2. **KalmanPeerLocationPredictor**
   - Kalman filter for noisy data
   - Good for moderate speed with noise reduction
   - Middle ground between complexity and accuracy

3. **ParticlePeerLocationPredictor**
   - Particle filter for complex motion
   - Best for erratic or unpredictable movement
   - Most resource-intensive but most accurate

## Configuration

The selected model is stored in `SharedPreferences` with the key `"prediction_model"`. The factory reads this value to determine which predictor to provide.

## Testing

A demonstration class `PredictionFactoryTest` is included to show how the DI system works and can be used for integration testing. 