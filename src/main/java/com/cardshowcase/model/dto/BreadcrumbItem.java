package com.cardshowcase.model.dto;

/**
 * A single crumb in a page breadcrumb trail.
 * {@code url} is null for the current (last) crumb — rendered as plain text.
 */
public record BreadcrumbItem(String label, String url) {}
