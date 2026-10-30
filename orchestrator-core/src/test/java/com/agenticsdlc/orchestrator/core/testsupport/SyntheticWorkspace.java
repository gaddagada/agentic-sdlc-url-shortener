package com.agenticsdlc.orchestrator.core.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a small, synthetic url-shortener-like codebase (controllers, services, repositories, entities,
 * filter, configuration, tests, migrations) into a temporary directory.
 */
public final class SyntheticWorkspace {

    private static final String MAIN = "url-shortener/src/main/java/com/agenticsdlc/shortener/";
    private static final String TEST = "url-shortener/src/test/java/com/agenticsdlc/shortener/";

    private SyntheticWorkspace() {
    }

    public static Path create(Path root) {
        write(root, MAIN + "api/LinkController.java", """
                package com.agenticsdlc.shortener.api;

                import com.agenticsdlc.shortener.api.dto.CreateLinkRequest;
                import com.agenticsdlc.shortener.api.dto.LinkResponse;
                import com.agenticsdlc.shortener.service.LinkService;
                import org.springframework.web.bind.annotation.*;

                /** Links API. Not a @Service. */
                @RestController
                @RequestMapping("/api/v1/links")
                public class LinkController {
                    private final LinkService linkService;

                    public LinkController(LinkService linkService) {
                        this.linkService = linkService;
                    }

                    @PostMapping
                    public LinkResponse create(@RequestBody CreateLinkRequest request) {
                        return linkService.create(request);
                    }

                    @GetMapping("/{code}")
                    public LinkResponse get(@PathVariable String code) {
                        return linkService.get(code);
                    }

                    @DeleteMapping(value = "/{code}")
                    public void delete(@PathVariable String code) {
                        linkService.deactivate(code);
                    }
                }
                """);
        write(root, MAIN + "api/RedirectController.java", """
                package com.agenticsdlc.shortener.api;

                import com.agenticsdlc.shortener.service.LinkService;
                import org.springframework.web.bind.annotation.*;

                @RestController
                public class RedirectController {
                    private final LinkService linkService;

                    public RedirectController(LinkService linkService) {
                        this.linkService = linkService;
                    }

                    // @PostMapping("/commented-out") must not be indexed
                    @GetMapping("/{code}")
                    public Object redirect(@PathVariable String code) {
                        return linkService.resolve(code);
                    }
                }
                """);
        write(root, MAIN + "api/AnalyticsController.java", """
                package com.agenticsdlc.shortener.api;

                import com.agenticsdlc.shortener.service.AnalyticsService;
                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/api/v1/links")
                public class AnalyticsController {
                    private final AnalyticsService analyticsService;

                    public AnalyticsController(AnalyticsService analyticsService) {
                        this.analyticsService = analyticsService;
                    }

                    @GetMapping("/{code}/analytics")
                    public Object analytics(@PathVariable String code, @RequestParam(defaultValue = "7") int days) {
                        return analyticsService.analytics(code, days);
                    }
                }
                """);
        write(root, MAIN + "api/ApiExceptionHandler.java", """
                package com.agenticsdlc.shortener.api;

                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                public class ApiExceptionHandler {
                }
                """);
        write(root, MAIN + "api/dto/CreateLinkRequest.java", """
                package com.agenticsdlc.shortener.api.dto;

                public record CreateLinkRequest(String url, String customAlias, Long ttlSeconds) {
                }
                """);
        write(root, MAIN + "api/dto/LinkResponse.java", """
                package com.agenticsdlc.shortener.api.dto;

                public record LinkResponse(String code, String shortUrl, String originalUrl) {
                }
                """);
        write(root, MAIN + "service/LinkService.java", """
                package com.agenticsdlc.shortener.service;

                import com.agenticsdlc.shortener.api.dto.CreateLinkRequest;
                import com.agenticsdlc.shortener.api.dto.LinkResponse;
                import com.agenticsdlc.shortener.domain.Link;
                import com.agenticsdlc.shortener.repository.LinkRepository;
                import org.springframework.stereotype.Service;

                @Service
                public class LinkService {
                    private final LinkRepository linkRepository;
                    private final UrlValidator urlValidator;
                    private final ShortCodeGenerator shortCodeGenerator;

                    public LinkService(LinkRepository linkRepository, UrlValidator urlValidator,
                                       ShortCodeGenerator shortCodeGenerator) {
                        this.linkRepository = linkRepository;
                        this.urlValidator = urlValidator;
                        this.shortCodeGenerator = shortCodeGenerator;
                    }

                    public LinkResponse create(CreateLinkRequest request) {
                        urlValidator.validate(request.url());
                        String alias = request.customAlias();
                        Link link = new Link(shortCodeGenerator.next(), request.url());
                        linkRepository.save(link);
                        return null;
                    }

                    public LinkResponse get(String code) {
                        return null;
                    }

                    public Object resolve(String code) {
                        return null;
                    }

                    public void deactivate(String code) {
                    }
                }
                """);
        write(root, MAIN + "service/UrlValidator.java", """
                package com.agenticsdlc.shortener.service;

                import org.springframework.stereotype.Component;

                @Component
                public class UrlValidator {
                    public void validate(String url) {
                        // blocks private and loopback targets (ssrf)
                    }
                }
                """);
        write(root, MAIN + "service/ShortCodeGenerator.java", """
                package com.agenticsdlc.shortener.service;

                import org.springframework.stereotype.Component;

                @Component
                public class ShortCodeGenerator {
                    public String next() {
                        return "abc1234";
                    }
                }
                """);
        write(root, MAIN + "service/AnalyticsService.java", """
                package com.agenticsdlc.shortener.service;

                import com.agenticsdlc.shortener.repository.ClickEventRepository;
                import org.springframework.stereotype.Service;

                @Service
                public class AnalyticsService {
                    private final ClickEventRepository clickEventRepository;

                    public AnalyticsService(ClickEventRepository clickEventRepository) {
                        this.clickEventRepository = clickEventRepository;
                    }

                    public Object analytics(String code, int days) {
                        return clickEventRepository.count();
                    }
                }
                """);
        write(root, MAIN + "repository/LinkRepository.java", """
                package com.agenticsdlc.shortener.repository;

                import com.agenticsdlc.shortener.domain.Link;
                import org.springframework.data.jpa.repository.JpaRepository;

                public interface LinkRepository extends JpaRepository<Link, Long> {
                }
                """);
        write(root, MAIN + "repository/ClickEventRepository.java", """
                package com.agenticsdlc.shortener.repository;

                import com.agenticsdlc.shortener.domain.ClickEvent;
                import org.springframework.data.jpa.repository.JpaRepository;

                public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
                }
                """);
        write(root, MAIN + "domain/Link.java", """
                package com.agenticsdlc.shortener.domain;

                import jakarta.persistence.*;

                @Entity
                @Table(name = "links")
                public class Link {
                    @Id
                    private Long id;
                    private String code;
                    private String originalUrl;

                    protected Link() {
                    }

                    public Link(String code, String originalUrl) {
                        this.code = code;
                        this.originalUrl = originalUrl;
                    }
                }
                """);
        write(root, MAIN + "domain/ClickEvent.java", """
                package com.agenticsdlc.shortener.domain;

                import jakarta.persistence.*;

                @Entity
                public class ClickEvent {
                    @Id
                    private Long id;
                    private String referrerHost;
                }
                """);
        write(root, MAIN + "config/RateLimitFilter.java", """
                package com.agenticsdlc.shortener.config;

                import org.springframework.web.filter.OncePerRequestFilter;

                public class RateLimitFilter extends OncePerRequestFilter {
                    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
                }
                """);
        write(root, MAIN + "config/TokenBucketRateLimiter.java", """
                package com.agenticsdlc.shortener.config;

                public class TokenBucketRateLimiter {
                    public boolean tryAcquire(String client) {
                        return true;
                    }
                }
                """);
        write(root, MAIN + "config/ShortenerProperties.java", """
                package com.agenticsdlc.shortener.config;

                import org.springframework.boot.context.properties.ConfigurationProperties;

                @ConfigurationProperties("shortener")
                public record ShortenerProperties(String baseUrl, int codeLength) {
                }
                """);
        write(root, TEST + "service/LinkServiceTest.java", """
                package com.agenticsdlc.shortener.service;

                import org.junit.jupiter.api.Test;

                class LinkServiceTest {
                    @Test
                    void createsLinks() {
                        LinkService service = null;
                    }
                }
                """);
        write(root, TEST + "api/LinkControllerTest.java", """
                package com.agenticsdlc.shortener.api;

                class LinkControllerTest {
                    LinkController controller;
                }
                """);
        write(root, "url-shortener/src/main/resources/db/migration/V1__init.sql",
                "CREATE TABLE links (id BIGINT PRIMARY KEY);\n");
        write(root, "url-shortener/src/main/resources/db/migration/V2__click_events.sql",
                "CREATE TABLE click_events (id BIGINT PRIMARY KEY);\n");
        write(root, "url-shortener/src/main/resources/application.yml", "server.port: 8080\n");
        write(root, "url-shortener/target/classes/Ignored.java", "class Ignored {}\n");
        return root;
    }

    private static void write(Path root, String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
