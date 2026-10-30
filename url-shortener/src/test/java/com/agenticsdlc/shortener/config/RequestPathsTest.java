package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestPathsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
        //api;a=b//v1/links;x/ | NULL          | /api/v1/links/
        /api/v1/links          | NULL          | /api/v1/links
        /api                   | /v1/links;x   | /api/v1/links
        ''                     | /api/v1/links | /api/v1/links
        /api/v1/links/bulk;x=1 | NULL          | /api/v1/links/bulk
        ''                     | NULL          | ''
        """)
    void normalizesPathParametersAndDuplicateSlashes(String servletPath, String pathInfo, String expected) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ignored-raw-uri");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);

        assertThat(RequestPaths.normalizedPath(request)).isEqualTo(expected);
    }
}
