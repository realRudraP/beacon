package dev.realrudrap.beacon.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MarkAttendanceRequest(
    @SerialName("id") val id: String,
    @SerialName("clock_in_time") val clockInTime: String,
    @SerialName("clock_in_lat") val clockInLat: Double,
    @SerialName("clock_in_long") val clockInLong: Double,
    @SerialName("clock_in_photo_uri") val clockInPhotoUri: String? = null,
    @SerialName("clock_out_time") val clockOutTime: String? = null,
    @SerialName("clock_out_lat") val clockOutLat: Double? = null,
    @SerialName("clock_out_long") val clockOutLong: Double? = null,
    @SerialName("clock_out_photo_uri") val clockOutPhotoUri: String? = null,
)

@Serializable
data class UpdateAttendanceRequest(
    @SerialName("clock_in_photo_uri") val clockInPhotoUri: String? = null,
    @SerialName("clock_out_time") val clockOutTime: String? = null,
    @SerialName("clock_out_lat") val clockOutLat: Double?=null,
    @SerialName("clock_out_long") val clockOutLong: Double?=null,
    @SerialName("clock_out_photo_uri") val clockOutPhotoUri: String? = null,
)

@Serializable
data class AttendanceResponse(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("date") val date: String,
    @SerialName("check_in") val checkIn: String,
    @SerialName("check_out") val checkOut: String? = null,
    @SerialName("check_in_photo_uri") val checkInPhotoUri: String? = null,
    @SerialName("check_out_photo_uri") val checkOutPhotoUri: String? = null,
    @SerialName("check_in_lat") val checkInLat: Double? = null,
    @SerialName("check_in_long") val checkInLong: Double? = null,
    @SerialName("check_out_lat") val checkOutLat: Double? = null,
    @SerialName("check_out_long") val checkOutLong: Double? = null,
)

@Serializable
data class PhotoUploadResponse(
    @SerialName("photo_url") val photoUrl: String
)