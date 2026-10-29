package app.fileflash

/** Only an explicit FileFlash authentication failure warrants the expired-pairing treatment. */
internal fun Throwable.isPairingExpiredFailure(): Boolean =
    this is HttpFailure && statusCode == 401 && errorCode in setOf("invalid_token", "session_expired")
