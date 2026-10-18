package com.ishank.urlshortener.repository;

import com.ishank.urlshortener.models.URL;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UrlRepository extends JpaRepository<URL, Integer> {

    Optional<URL> findByShortUrl(String shortUrl);

    Optional<URL> findByShortUrlAndActiveTrue(String shortUrl);

    Optional<URL> findByLongUrlAndActiveTrue(String longUrl);

    boolean existsByShortUrl(String shortUrl);

    List<URL> findByExpiresAtBefore(java.time.LocalDateTime now);
}

