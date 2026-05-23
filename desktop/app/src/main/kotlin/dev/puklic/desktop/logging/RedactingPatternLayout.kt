package dev.puklic.desktop.logging

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Logback pattern layout that masks Discord credentials before lines reach any appender.
 *
 * Three patterns are redacted:
 *  - `Authorization: <token>` HTTP headers
 *  - bare `Bearer <token>` occurrences
 *  - Discord MFA tokens (prefix `mfa.`)
 *
 * Threshold for the generic Bearer/Authorization match is 20 chars so we do not redact
 * unrelated short identifiers. MFA tokens have a fixed prefix and are always redacted.
 */
public class RedactingPatternLayout : PatternLayout() {

    override fun doLayout(event: ILoggingEvent): String {
        val raw = super.doLayout(event)
        return redact(raw)
    }

    public companion object {
        private val AUTH_HEADER_REGEX = Regex("""(?i)(Authorization\s*[:=]\s*(?:Bearer\s+)?)([A-Za-z0-9._\-]{20,})""")
        private val BEARER_REGEX = Regex("""(Bearer\s+)([A-Za-z0-9._\-]{20,})""")
        private val MFA_REGEX = Regex("""mfa\.[A-Za-z0-9._\-]{20,}""")

        /** Public for unit testing; applies the same redaction rules as `doLayout`. */
        public fun redact(input: String): String =
            input
                .replace(AUTH_HEADER_REGEX) { m -> "${m.groupValues[1]}<REDACTED>" }
                .replace(BEARER_REGEX) { m -> "${m.groupValues[1]}<REDACTED>" }
                .replace(MFA_REGEX, "<REDACTED-MFA>")
    }
}
