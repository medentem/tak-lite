package com.tak.lite.util

import com.geeksville.mesh.MeshProtos
import com.tak.lite.model.MapAnnotation
import com.google.protobuf.ByteString

object MeshAnnotationInterop {
    fun mapAnnotationToMeshData(annotation: MapAnnotation): MeshProtos.Data {
        // TODO: Implement conversion from MapAnnotation to MeshProtos.Data
        // This is a stub. You must implement the actual conversion logic based on your protobuf schema and annotation model.
        return MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.PRIVATE_APP)
            .setPayload(ByteString.copyFrom(annotation.toString().toByteArray())) // Replace with real serialization
            .build()
    }

    fun meshDataToMapAnnotation(data: MeshProtos.Data): MapAnnotation? {
        // TODO: Implement conversion from MeshProtos.Data to MapAnnotation
        // This is a stub. You must implement the actual conversion logic based on your protobuf schema and annotation model.
        val str = data.payload.toString(Charsets.UTF_8)
        // Replace with real deserialization
        return null
    }
} 