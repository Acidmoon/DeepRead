package com.vibecoding.reader

import android.app.Application
import com.vibecoding.reader.di.AppContainer

class ReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
