package dev.pocketshare.core.log

/** Minimal logger abstraction so core/server/tunnel can stay Android-independent. */
interface AppLogger {
    fun debug(
        tag: String,
        message: String,
    )

    fun info(
        tag: String,
        message: String,
    )

    fun warn(
        tag: String,
        message: String,
        error: Throwable? = null,
    )

    fun error(
        tag: String,
        message: String,
        error: Throwable? = null,
    )
}

object NoopLogger : AppLogger {
    override fun debug(
        tag: String,
        message: String,
    ) = Unit

    override fun info(
        tag: String,
        message: String,
    ) = Unit

    override fun warn(
        tag: String,
        message: String,
        error: Throwable?,
    ) = Unit

    override fun error(
        tag: String,
        message: String,
        error: Throwable?,
    ) = Unit
}
