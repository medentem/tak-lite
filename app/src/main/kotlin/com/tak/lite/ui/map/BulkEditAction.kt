package com.tak.lite.ui.map

import com.tak.lite.model.AnnotationColor

sealed class BulkEditAction {
    data class ChangeColor(val color: AnnotationColor) : BulkEditAction()
    data class SetExpiration(val millis: Long) : BulkEditAction()
    object Delete : BulkEditAction()
} 