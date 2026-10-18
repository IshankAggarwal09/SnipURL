package com.ishank.urlshortener.services;

import com.ishank.urlshortener.dto.AnalyticsResponse;
import com.ishank.urlshortener.models.ClickEvent;
import com.ishank.urlshortener.models.URL;
import com.ishank.urlshortener.repository.ClickEventRepository;
import com.ishank.urlshortener.repository.UrlRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final ClickEventRepository clickEventRepository;
    private final UrlRepository urlRepository;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Derives country code from IP address.
     * TODO: Integrate with GeoIP provider (e.g. MaxMind GeoLite2 or ipstack) for real geographic resolution.
     *
     * @param ipAddress the client IP address
     * @return ISO country code (placeholder "IN")
     */
    public String deriveCountryFromIp(String ipAddress) {
        // TODO: Replace with real GeoIP lookup
        return "IN";
    }

    @Transactional
    public void recordClick(String shortUrl, HttpServletRequest request) {
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }

        String userAgent = request.getHeader("User-Agent");
        String country = deriveCountryFromIp(ipAddress);

        ClickEvent event = ClickEvent.builder()
                .shortUrl(shortUrl)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .country(country)
                .clickedAt(LocalDateTime.now())
                .build();

        clickEventRepository.save(event);
    }

    public AnalyticsResponse getStats(String shortUrl) {
        long totalClicks = clickEventRepository.countByShortUrl(shortUrl);
        
        List<ClickEvent> recentEvents = clickEventRepository.findByShortUrlOrderByClickedAtDesc(shortUrl);
        List<AnalyticsResponse.ClickDetails> details = recentEvents.stream()
                .limit(50) // only show last 50 clicks in UI for performance
                .map(e -> AnalyticsResponse.ClickDetails.builder()
                        .ipAddress(e.getIpAddress())
                        .userAgent(e.getUserAgent())
                        .country(e.getCountry() != null ? e.getCountry() : "IN")
                        .clickedAt(e.getClickedAt().format(FORMATTER))
                        .build())
                .collect(Collectors.toList());

        return AnalyticsResponse.builder()
                .shortUrl(shortUrl)
                .totalClicks(totalClicks)
                .recentClicks(details)
                .build();
    }

    /**
     * Retrieves the top N most clicked short URLs sorted by click count descending.
     *
     * @param limit maximum number of URLs to return
     * @return list of maps with keys: shortCode, longUrl, clickCount
     */
    public List<Map<String, Object>> getTopUrls(int limit) {
        int effectiveLimit = Math.max(1, limit);
        List<Object[]> rows = clickEventRepository.findTopUrlsAggregated(PageRequest.of(0, effectiveLimit));

        List<Map<String, Object>> topUrls = new ArrayList<>();
        for (Object[] row : rows) {
            String shortCode = (String) row[0];
            Long clickCount = (Long) row[1];

            String longUrl = urlRepository.findByShortUrl(shortCode)
                    .map(URL::getLongUrl)
                    .orElse("N/A");

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("shortCode", shortCode);
            map.put("longUrl", longUrl);
            map.put("clickCount", clickCount);
            topUrls.add(map);
        }

        return topUrls;
    }
}

