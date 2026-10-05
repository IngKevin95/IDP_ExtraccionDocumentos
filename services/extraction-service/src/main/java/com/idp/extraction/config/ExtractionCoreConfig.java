package com.idp.extraction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.extraction.reliability.Calibrator;
import com.idp.extraction.reliability.IdentityCalibrator;
import com.idp.extraction.reliability.IsotonicCalibrator;
import com.idp.extraction.typology.TypologyRegistry;
import com.idp.extraction.validation.ValidatorRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * Beans de dominio sin dependencias externas. Las tipologias se cargan y validan al arrancar:
 * un YAML invalido o un validador inexistente impide el arranque (fail-fast, SEC-023).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtractionProperties.class)
public class ExtractionCoreConfig {

    @Bean
    ValidatorRegistry validatorRegistry() {
        return ValidatorRegistry.defaults();
    }

    @Bean
    TypologyRegistry typologyRegistry(ValidatorRegistry validators) {
        return TypologyRegistry.loadDefault(validators);
    }

    @Bean
    ObjectMapper idpObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    Calibrator calibrator(ExtractionProperties props, ResourceLoader loader) {
        String location = props.calibration().resource();
        if (location == null || location.isBlank()) {
            return new IdentityCalibrator();
        }
        try (InputStream in = loader.getResource(location).getInputStream()) {
            return IsotonicCalibrator.fromJson(in);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar la calibracion isotonica", e);
        }
    }
}
