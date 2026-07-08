package dev.realrudrap.beacon.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "users")
@Serializable
data class User (

    @PrimaryKey @SerialName("username")val username :String,
    @SerialName("full_name") val fullName: String,
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("email") val email: String,
    @SerialName("role") val role: String,
    @SerialName("active") val active: Boolean,
    @SerialName("weekly_off_days") val weeklyOffDays: List<Int>
)