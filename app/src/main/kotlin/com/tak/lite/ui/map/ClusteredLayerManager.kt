package com.tak.lite.ui.map

import android.graphics.Color
import android.util.Log
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Manages clustered layers for peer dots and POIs using MapLibre native clustering
 */
class ClusteredLayerManager(
    private val mapLibreMap: MapLibreMap,
    private val clusteringConfig: ClusteringConfig
) {
    
    companion object {
        private const val TAG = "ClusteredLayerManager"
        
        // Layer IDs
        const val PEER_CLUSTERS_LAYER = "peer-clusters"
        const val PEER_CLUSTER_COUNT_LAYER = "peer-cluster-count"
        const val PEER_DOTS_LAYER = "peer-dots"
        const val PEER_HIT_AREA_LAYER = "peer-hit-area"
        const val POI_CLUSTERS_LAYER = "poi-clusters"
        const val POI_DOTS_LAYER = "poi-dots"
        
        // Source IDs
        const val PEER_CLUSTERED_SOURCE = "peer-dots-clustered"
        const val POI_CLUSTERED_SOURCE = "poi-clustered"
    }

    /**
     * Setup peer dots with native clustering
     */
    fun setupPeerClusteredLayer(data: String) {
        Log.d(TAG, "Setting up peer clustered layer")
        Log.d(TAG, "Clustering config: radius=${clusteringConfig.clusterRadius}, maxZoom=${clusteringConfig.peerClusterMaxZoom}")
        
        mapLibreMap.getStyle { style ->
            try {
                Log.d(TAG, "setupPeerClusteredLayer: got style, creating source")
                
                // Check if source already exists
                val existingSource = style.getSource(PEER_CLUSTERED_SOURCE)
                if (existingSource != null) {
                    Log.d(TAG, "Peer clustered source already exists: ${existingSource.id}")
                    return@getStyle
                }
                
                // Create clustered source with initial empty data and clustering options from user config
                Log.d(TAG, "Creating ClusteredGeoJsonSource with radius=${clusteringConfig.clusterRadius}, maxZoom=${clusteringConfig.peerClusterMaxZoom}")
                
                val source = ClusteredGeoJsonSource(
                    sourceId = PEER_CLUSTERED_SOURCE,
                    clusterRadius = clusteringConfig.clusterRadius,
                    clusterMaxZoom = clusteringConfig.peerClusterMaxZoom
                ).createSourceWithData(data)
                
                style.addSource(source)
                Log.d(TAG, "Added peer clustered source with clustering enabled: ${source.id}, radius=${clusteringConfig.clusterRadius}, maxZoom=${clusteringConfig.peerClusterMaxZoom}")

                Log.d(TAG, "setupPeerClusteredLayer: creating peer dots layer")
                // Add individual peer dots layer
                // Try a simpler filter that should work for individual points
                val peerFilter = Expression.not(Expression.has("point_count"))
                val peerLayer = CircleLayer(PEER_DOTS_LAYER, PEER_CLUSTERED_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor(Expression.get("fillColor")),
                        PropertyFactory.circleRadius(5f),
                        PropertyFactory.circleStrokeColor(Expression.get("borderColor")),
                        PropertyFactory.circleStrokeWidth(3f)
                    )
                    .withFilter(peerFilter)
                style.addLayer(peerLayer)
                Log.d(TAG, "Added peer dots layer: ${peerLayer.id} with filter: $peerFilter")

                // Add invisible hit area layer for easier tapping
                val hitAreaLayer = CircleLayer(PEER_HIT_AREA_LAYER, PEER_CLUSTERED_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor("#FFFFFF"), // Transparent
                        PropertyFactory.circleOpacity(.01f),
                        PropertyFactory.circleRadius(20f), // Larger hit area
                    )
                    .withFilter(peerFilter)
                style.addLayer(hitAreaLayer)
                Log.d(TAG, "Added peer hit area layer: ${hitAreaLayer.id}")
                
                // Verify hit area layer was added
                val finalHitAreaLayer = style.getLayer(PEER_HIT_AREA_LAYER)
                Log.d(TAG, "  Hit area layer exists: ${finalHitAreaLayer != null}")
                if (finalHitAreaLayer != null) {
                    Log.d(TAG, "  Hit area layer visibility: ${finalHitAreaLayer.visibility.value}")
                    Log.d(TAG, "  Hit area layer id: ${finalHitAreaLayer.id}")
                }

                Log.d(TAG, "setupPeerClusteredLayer: creating cluster circles layer")
                // Add cluster circles layer (background for clusters)
                val clusterFilter = Expression.has("point_count")
                val clusterLayer = CircleLayer(PEER_CLUSTERS_LAYER, PEER_CLUSTERED_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor("#4CAF50"), // Material green
                        PropertyFactory.circleRadius(Expression.literal(20f))
                    )
                    .withFilter(clusterFilter)
                style.addLayer(clusterLayer)
                Log.d(TAG, "Added peer cluster circles layer: ${clusterLayer.id} with filter: $clusterFilter")

                // Verify layers were added
                val finalPeerLayer = style.getLayer(PEER_DOTS_LAYER)
                val finalClusterLayer = style.getLayer(PEER_CLUSTERS_LAYER)
                val finalClusterCountLayer = style.getLayer(PEER_CLUSTER_COUNT_LAYER)

                val finalSource = style.getSource(PEER_CLUSTERED_SOURCE)
                
                Log.d(TAG, "Layer verification:")
                Log.d(TAG, "  Peer layer exists: ${finalPeerLayer != null}")
                Log.d(TAG, "  Cluster layer exists: ${finalClusterLayer != null}")
                Log.d(TAG, "  Cluster count layer exists: ${finalClusterCountLayer != null}")

                Log.d(TAG, "  Source exists: ${finalSource != null}")
                
                if (finalPeerLayer != null) {
                    Log.d(TAG, "  Peer layer visibility: ${finalPeerLayer.visibility.value}")
                    Log.d(TAG, "  Peer layer id: ${finalPeerLayer.id}")
                }
                if (finalClusterLayer != null) {
                    Log.d(TAG, "  Cluster layer visibility: ${finalClusterLayer.visibility.value}")
                    Log.d(TAG, "  Cluster layer id: ${finalClusterLayer.id}")
                }
                if (finalClusterCountLayer != null) {
                    Log.d(TAG, "  Cluster count layer visibility: ${finalClusterCountLayer.visibility.value}")
                    Log.d(TAG, "  Cluster count layer id: ${finalClusterCountLayer.id}")
                }
                if (finalSource != null) {
                    Log.d(TAG, "  Source ID: ${finalSource.id}")
                    Log.d(TAG, "  Source type: ${finalSource.javaClass.simpleName}")
                }

                Log.d(TAG, "setupPeerClusteredLayer: completed successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up peer clustered layer", e)
            }
        }
    }
    
    /**
     * Setup POIs with native clustering
     */
    fun setupPoiClusteredLayer(data: String) {
        Log.d(TAG, "Setting up POI clustered layer")
        
        mapLibreMap.getStyle { style ->
            try {
                // Create clustered source with user configuration
                val source = ClusteredGeoJsonSource(
                    sourceId = POI_CLUSTERED_SOURCE,
                    clusterRadius = clusteringConfig.clusterRadius,
                    clusterMaxZoom = clusteringConfig.poiClusterMaxZoom
                ).createSourceWithData(data)
                style.addSource(source)
                Log.d(TAG, "Added POI clustered source with radius=${clusteringConfig.clusterRadius}, maxZoom=${clusteringConfig.poiClusterMaxZoom}")

                // Add individual POI symbols layer
                val poiLayer = SymbolLayer(POI_DOTS_LAYER, POI_CLUSTERED_SOURCE)
                    .withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconSize(1.0f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.textField(Expression.get("label")),
                        PropertyFactory.textColor(Color.WHITE),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textOffset(arrayOf(0f, -2f)),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(false)
                    )
                    .withFilter(Expression.all(
                        Expression.not(Expression.has("point_count")),
                        Expression.has("poiId") // Only show POIs, not line endpoints
                    ))
                style.addLayer(poiLayer)
                Log.d(TAG, "Added POI symbols layer")

                // Add cluster circles layer (background for clusters)
                val clusterLayer = CircleLayer(POI_CLUSTERS_LAYER, POI_CLUSTERED_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor("#FCFCFC"),
                        PropertyFactory.circleRadius(Expression.literal(20f))
                    )
                    .withFilter(Expression.has("point_count"))
                style.addLayer(clusterLayer)
                Log.d(TAG, "Added POI cluster circles layer")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up POI clustered layer", e)
            }
        }
    }
} 