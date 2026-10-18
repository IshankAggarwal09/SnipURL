package com.ishank.urlshortener.controllers;

import com.ishank.urlshortener.dto.URLRequest;
import com.ishank.urlshortener.dto.URLResponse;
import com.ishank.urlshortener.services.UrlService;
import com.ishank.urlshortener.services.UrlService.AliasAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebControllerTest {

    @Mock
    private UrlService urlService;

    @Mock
    private com.ishank.urlshortener.services.AnalyticsService analyticsService;

    @Mock
    private Model model;

    @Mock
    private BindingResult bindingResult;

    private WebController webController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        webController = new WebController(urlService, analyticsService);
    }

    @Test
    void index_addsEmptyRequestToModel() {
        String view = webController.index(model);
        assertThat(view).isEqualTo("index");
        verify(model).addAttribute(eq("urlRequest"), any(URLRequest.class));
    }

    @Test
    void shortenUrl_addsResultToModel_onSuccess() {
        URLRequest request = new URLRequest();
        URLResponse response = URLResponse.builder().shortCode("abc").build();
        when(bindingResult.hasErrors()).thenReturn(false);
        when(urlService.createShortUrl(request)).thenReturn(response);

        String view = webController.shortenUrl(request, bindingResult, model);
        
        assertThat(view).isEqualTo("index");
        verify(model).addAttribute("urlRequest", request);
        verify(model).addAttribute("result", response);
    }

    @Test
    void shortenUrl_addsErrorToModel_onValidationError() {
        URLRequest request = new URLRequest();
        when(bindingResult.hasErrors()).thenReturn(true);
        when(bindingResult.getAllErrors()).thenReturn(Collections.singletonList(new ObjectError("urlRequest", "Validation failed")));

        String view = webController.shortenUrl(request, bindingResult, model);

        assertThat(view).isEqualTo("index");
        verify(model).addAttribute("urlRequest", request);
        verify(model).addAttribute("error", "Validation failed");
    }

    @Test
    void shortenUrl_addsErrorToModel_whenAliasExists() {
        URLRequest request = new URLRequest();
        when(bindingResult.hasErrors()).thenReturn(false);
        when(urlService.createShortUrl(request)).thenThrow(new AliasAlreadyExistsException("exists"));

        String view = webController.shortenUrl(request, bindingResult, model);

        assertThat(view).isEqualTo("index");
        verify(model).addAttribute("urlRequest", request);
        verify(model).addAttribute(eq("error"), any(String.class));
    }

    @Test
    void shortenUrl_addsErrorToModel_onGeneralException() {
        URLRequest request = new URLRequest();
        when(bindingResult.hasErrors()).thenReturn(false);
        when(urlService.createShortUrl(request)).thenThrow(new RuntimeException("DB error"));

        String view = webController.shortenUrl(request, bindingResult, model);

        assertThat(view).isEqualTo("index");
        verify(model).addAttribute("urlRequest", request);
        verify(model).addAttribute(eq("error"), any(String.class));
    }
}
