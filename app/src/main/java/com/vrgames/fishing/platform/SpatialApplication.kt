package com.vrgames.fishing.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.vrgames.fishing.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
