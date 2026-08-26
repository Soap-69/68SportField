package com.cardshowcase.model.dto;

import jakarta.validation.constraints.NotBlank;

public class RecordTrackingRequest {

    @NotBlank(message = "carrier is required")
    private String carrier;

    @NotBlank(message = "trackingNumber is required")
    private String trackingNumber;

    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }

    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
}
