package com.tak.lite.network.takserver

import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.UserStatus
import com.tak.lite.util.MeshAnnotationInterop

/**
 * Encodes/decodes the &lt;taklite&gt; CoT detail extension for TAK Lite fidelity over Local TAK Server.
 */
object TakLiteDetail {
    fun annotationJson(annotation: MapAnnotation): String =
        MeshAnnotationInterop.annotationToCompactJson(annotation)

    fun annotationFromJson(json: String): MapAnnotation? =
        MeshAnnotationInterop.compactJsonToAnnotation(json)

    fun statusJson(status: UserStatus): String =
        MeshAnnotationInterop.statusToCompactJson(status)

    fun statusFromJson(json: String): UserStatus? =
        MeshAnnotationInterop.compactJsonToStatus(json)

    fun bulkDeleteJson(ids: List<String>): String =
        MeshAnnotationInterop.bulkDeleteToCompactJson(ids)

    fun bulkDeleteFromJson(json: String): List<String>? =
        MeshAnnotationInterop.compactJsonToBulkDeleteIds(json)
}
