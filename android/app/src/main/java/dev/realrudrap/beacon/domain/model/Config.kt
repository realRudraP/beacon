package dev.realrudrap.beacon.domain.model

import androidx.room.Entity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Config(
    @SerialName("allowed_accuracy_meters") val allowedAccuracyMeters: Int,
    @SerialName("public_holidays") val publicHolidays: List<String>?=null,
    val geofences: List<Geofence>?=null,
    @SerialName("notification_rules") val notificationRules: List<NotificationRule>?=null
)
@Serializable
data class Geofence(
    val id: String,
    val name: String,
    @SerialName("center_lat")val centerLat: Double,
    @SerialName("center_lng")val centerLng: Double,
    val radius: Int,
    @SerialName("created_at")val createdAt: String,
    @SerialName("updated_at")val updatedAt: String
)
@Serializable
data class NotificationRule(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val conditions: NotificationConditions,
    val time: String
)
@Serializable
data class NotificationConditions(
    @SerialName("is_working_day") val isWorkingDay: Boolean? = null,
    @SerialName("is_public_holiday") val isPublicHoliday: Boolean? = null,
    @SerialName("has_clocked_in_today") val hasClockedInToday: Boolean? = null,
    @SerialName("has_clocked_out_today") val hasClockedOutToday: Boolean? = null,
    @SerialName("has_pending_sync_records") val hasPendingSyncRecords: Boolean? = null
)