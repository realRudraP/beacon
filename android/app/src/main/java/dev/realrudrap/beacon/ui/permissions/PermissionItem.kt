package dev.realrudrap.beacon.ui.permissions

import androidx.compose.ui.graphics.vector.ImageVector

data class PermissionItem (
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val onActionClick: ()->Unit
)