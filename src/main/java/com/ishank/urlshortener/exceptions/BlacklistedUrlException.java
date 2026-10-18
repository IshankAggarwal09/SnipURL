package com.ishank.urlshortener.exceptions;

/**
 * Thrown when a submitted URL belongs to a known URL-shortener service
 * (e.g. bit.ly, tinyurl.com, t.co) that is blocked from being re-shortened.
 *
 * <p>Shortening an already-shortened URL creates ambiguous redirect chains and
 * is a common vector for link-spam abuse. This exception signals an explicit
 * policy rejection rather than a generic validation failure.</p>
 *
 * @author Ishank Aggarwal
 */
public class BlacklistedUrlException extends RuntimeException {

    public BlacklistedUrlException(String message) {
        super(message);
    }
}
