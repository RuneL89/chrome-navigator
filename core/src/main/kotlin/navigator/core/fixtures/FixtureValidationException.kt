package navigator.core.fixtures

/**
 * Thrown when a golden fixture fails strict validation. Malformed fixture = loud
 * failure, never a silent default (PHASE_00 §2.3).
 */
class FixtureValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
