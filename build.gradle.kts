plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.jenishsoni"
version = "0.0.1-SNAPSHOT"
description = "Double-entry ledger service"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // What TallyApplication.main sets (RiskClient.KEEP_ALIVE), which tests don't run. The JDK reads it once, when its
    // first HTTP client is built, so it is set when the test JVM starts.
    systemProperty("jdk.httpclient.keepalive.timeout", "4")
}

// A simulation sends thousands of requests, so the normal build leaves it to simulationTest.
tasks.test {
    useJUnitPlatform {
        excludeTags("simulation")
    }
}

tasks.register<Test>("simulationTest") {
    group = "verification"
    description = "Runs the random simulation tests, which compare the HTTP API with an in-memory model."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("simulation")
    }
    shouldRunAfter("test")
    // A failure's message holds the seed and the last operations, so it is shown whole, and the console shows the
    // count and time of every seed. The app logs only warnings here, so those lines are not lost among the log
    // lines of ten thousand requests.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    systemProperty("logging.level.root", "WARN")
    // A simulation is asked for on purpose, so an earlier result must never stand in for a new run.
    outputs.upToDateWhen { false }
    // -D on the Gradle command line sets a property of Gradle's own JVM, not of the test JVM, so seed (replay
    // one seed) and seeds (how many to run) are passed on. When unset, the test uses its own defaults.
    listOf("seed", "seeds").forEach { property ->
        providers.systemProperty(property).orNull?.let { systemProperty(property, it) }
    }
}
