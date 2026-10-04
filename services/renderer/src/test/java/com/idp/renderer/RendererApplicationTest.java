package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.renderer.api.RenderController;
import com.idp.renderer.security.AntivirusScanner;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

@SpringBootTest
class RendererApplicationTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    Environment env;

    @Test
    void contextLoads() {
        assertThat(ctx.getBean(RenderController.class)).isNotNull();
        assertThat(ctx.getBean(AntivirusScanner.class)).isNotNull();
    }

    @Test
    void ac09_sinBdKafkaNiCredenciales() {
        assertThat(ctx.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThatThrownBy(() -> Class.forName("org.springframework.kafka.core.KafkaTemplate"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(env.getProperty("spring.datasource.url")).isNull();
        assertThat(env.getProperty("spring.kafka.bootstrap-servers")).isNull();
    }
}
