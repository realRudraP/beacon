package dev.realrudrap.beacon.data.local

import android.os.Build
import javax.inject.Inject

class DeviceInfoManager @Inject constructor(){
    fun getDeviceInfoString(): String {
        return """
${Build.MANUFACTURER} ${Build.MODEL} running Android ${Build.VERSION.RELEASE} ${Build.VERSION.SDK_INT}
"""
    }
}