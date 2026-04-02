package com.lauri000.nostrwifiaware

import android.app.Application

class NearbyApp : Application() {
    val nearbyController: AndroidNearbyController by lazy {
        AndroidNearbyController(this)
    }
}
