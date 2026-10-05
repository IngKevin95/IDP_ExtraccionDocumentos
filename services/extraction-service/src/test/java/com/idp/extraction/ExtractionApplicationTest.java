package com.idp.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.extraction.typology.TypologyRegistry;
import com.idp.extraction.validation.ValidatorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ExtractionApplicationTest {

    @Autowired
    private TypologyRegistry typologies;

    @Autowired
    private ValidatorRegistry validators;

    @Test
    void contextLoadsAndTypologiesAreLoadedAtStartup() {
        assertThat(typologies.activeDefinitions()).hasSize(4);
        assertThat(validators.contains("nit_modulo_11")).isTrue();
    }
}
