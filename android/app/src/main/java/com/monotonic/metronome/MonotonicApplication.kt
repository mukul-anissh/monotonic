package com.monotonic.metronome

import android.app.Application
import android.content.Context
import com.monotonic.metronome.session.MetronomeSessionController

class MonotonicApplication : Application() {

    val sessionController: MetronomeSessionController by lazy {
        MetronomeSessionController(this)
    }

    companion object {
        fun from(context: Context): MonotonicApplication {
            return context.applicationContext as MonotonicApplication
        }
    }
}
