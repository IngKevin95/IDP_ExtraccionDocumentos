package com.idp.renderer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RendererApplication {
    public static void main(String[] args) {
        SpringApplication.run(RendererApplication.class, args);
    }
}
