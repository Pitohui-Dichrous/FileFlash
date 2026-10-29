package app.fileflash

import android.app.Application

class FileFlashApplication : Application() {
    private val repositoryDelegate = lazy { TransferRepository(this) }
    val repository by repositoryDelegate

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && repositoryDelegate.isInitialized()) repository.clearPreviews()
    }
}
