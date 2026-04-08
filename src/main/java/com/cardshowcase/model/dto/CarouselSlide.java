package com.cardshowcase.model.dto;

/**
 * Represents a single slide in the homepage coverflow carousel.
 * Can come from a Banner or a featured Product.
 */
public record CarouselSlide(String imageUrl, String title, String linkUrl) {}
