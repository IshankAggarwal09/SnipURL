package com.ishank.urlshortener.services;

import com.ishank.urlshortener.dto.URLRequest;
import com.ishank.urlshortener.dto.URLResponse;
import com.ishank.urlshortener.exceptions.BlacklistedUrlException;
import com.ishank.urlshortener.models.URL;
import com.ishank.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class UrlService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** Domains that are themselves URL shorteners and must not be re-shortened. */
    private static final List<String> BLACKLISTED_DOMAINS = List.of(
            "bit.ly", "tinyurl.com", "t.co"
    );

    private static final Random RANDOM = new Random();

    private final UrlRepository urlRepository;
    private final StringRedisTemplate redisTemplate;
    @Value("${app.base-url:http://localhost:8080/api/urls}")
    private String baseUrl;

    private String shortKey(String shortCode) {
        return "short:" + shortCode;
    }

    private String longKey(String longUrl) {
        return "long:" + longUrl;
    }

    @Transactional
    public URLResponse createShortUrl(URLRequest request) {
        String longUrl = request.getLongUrl();

        // Reject URLs from known shortener services before doing any work
        isBlacklisted(longUrl);
        LocalDateTime expirationTime = request.getExpirationTime();

        // check cache for existing mapping (idempotent behavior)
        String cachedShortCode = redisTemplate.opsForValue().get(longKey(longUrl));
        if (cachedShortCode != null) {
            URL existing = urlRepository.findByShortUrlAndActiveTrue(cachedShortCode)
                    .orElseThrow(() -> new UrlNotFoundException("Short URL not found for cached code"));
            if (isExpired(existing)) {
                deactivate(existing);
            } else {
                return toResponse(existing);
            }
        }

        String desiredShortCode = request.getCustomAlias();
        if (desiredShortCode != null && !desiredShortCode.isBlank()) {
            if (urlRepository.existsByShortUrl(desiredShortCode)) {
                throw new AliasAlreadyExistsException("Custom alias already exists: " + desiredShortCode);
            }
        }

        URL url = URL.builder()
                .longUrl(longUrl)
                .expiresAt(expirationTime)
                .active(true)
                .shortUrl(desiredShortCode != null && !desiredShortCode.isBlank() ? desiredShortCode : "T" + (int)(Math.random() * 9000000 + 1000000))
                .build();

        // first save to generate numeric ID
        URL saved = urlRepository.save(url);

        String shortCode = desiredShortCode;
        if (shortCode == null || shortCode.isBlank()) {
            shortCode = Base62Encoder.encode(saved.getId());
        }

        saved.setShortUrl(shortCode);
        System.out.println("UrlService.createShortUrl short Code : " + shortCode);
        URL finalEntity = urlRepository.save(saved);

        cacheMapping(finalEntity);

        return toResponse(finalEntity);
    }

    @Transactional
    public String resolveLongUrl(String shortCode) {
        // check cache first
        String cachedLong = redisTemplate.opsForValue().get(shortKey(shortCode));
        if (cachedLong != null) {
            // even on cache hit, verify the URL has not expired
            Optional<URL> optionalUrl = urlRepository.findByShortUrlAndActiveTrue(shortCode);
            if (optionalUrl.isPresent() && isExpired(optionalUrl.get())) {
                deactivate(optionalUrl.get());
                evictCache(shortCode, cachedLong);
                throw new UrlExpiredException("Short URL has expired: " + shortCode);
            }
            return cachedLong;
        }

        Optional<URL> optionalUrl = urlRepository.findByShortUrlAndActiveTrue(shortCode);
        URL url = optionalUrl.orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + shortCode));

        if (isExpired(url)) {
            // mark inactive and throw dedicated exception
            deactivate(url);
            throw new UrlExpiredException("Short URL has expired: " + shortCode);
        }

        cacheMapping(url);
        return url.getLongUrl();
    }

    private void cacheMapping(URL url) {
        if (!url.isActive()) {
            return;
        }
        String code = url.getShortUrl();
        String longUrl = url.getLongUrl();
        redisTemplate.opsForValue().set(shortKey(code), longUrl, CACHE_TTL);
        redisTemplate.opsForValue().set(longKey(longUrl), code, CACHE_TTL);
    }

    private boolean isExpired(URL url) {
        LocalDateTime expiresAt = url.getExpiresAt();
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    private void deactivate(URL url) {
        if (url.isActive()) {
            url.setActive(false);
            urlRepository.save(url);
        }
    }

    private void evictCache(String shortCode, String longUrl) {
        redisTemplate.delete(shortKey(shortCode));
        redisTemplate.delete(longKey(longUrl));
    }

    private URLResponse toResponse(URL url) {
        String shortCode = url.getShortUrl();
        String fullShortUrl = baseUrl.endsWith("/")
                ? baseUrl + shortCode
                : baseUrl + "/" + shortCode;

        return URLResponse.builder()
                .shortUrl(fullShortUrl)
                .shortCode(shortCode)
                .longUrl(url.getLongUrl())
                .expirationTime(url.getExpiresAt())
                .build();
    }

    /**
     * Checks whether the given URL's host belongs to a blacklisted shortener service.
     *
     * <p>The check is host-only (scheme and path are ignored) so that both
     * {@code http://bit.ly/xyz} and {@code https://bit.ly/abc} are caught.</p>
     *
     * @param url the raw URL string to inspect
     * @throws BlacklistedUrlException if the host matches any entry in
     *                                 {@link #BLACKLISTED_DOMAINS}
     */
    public void isBlacklisted(String url) {
        try {
            String host = new URI(url).getHost();
            if (host != null) {
                String lowerHost = host.toLowerCase();
                for (String blocked : BLACKLISTED_DOMAINS) {
                    if (lowerHost.equals(blocked) || lowerHost.endsWith("." + blocked)) {
                        throw new BlacklistedUrlException(
                                "URL from known shortener services are not allowed.");
                    }
                }
            }
        } catch (URISyntaxException e) {
            // Malformed URLs are not blacklisted here; validation handles them upstream
        }
    }

    /**
     * Generates a deterministic vanity short code from the URL's domain name
     * combined with a 3-digit random alphanumeric suffix.
     *
     * <p>Example: {@code "https://github.com/ishank"} → {@code "github-a3f"}<br>
     * The domain label used is the second-to-last segment (i.e. the registrable
     * domain without TLD), so subdomains and TLDs are stripped for readability.
     * The random suffix ensures codes stay unique across multiple shortenings of
     * the same domain.</p>
     *
     * @param longUrl the original URL whose domain is used as the code prefix
     * @return a short code in the form {@code "<domain>-<3-char suffix>"}, or a
     *         random fallback code if the URL cannot be parsed
     */
    public String generateVanityCode(String longUrl) {
        String domainLabel = extractDomainLabel(longUrl);
        String suffix = randomAlphanumericSuffix(3);
        return domainLabel + "-" + suffix;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the registrable domain label (e.g. "github" from "github.com").
     * Falls back to {@code "url"} if the host cannot be determined.
     */
    private String extractDomainLabel(String longUrl) {
        try {
            String host = new URI(longUrl).getHost();
            if (host == null || host.isBlank()) {
                return "url";
            }
            // Strip leading "www." for cleaner codes
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            // Use the first segment (registrable domain without TLD)
            String[] parts = host.split("\\.");
            return parts.length >= 2 ? parts[parts.length - 2] : parts[0];
        } catch (URISyntaxException e) {
            return "url";
        }
    }

    /**
     * Generates a lowercase alphanumeric string of the requested length
     * using digits 0–9 and letters a–z.
     */
    private String randomAlphanumericSuffix(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public static class AliasAlreadyExistsException extends RuntimeException {
        public AliasAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class UrlNotFoundException extends RuntimeException {
        public UrlNotFoundException(String message) {
            super(message);
        }
    }

    public static class UrlExpiredException extends RuntimeException {
        public UrlExpiredException(String message) {
            super(message);
        }
    }
}
