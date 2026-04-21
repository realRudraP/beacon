package dev.realrudrap.beacon.data.remote.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("access_token") val accessToken: String
)