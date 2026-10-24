package subtitle.exception

import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// Исключения с кодом из контракта
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class SrtFileNotFoundException(
    cause: Throwable? = null,
) : ApiException(HttpStatus.NOT_FOUND, "SRT_FILE_NOT_FOUND", "No file found at srtPath on the shared volume.", cause)

class InvalidSrtFormatException(
    cause: Throwable? = null,
) : ApiException(HttpStatus.BAD_REQUEST, "INVALID_SRT_FORMAT", "The file at srtPath is not a valid SRT file.", cause) {
    init {
        cause?.let { initCause(it) }
    }
}

class InvalidPathException : ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Invalid srtPath.")

class SubtitleNotFoundException : ApiException(HttpStatus.NOT_FOUND, "SUBTITLE_NOT_FOUND", "No subtitle found with id.")

class SrtParseException(
    message: String,
) : RuntimeException(message)

data class ErrorResponse(
    val code: String,
    val message: String,
    val traceId: String?,
)

@RestControllerAdvice
class GlobalExceptionHandler(
    private val tracer: Tracer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun traceId() = tracer.currentSpan()?.context()?.traceId()

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(e.status)
            .body(ErrorResponse(e.code, e.message ?: "", traceId()))

    // Outermost safety net: unexpected failures become a generic 500 without leaking internals
    @Suppress("TooGenericExceptionCaught")
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "Internal server error.", traceId()))
    }
}
