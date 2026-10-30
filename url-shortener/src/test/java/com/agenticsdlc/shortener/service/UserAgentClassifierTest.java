package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.agenticsdlc.shortener.domain.UserAgentFamily;

class UserAgentClassifierTest {

    private final UserAgentClassifier classifier = new UserAgentClassifier();

    @ParameterizedTest(name = "{1}: {0}")
    @CsvSource(delimiter = '|', textBlock = """
        Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 | CHROME
        Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 | CHROME
        Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0.6723.90 Mobile/15E148 Safari/604.1 | CHROME
        Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.2849.68 | EDGE
        Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 OPR/114.0.0.0 | OPERA
        Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0 | FIREFOX
        Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/131.0 Mobile/15E148 Safari/605.1.15 | FIREFOX
        Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15 | SAFARI
        Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1 | SAFARI
        Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko | IE
        Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 6.1) | IE
        Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html) | BOT
        Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm) | BOT
        facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php) | BOT
        Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots) | BOT
        Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/130.0.0.0 Safari/537.36 | BOT
        curl/8.4.0 | HTTP_CLIENT
        Wget/1.21.4 | HTTP_CLIENT
        python-requests/2.32.3 | HTTP_CLIENT
        Java-http-client/25 | HTTP_CLIENT
        PostmanRuntime/7.42.0 | HTTP_CLIENT
        okhttp/4.12.0 | HTTP_CLIENT
        Go-http-client/2.0 | HTTP_CLIENT
        SomeCustomAgent/1.0 | OTHER
        """)
    void classifiesKnownFamilies(String userAgent, UserAgentFamily expected) {
        assertThat(classifier.classify(userAgent)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingUserAgentIsUnknown(String userAgent) {
        assertThat(classifier.classify(userAgent)).isEqualTo(UserAgentFamily.UNKNOWN);
    }
}
