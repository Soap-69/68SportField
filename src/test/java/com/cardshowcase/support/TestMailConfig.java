package com.cardshowcase.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;

/** Replaces the autoconfigured JavaMailSender in the test profile with the recording double. */
@Configuration
@Profile("test")
public class TestMailConfig {

    @Bean
    @Primary
    public JavaMailSender recordingMailSender() {
        return new RecordingMailSender();
    }
}
