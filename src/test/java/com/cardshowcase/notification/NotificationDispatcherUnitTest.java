package com.cardshowcase.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherUnitTest {

    @Mock NotificationLogWriter writer;
    @Mock NotificationRenderer renderer;
    @Mock NotificationSettings settings;
    @Mock ObjectProvider<JavaMailSender> provider;
    @Mock JavaMailSender sender;

    @Test
    void noMailSenderBean_marksSkippedDisabled_neverClaims() {
        when(provider.getIfAvailable()).thenReturn(null);
        when(writer.markSkippedDisabled(7L)).thenReturn(true);

        DeliveryOutcome outcome = new NotificationDispatcher(writer, renderer, settings, provider).deliver(7L);

        assertThat(outcome).isEqualTo(DeliveryOutcome.SKIPPED_DISABLED);
        verify(writer).markSkippedDisabled(7L);
        verify(writer, never()).claim(anyLong());
        verifyNoInteractions(renderer);
    }

    @Test
    void disabledFlag_withSenderPresent_marksSkippedDisabled_neverClaims() {
        when(provider.getIfAvailable()).thenReturn(sender);
        when(settings.isEnabled()).thenReturn(false);
        when(writer.markSkippedDisabled(7L)).thenReturn(true);

        DeliveryOutcome outcome = new NotificationDispatcher(writer, renderer, settings, provider).deliver(7L);

        assertThat(outcome).isEqualTo(DeliveryOutcome.SKIPPED_DISABLED);
        verify(writer, never()).claim(anyLong());
        verifyNoInteractions(renderer, sender);
    }

    @Test
    void claimLost_returnsNotClaimed_withoutRenderingOrSending() {
        when(provider.getIfAvailable()).thenReturn(sender);
        when(settings.isEnabled()).thenReturn(true);
        when(writer.claim(7L)).thenReturn(false);

        DeliveryOutcome outcome = new NotificationDispatcher(writer, renderer, settings, provider).deliver(7L);

        assertThat(outcome).isEqualTo(DeliveryOutcome.NOT_CLAIMED);
        verify(writer, never()).markSent(anyLong());
        verify(writer, never()).markFailed(anyLong(), anyString());
        verifyNoInteractions(renderer, sender);
    }
}
