package com.ishank.urlshortener.services;

import com.ishank.urlshortener.dto.URLRequest;
import com.ishank.urlshortener.dto.URLResponse;
import com.ishank.urlshortener.exceptions.BlacklistedUrlException;
import com.ishank.urlshortener.models.URL;
import com.ishank.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UrlServiceTest {

    private UrlRepository urlRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        urlService = new UrlService(urlRepository, redisTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080/api/urls");
    }

    @Test
    void createShortUrl_generatesBase62FromId_whenNoCustomAlias() {
        URLRequest request = new URLRequest();
        request.setLongUrl("https://example.com");

        when(urlRepository.save(any(URL.class))).thenAnswer(invocation -> {
            URL u = invocation.getArgument(0);
            if (u.getId() == null) {
                u.setId(1);
            }
            return u;
        });

        URLResponse response = urlService.createShortUrl(request);

        assertThat(response.getShortCode()).isNotBlank();
        assertThat(response.getLongUrl()).isEqualTo("https://example.com");
        verify(urlRepository, times(2)).save(any(URL.class));
        verify(valueOperations, atLeastOnce()).set(startsWith("short:"), eq("https://example.com"), any());
        verify(valueOperations, atLeastOnce()).set(startsWith("long:"), anyString(), any());
    }

    @Test
    void createShortUrl_throwsConflict_whenCustomAliasExists() {
        URLRequest request = new URLRequest();
        request.setLongUrl("https://example.com");
        request.setCustomAlias("alias");

        when(urlRepository.existsByShortUrl("alias")).thenReturn(true);

        assertThrows(UrlService.AliasAlreadyExistsException.class, () -> urlService.createShortUrl(request));
        verify(urlRepository).existsByShortUrl("alias");
    }

    @Test
    void resolveLongUrl_returnsFromCache_whenPresent() {
        when(valueOperations.get("short:code")).thenReturn("https://cached.com");
        URL entity = URL.builder().id(1).shortUrl("code").longUrl("https://cached.com").expiresAt(LocalDateTime.now().plusMinutes(10)).active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("code")).thenReturn(Optional.of(entity));

        String result = urlService.resolveLongUrl("code");

        assertThat(result).isEqualTo("https://cached.com");
        verify(urlRepository).findByShortUrlAndActiveTrue("code");
    }

    @Test
    void resolveLongUrl_throwsExpired_whenPastExpiration() {
        URL entity = URL.builder()
                .id(1)
                .shortUrl("code")
                .longUrl("https://expired.com")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .active(true)
                .build();

        when(urlRepository.findByShortUrlAndActiveTrue("code")).thenReturn(Optional.of(entity));

        assertThrows(UrlService.UrlExpiredException.class, () -> urlService.resolveLongUrl("code"));
        ArgumentCaptor<URL> captor = ArgumentCaptor.forClass(URL.class);
        verify(urlRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void resolveLongUrl_throwsNotFound_whenMissing() {
        when(urlRepository.findByShortUrlAndActiveTrue("missing")).thenReturn(Optional.empty());

        assertThrows(UrlService.UrlNotFoundException.class, () -> urlService.resolveLongUrl("missing"));
    }

    // -------------------------------------------------------------------------
    // isBlacklisted() tests
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://bit.ly/3xKq1Z",
            "http://bit.ly/abc",
            "https://tinyurl.com/somelink",
            "https://t.co/xyz"
    })
    void isBlacklisted_throwsForKnownShorteners(String url) {
        BlacklistedUrlException ex = assertThrows(
                BlacklistedUrlException.class,
                () -> urlService.isBlacklisted(url)
        );
        assertThat(ex.getMessage()).isEqualTo("URL from known shortener services are not allowed.");
    }

    @Test
    void isBlacklisted_withBitlyUrl_throwsBlacklistedUrlException() {
        BlacklistedUrlException ex = assertThrows(
                BlacklistedUrlException.class,
                () -> urlService.isBlacklisted("https://bit.ly/xyz")
        );
        assertThat(ex.getMessage()).isEqualTo("URL from known shortener services are not allowed.");
    }

    @Test
    void isBlacklisted_withGoogleUrl_passesWithoutException() {
        // "https://google.com" should pass without throwing
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            urlService.isBlacklisted("https://google.com");
        });
    }

    @Test
    void isBlacklisted_doesNotThrowForCleanUrl() {
        // Should complete without throwing
        urlService.isBlacklisted("https://github.com/ishank");
    }

    @Test
    void isBlacklisted_matchesSubdomainsOfBlacklist() {
        // e.g. a hypothetical api.bit.ly should also be blocked
        assertThrows(
                BlacklistedUrlException.class,
                () -> urlService.isBlacklisted("https://api.bit.ly/link")
        );
    }

    @Test
    void createShortUrl_throwsBlacklisted_beforeAnyDbAccess() {
        URLRequest request = new URLRequest();
        request.setLongUrl("https://tinyurl.com/blocked");

        assertThrows(BlacklistedUrlException.class, () -> urlService.createShortUrl(request));

        // Repository must never be touched when URL is blacklisted
        verifyNoInteractions(urlRepository);
    }

    // -------------------------------------------------------------------------
    // generateVanityCode() tests
    // -------------------------------------------------------------------------

    @Test
    void generateVanityCode_extractsCorrectDomainLabel() {
        String code = urlService.generateVanityCode("https://github.com/ishank");
        // Code must start with the domain label
        assertThat(code).startsWith("github-");
    }

    @Test
    void generateVanityCode_stripsWwwPrefix() {
        String code = urlService.generateVanityCode("https://www.example.com/page");
        assertThat(code).startsWith("example-");
    }

    @Test
    void generateVanityCode_hasCorrectFormat() {
        String code = urlService.generateVanityCode("https://google.com");
        // Must match pattern: <word>-<3 alphanumeric chars>
        assertThat(code).matches("[a-z0-9]+-[a-z0-9]{3}");
    }

    @Test
    void generateVanityCode_suffixIsAlwaysThreeChars() {
        for (int i = 0; i < 20; i++) {
            String code = urlService.generateVanityCode("https://example.com");
            String suffix = code.substring(code.lastIndexOf('-') + 1);
            assertThat(suffix).hasSize(3);
        }
    }
}
