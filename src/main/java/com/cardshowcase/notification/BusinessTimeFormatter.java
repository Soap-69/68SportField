package com.cardshowcase.notification;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats stored timestamps for emails and the invoice only.
 *
 * Stored values are {@code LocalDateTime} written by {@code LocalDateTime.now()} in the
 * writing JVM's default zone, with no zone recorded. The value is therefore interpreted
 * in the current process's default zone and converted to America/New_York. This is
 * correct only when every displayed row was written by a JVM whose default zone equals
 * the current one; it is not a general conversion and must not be described as one.
 */
@Component
public class BusinessTimeFormatter {

    static final ZoneId DISPLAY_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    public String format(LocalDateTime stored) {
        return stored.atZone(ZoneId.systemDefault())
                     .withZoneSameInstant(DISPLAY_ZONE)
                     .format(FORMAT) + " ET";
    }
}
