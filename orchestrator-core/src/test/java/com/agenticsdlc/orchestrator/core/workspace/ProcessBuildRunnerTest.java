package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProcessBuildRunnerTest {

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
