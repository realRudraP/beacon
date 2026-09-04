package dev.realrudrap.beacon.data.remote

import dev.realrudrap.beacon.data.remote.dto.AttendanceResponse
import dev.realrudrap.beacon.data.remote.dto.MarkAttendanceRequest
import dev.realrudrap.beacon.data.remote.dto.PhotoUploadResponse
import dev.realrudrap.beacon.data.remote.dto.UpdateAttendanceRequest
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface AttendanceApiService {
    @POST("attendance/")
    suspend fun markAttendance(
        @Body request: MarkAttendanceRequest
    ): Response<AttendanceResponse>

    @PATCH("attendance/{id}")
    suspend fun updateAttendance(
        @Path("id") id: String,
        @Body request: UpdateAttendanceRequest
    ): Response<AttendanceResponse>

    @Multipart
    @POST("attendance/photos/{id}")
    suspend fun uploadAttendancePhoto(
        @Path("id") id: String,
        @Query("type") type: String,
        @Part photo: MultipartBody.Part
    ): Response<PhotoUploadResponse>

    @GET("attendance/")
    suspend fun getAttendance(
        @Query("from") fromDate: String? = null,
        @Query("to") toDate: String? = null
    ): Response<List<AttendanceResponse>>
}