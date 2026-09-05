package dev.realrudrap.beacon.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateFcmTokenRequest(
    @SerialName("fcm_token")
    val fcmToken: String
)