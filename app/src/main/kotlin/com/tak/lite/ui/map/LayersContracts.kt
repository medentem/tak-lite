package com.tak.lite.ui.map

interface MapControllerProvider {
    fun getMapController(): MapController?
    fun getLayersTarget(): LayersTarget?
}

interface LayersTarget {
    fun setWeatherLayerEnabled(enabled: Boolean)
    fun setPredictionsLayerEnabled(enabled: Boolean)
}


