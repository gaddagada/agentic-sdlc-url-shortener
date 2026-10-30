package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessBuildRunnerTest {

    @Test
    void credentialLikeTokensInTheBuildOutputAreRedactedBeforeTheyAreCaptured(@TempDir Path dir) throws Exception {
        String token = "ghp_" + "a1".repeat(15);
        Files.writeString(dir.resolve("mvnw"), "#!/bin/sh\necho \"[INFO] start\"\necho \"[ERROR] expected "
                + "password=Secr3t42 but was null\"\necho \"[INFO] token " + token + "\"\nexit 1\n");
        Files.writeString(dir.resolve("mvnw.cmd"), "@echo off\r\necho [INFO] start\r\necho [ERROR] expected "
                + "password=Secr3t42 but was null\r\necho [INFO] token " + token + "\r\nexit /b 1\r\n");

        BuildResult result = new ProcessBuildRunner().run(dir, "./mvnw verify", Duration.ofSeconds(60));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.failures()).singleElement().asString().startsWith("[ERROR] expected ")
                .contains("[redacted]").doesNotContain("Secr3t42");
        assertThat(result.outputTail()).contains("[INFO] start").noneMatch(l -> l.contains("Secr3t42")
                || l.contains(token)).anySatisfy(l -> assertThat(l).isEqualTo("[INFO] token [redacted]"));
    }

    @Test
    void sandboxEnvironmentDropsCredentialLikeVariablesAndKeepsTheToolchain() {
        Map<String, String> environment = new HashMap<>();
        environment.put("ANTHROPIC_API_KEY", "sk-ant-secret");
        environment.put("GITHUB_TOKEN", "ghp_secret");
        environment.put("SHORTENER_DB_PASSWORD", "secret");
        environment.put("AWS_SECRET_ACCESS_KEY", "secret");
        environment.put("SSH_AUTH_SOCK", "/tmp/agent.sock");
        environment.put("SomeCredentialStore", "x");
        environment.put("JAVA_HOME", "/opt/jdk");
        environment.put("PATH", "/usr/bin");
        environment.put("MAVEN_OPTS", "-Xmx1g");
        environment.put("HOME", "/home/build");
        environment.put("TEMP", "/tmp");

        ProcessBuildRunner.sanitizeEnvironment(environment);

        assertThat(environment).containsOnlyKeys("JAVA_HOME", "PATH", "MAVEN_OPTS", "HOME", "TEMP");
    }
}
