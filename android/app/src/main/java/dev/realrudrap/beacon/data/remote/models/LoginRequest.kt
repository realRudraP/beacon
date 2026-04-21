package dev.realrudrap.beacon.data.remote.models

import kotlinx.serialization.SerialName

data class LoginRequest(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
    @SerialName("device_id") val deviceId: String
)