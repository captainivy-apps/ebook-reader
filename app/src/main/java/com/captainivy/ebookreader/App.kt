package com.captainivy.ebookreader

import android.app.Application
import com.captainivy.ebookreader.data.Prefs
import com.captainivy.ebookreader.data.db.AppDatabase
import com.captainivy.ebookreader.data.migration.CharCountMigration
import com.captainivy.ebookreader.data.repo.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var repository: BookRepository
        private set
    lateinit var prefs: Prefs
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase(this)
        repository = BookRepository(this, db)
        prefs = Prefs(this)
        // 后台一次性迁移旧书的字符基准（不影响启动，失败下次重试）
        appScope.launch {
            try {
                CharCountMigration(repository).migrateIfNeeded()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun get(): App = instance
    }
}
