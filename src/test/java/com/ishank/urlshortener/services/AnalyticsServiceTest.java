package com.ishank.urlshortener.services;

import com.ishank.urlshortener.dto.AnalyticsResponse;
import com.ishank.urlshortener.models.ClickEvent;
import com.ishank.urlshortener.models.URL;
import com.ishank.urlshortener.repository.ClickEventRepository;
import com.ishank.urlshortener.repository.UrlRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private ClickEventRepository clickEventRepository;
    private UrlRepository urlRepository;
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        clickEventRepository = mock(ClickEventRepository.class);
        urlRepository = mock(UrlRepository.class);
        analyticsService = new AnalyticsService(clickEventRepository, urlRepository);
    }

    @Test
    void recordClick_usesXForwardedFor_whenPresent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.1");
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

        analyticsService.recordClick("abcd", request);

        ArgumentCaptor<ClickEvent> eventCaptor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventRepository).save(eventCaptor.capture());

        ClickEvent saved = eventCaptor.getValue();
        assertThat(saved.getShortUrl()).isEqualTo("abcd");
        assertThat(saved.getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getCountry()).isEqualTo("IN");
        assertThat(saved.getClickedAt()).isNotNull();
    }

    @Test
    void recordClick_usesRemoteAddr_whenXForwardedForIsMissing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        when(request.getHeader("User-Agent")).thenReturn("Chrome");

        analyticsService.recordClick("1234", request);

        ArgumentCaptor<ClickEvent> eventCaptor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventRepository).save(eventCaptor.capture());

        ClickEvent saved = eventCaptor.getValue();
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.5");
        assertThat(saved.getUserAgent()).isEqualTo("Chrome");
        assertThat(saved.getCountry()).isEqualTo("IN");
    }

    @Test
    void deriveCountryFromIp_returnsPlaceholderCountry() {
        assertThat(analyticsService.deriveCountryFromIp("8.8.8.8")).isEqualTo("IN");
        assertThat(analyticsService.deriveCountryFromIp("127.0.0.1")).isEqualTo("IN");
    }

    @Test
    void getStats_returnsFormattedStats() {
        when(clickEventRepository.countByShortUrl("xyz")).thenReturn(150L);

        ClickEvent event1 = ClickEvent.builder()
                .ipAddress("1.1.1.1")
                .userAgent("Safari")
                .country("IN")
                .clickedAt(LocalDateTime.of(2026, 3, 23, 10, 30, 0))
                .build();
        
        ClickEvent event2 = ClickEvent.builder()
                .ipAddress("2.2.2.2")
                .userAgent("Firefox")
                .country("IN")
                .clickedAt(LocalDateTime.of(2026, 3, 23, 11, 45, 30))
                .build();

        when(clickEventRepository.findByShortUrlOrderByClickedAtDesc("xyz"))
                .thenReturn(List.of(event1, event2));

        AnalyticsResponse response = analyticsService.getStats("xyz");

        assertThat(response.getShortUrl()).isEqualTo("xyz");
        assertThat(response.getTotalClicks()).isEqualTo(150L);
        assertThat(response.getRecentClicks()).hasSize(2);

        assertThat(response.getRecentClicks().get(0).getIpAddress()).isEqualTo("1.1.1.1");
        assertThat(response.getRecentClicks().get(0).getClickedAt()).isEqualTo("2026-03-23 10:30:00");
    }

    @Test
    void getTopUrls_withLimit3_returnsOnlyTop3SortedByClicks() {
        // Mock repository returning top 3 when limit is 3 (PageRequest of 3 items)
        Object[] row1 = new Object[]{"shortA", 500L};
        Object[] row2 = new Object[]{"shortB", 300L};
        Object[] row3 = new Object[]{"shortC", 150L};

        // When requesting limit 3, repository is queried with PageRequest(0, 3) and returns 3 rows
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        when(clickEventRepository.findTopUrlsAggregated(pageableCaptor.capture()))
                .thenReturn(List.of(row1, row2, row3));

        when(urlRepository.findByShortUrl("shortA"))
                .thenReturn(Optional.of(URL.builder().shortUrl("shortA").longUrl("https://a.com").build()));
        when(urlRepository.findByShortUrl("shortB"))
                .thenReturn(Optional.of(URL.builder().shortUrl("shortB").longUrl("https://b.com").build()));
        when(urlRepository.findByShortUrl("shortC"))
                .thenReturn(Optional.of(URL.builder().shortUrl("shortC").longUrl("https://c.com").build()));

        List<Map<String, Object>> top3 = analyticsService.getTopUrls(3);

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(3);
        assertThat(top3).hasSize(3);

        assertThat(top3.get(0)).containsEntry("shortCode", "shortA");
        assertThat(top3.get(0)).containsEntry("longUrl", "https://a.com");
        assertThat(top3.get(0)).containsEntry("clickCount", 500L);

        assertThat(top3.get(1)).containsEntry("shortCode", "shortB");
        assertThat(top3.get(1)).containsEntry("longUrl", "https://b.com");
        assertThat(top3.get(1)).containsEntry("clickCount", 300L);

        assertThat(top3.get(2)).containsEntry("shortCode", "shortC");
        assertThat(top3.get(2)).containsEntry("longUrl", "https://c.com");
        assertThat(top3.get(2)).containsEntry("clickCount", 150L);
    }

    @Test
    void getTopUrls_returnsRankedUrlsWithMetadata() {
        Object[] row1 = new Object[]{"short1", 100L};
        Object[] row2 = new Object[]{"short2", 50L};
        when(clickEventRepository.findTopUrlsAggregated(any(Pageable.class)))
                .thenReturn(List.of(row1, row2));

        URL url1 = URL.builder().shortUrl("short1").longUrl("https://example1.com").build();
        when(urlRepository.findByShortUrl("short1")).thenReturn(Optional.of(url1));
        when(urlRepository.findByShortUrl("short2")).thenReturn(Optional.empty());

        List<Map<String, Object>> topUrls = analyticsService.getTopUrls(2);

        assertThat(topUrls).hasSize(2);
        assertThat(topUrls.get(0)).containsEntry("shortCode", "short1");
        assertThat(topUrls.get(0)).containsEntry("longUrl", "https://example1.com");
        assertThat(topUrls.get(0)).containsEntry("clickCount", 100L);

        assertThat(topUrls.get(1)).containsEntry("shortCode", "short2");
        assertThat(topUrls.get(1)).containsEntry("longUrl", "N/A");
        assertThat(topUrls.get(1)).containsEntry("clickCount", 50L);
    }
}
