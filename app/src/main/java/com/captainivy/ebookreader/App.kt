package com.captainivy.ebookreader

import android.app.Application
import com.captainivy.ebookreader.data.Prefs
import com.captainivy.ebookreader.data.db.AppDatabase
import com.captainivy.ebookreader.data.repo.BookRepository

class App : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var repository: BookRepository
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase(this)
        repository = BookRepository(this, db)
        prefs = Prefs(this)
    }

    companion object {
        lateinit var instance: App
            private set

        fun get(): App = instance
    }
}
