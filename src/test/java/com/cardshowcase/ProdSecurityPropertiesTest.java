package com.cardshowcase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** T16: the real production profile file forces Secure on the session and cart cookies (Week 9 O2). */
class ProdSecurityPropertiesTest {

    @Test
    void productionProfileForcesSecureCookies() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties props = yaml.getObject();

        assertThat(props).isNotNull();
        assertThat(props.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(props.getProperty("app.cart.cookie.secure")).isEqualTo("true");
        assertThat(props.getProperty("app.base-url")).isEqualTo("https://68sportfield.com");
    }
}
