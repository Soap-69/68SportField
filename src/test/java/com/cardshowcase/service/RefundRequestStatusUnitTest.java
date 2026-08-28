package com.cardshowcase.service;

import com.cardshowcase.model.entity.RefundRequestStatus;
import org.junit.jupiter.api.Test;

import static com.cardshowcase.model.entity.RefundRequestStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class RefundRequestStatusUnitTest {

    @Test
    void pendingApproval_canTransitionTo_approved() {
        assertThat(PENDING_APPROVAL.canTransitionTo(APPROVED)).isTrue();
    }

    @Test
    void pendingApproval_canTransitionTo_rejected() {
        assertThat(PENDING_APPROVAL.canTransitionTo(REJECTED)).isTrue();
    }

    @Test
    void approved_canTransitionTo_executed() {
        assertThat(APPROVED.canTransitionTo(EXECUTED)).isTrue();
    }

    @Test
    void approved_canTransitionTo_failed() {
        assertThat(APPROVED.canTransitionTo(FAILED)).isTrue();
    }

    @Test
    void pendingApproval_cannotTransitionTo_executed() {
        assertThat(PENDING_APPROVAL.canTransitionTo(EXECUTED)).isFalse();
    }

    @Test
    void executed_cannotTransitionTo_anything() {
        for (RefundRequestStatus s : RefundRequestStatus.values()) {
            assertThat(EXECUTED.canTransitionTo(s))
                .as("EXECUTED should not transition to " + s)
                .isFalse();
        }
    }

    @Test
    void rejected_cannotTransitionTo_anything() {
        for (RefundRequestStatus s : RefundRequestStatus.values()) {
            assertThat(REJECTED.canTransitionTo(s))
                .as("REJECTED should not transition to " + s)
                .isFalse();
        }
    }

    @Test
    void failed_cannotTransitionTo_anything() {
        for (RefundRequestStatus s : RefundRequestStatus.values()) {
            assertThat(FAILED.canTransitionTo(s))
                .as("FAILED should not transition to " + s)
                .isFalse();
        }
    }
}
