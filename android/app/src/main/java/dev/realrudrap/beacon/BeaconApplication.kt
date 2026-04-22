package dev.realrudrap.beacon

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BeaconApplication: Application(){
    override fun onCreate() {
        super.onCreate()


            Timber.plant(Timber.DebugTree())


        // TODO: Plant custom logging tree which writes to a file
    }
}