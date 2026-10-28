package dev.jenishsoni.tally;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.jenishsoni.tally.risk.RiskClient;

@SpringBootApplication
@EnableScheduling
public class TallyApplication {

    public static void main(String[] args) {
        // Before Spring builds any JDK HTTP client, since the JDK reads the setting only once.
        RiskClient.limitKeepAlive();
        SpringApplication.run(TallyApplication.class, args);
    }

}
