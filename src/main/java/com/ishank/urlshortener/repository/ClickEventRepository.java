package com.ishank.urlshortener.repository;

import com.ishank.urlshortener.models.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    List<ClickEvent> findByShortUrlOrderByClickedAtDesc(String shortUrl);
    long countByShortUrl(String shortUrl);
    
    @org.springframework.data.jpa.repository.Query("SELECT c.shortUrl AS shortUrl, COUNT(c) AS clickCount FROM ClickEvent c GROUP BY c.shortUrl ORDER BY COUNT(c) DESC")
    List<Object[]> findTopUrlsAggregated(org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ClickEvent c WHERE c.shortUrl = :shortUrl")
    void deleteByShortUrl(@org.springframework.data.repository.query.Param("shortUrl") String shortUrl);
}
