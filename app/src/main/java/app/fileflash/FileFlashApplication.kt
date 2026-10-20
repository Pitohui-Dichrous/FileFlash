package app.fileflash

import android.app.Application

class FileFlashApplication : Application() {
    val repository by lazy { TransferRepository(this) }
}
