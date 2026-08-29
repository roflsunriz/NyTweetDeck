package dev.nytweetdeck.android.xapi

class XApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
