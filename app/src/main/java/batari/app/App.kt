package batari.app

import android.app.Application
import batari.app.data.Repository

class App : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
    }
}
