package com.agenticsdlc.orchestrator.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretPatternsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "AKIAABCDEFGHIJKLMNOP",
            "sk-ant-api03-abcdefghij",
            "ghp_abcdefghijklmnopqrstuvwxyz12",
            "github_pat_is_not_matched_but_ghs_is: ghs_abcdefghijklmnopqrstuvwxyz12",
            "password = hunter2",
            "password: \"s3cret!\"",
            "DB_PASSWORD=Tr0ub4dor&3",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----",
            "xoxb-1234567890-abcdefghij",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"})
    void detectsLiteralSecrets(String text) {
        assertThat(SecretPatterns.containsSecret("prefix " + text + " suffix")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password: ${SHORTENER_DB_PASSWORD}",
            "password: {{ vault.db_password }}",
            "password: <redacted>",
            "password: ***",
            "password: \"\"",
            "this.password = password;",
            "password = properties.password()",
            "spring.datasource.password=changeme",
            "spring.datasource.password=change-me-in-production",
            "The password field is required and must be validated.",
            "Rotate the API key monthly."})
    void ignoresPlaceholdersConfigurationReferencesAndCode(String text) {
        assertThat(SecretPatterns.findKinds(text)).as(text).isEmpty();
    }
}
