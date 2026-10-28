package dev.jenishsoni.tally.risk;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The risk check's settings, tally.risk (docs/risk-decision-call.md, 4.4): whether a transfer asks the risk engine,
 * its base URL, how long a transfer waits for a decision, and the backup answer until the risk engine's first reply
 * names one. They are checked when the check is on, so tally refuses to start with settings it can't use; while it
 * is off they are neither used nor checked.
 */
@ConfigurationProperties("tally.risk")
public record RiskProperties(boolean enabled, String url, Duration timeout, Decision onTimeoutDefault) {

    public RiskProperties {
        if (enabled) {
            requireHttpUrl(url);
            requireTimeout(timeout);
            requireBackupAnswer(onTimeoutDefault);
        }
    }

    private static void requireHttpUrl(String url) {
        if (!isAbsoluteHttpUrl(url)) {
            // The value stays out of the message, since a URL can carry credentials.
            throw new IllegalStateException("tally.risk.url must be an absolute http or https URL");
        }
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            return http && uri.getHost() != null;
        } catch (URISyntaxException _) {
            return false;
        }
    }

    // The client's deadline counts whole milliseconds (RiskClient), so anything shorter would be no wait at all.
    private static void requireTimeout(Duration timeout) {
        if (timeout == null || timeout.toMillis() < 1) {
            throw new IllegalStateException("tally.risk.timeout must be positive, at least 1ms");
        }
    }

    // Binding the enum already refuses any other value, so only a missing or empty one gets here.
    private static void requireBackupAnswer(Decision onTimeoutDefault) {
        if (onTimeoutDefault == null) {
            throw new IllegalStateException("tally.risk.on-timeout-default must be allow, hold or block");
        }
    }

}
