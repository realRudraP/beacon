package dev.realrudrap.beacon.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationCheckPayload(
    @SerialName("id") val id: String,
    @SerialName("attendance_record_id") val attendanceRecordId: String,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double,
    @SerialName("accuracy") val accuracy: Double? = null,
    @SerialName("sequence_number") val sequenceNumber: Int,
)

@Serializable
data class IngestLocationChecksRequest(
    @SerialName("checks") val checks: List<LocationCheckPayload>,
)

@Serializable
data class IngestLocationChecksResponse(
    @SerialName("accepted") val accepted: List<String> = emptyList(),
    @SerialName("duplicates") val duplicates: List<String> = emptyList(),
    @SerialName("rejected") val rejected: List<RejectedLocationCheck> = emptyList(),
)

@Serializable
data class RejectedLocationCheck(
    @SerialName("id") val id: String,
    @SerialName("reason") val reason: String,
)
