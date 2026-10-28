package dev.jenishsoni.tally.risk;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds tally.risk to {@link RiskProperties}, which checks the settings as it is created. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RiskProperties.class)
class RiskConfiguration {
}
