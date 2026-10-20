package app.fileflash

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadEntryTest {
    @Test fun olderServersKeepTheirKnownLengthProgress() {
        val entry = Gson().fromJson("""{"id":"older-server","size":123,"offset":12}""", UploadEntry::class.java)
        assertTrue(entry.lengthKnown)
    }

    @Test fun streamingUnknownLengthRemainsExplicitAfterJsonParsing() {
        val entry = Gson().fromJson("""{"id":"stream","size":123,"offset":123,"lengthKnown":false}""", UploadEntry::class.java)
        assertFalse(entry.lengthKnown)
    }
}
