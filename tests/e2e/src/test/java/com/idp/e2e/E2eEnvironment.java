package com.idp.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.audit.AuditApplication;
import com.idp.document.DocumentApplication;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.extraction.ExtractionApplication;
import com.idp.quality.QualityApplication;
import com.idp.review.ReviewApplication;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Entorno de extremo a extremo sin Docker, compartido por todas las pruebas de la JVM: Kafka embebido (KRaft),
 * H2 por silo de tenant y para la base de control, y los tres servicios (document, extraction, audit) cada uno
 * en su propio contexto Spring con puerto aleatorio.
 */
final class E2eEnvironment {

    static final com.idp.events.EventTopology TOPOLOGY = com.idp.events.EventTopology.defaults();
    /** Topicos de la topologia (un topico por productor, ADR 0029); el broker embebido los crea todos. */
    static final String[] TOPICS = TOPOLOGY.allTopics();
    static final String CONTROL_URL = "jdbc:h2:mem:e2e_control;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR(100000)";
    static final ObjectMapper JSON = new ObjectMapper();

    static final String TENANT_A = UUID.randomUUID().toString();
    static final String TENANT_B = UUID.randomUUID().toString();
    /** Tenant dedicado al shredding de KEK: deshabilitar su llave no afecta a las demas pruebas. */
    static final String TENANT_C = UUID.randomUUID().toString();
    /** Tenant del lazo del muestreo ciego (quality-service y review-service). */
    static final String TENANT_D = UUID.randomUUID().toString();
    static final String REVISOR_D = "revisor-d";
    /** Quien cargo el documento: tiene rol REVISOR pero no puede hacer la revision ciega. */
    static final String UPLOADER_D = "operador-d";
    static final String STEWARD_D = "steward-d";
    /** Rol de gestion: unico que ve el flag blindSample de la tarea (el REVISOR no debe saber que es ciega). */
    static final String ADMIN_D = "admin-d";
    static final String QUALITY_URL = "jdbc:h2:mem:e2e_quality;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    static final String OPERATOR_A = "operador-a";
    static final String OPERATOR_B = "operador-b";
    static final String OPERATOR_C = "operador-c";
    static final String AUDITOR_A = "auditor-a";

    private static E2eEnvironment instance;

    private final EmbeddedKafkaKraftBroker kafka;
    private final RSAPrivateKey jwtKey;
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    final int documentPort;
    final int auditPort;
    final int qualityPort;
    final int reviewPort;
    final EventSchemaValidator validator = new EventSchemaValidator(new EventSerde());

    static synchronized E2eEnvironment get() {
        if (instance == null) {
            instance = new E2eEnvironment();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::close));
        }
        return instance;
    }

    private E2eEnvironment() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            jwtKey = (RSAPrivateKey) pair.getPrivate();
            Overrides.Shared.jwtPublicKey = (RSAPublicKey) pair.getPublic();

            kafka = new EmbeddedKafkaKraftBroker(1, 1, TOPICS);
            kafka.afterPropertiesSet();

            seedDatabases();
            documentPort = start("document-service", DocumentApplication.class, Overrides.DocumentOverrides.class,
                documentProps());
            start("extraction-service", ExtractionApplication.class, Overrides.ExtractionOverrides.class,
                extractionProps());
            auditPort = start("audit-service", AuditApplication.class, Overrides.AuditOverrides.class, auditProps());
            qualityPort = start("quality-service", QualityApplication.class, Overrides.QualityOverrides.class,
                qualityProps());
            reviewPort = start("review-service", ReviewApplication.class, Overrides.ReviewOverrides.class,
                reviewProps());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo levantar el entorno e2e", e);
        }
    }

    // ---- bases de datos ---------------------------------------------------------------------------------

    private static void seedDatabases() throws Exception {
        try (Connection c = DriverManager.getConnection(CONTROL_URL, "sa", "")) {
            c.createStatement().execute("create table tenants (id uuid primary key, status varchar(20) not null)");
            c.createStatement().execute("create table silo_location (tenant_id uuid primary key, "
                + "bucket_name varchar(255) not null)");
            c.createStatement().execute("create table tenant_config (tenant_id uuid primary key, "
                + "data_kek_id varchar(255), audit_kek_id varchar(255))");
            c.createStatement().execute("create table role_assignment (id uuid primary key, tenant_id uuid not null, "
                + "user_id varchar(255) not null, role varchar(50) not null, expires_at timestamp with time zone, "
                + "deleted_at timestamp with time zone)");
            for (String t : List.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D)) {
                c.createStatement().execute("insert into tenants values ('" + t + "', 'ACTIVE')");
                c.createStatement().execute("insert into silo_location values ('" + t + "', 'idp-" + t
                    + "-docs')");
                c.createStatement().execute("insert into tenant_config values ('" + t + "', 'documents', 'audit')");
            }
            grant(c, TENANT_A, OPERATOR_A, com.idp.security.Roles.OPERADOR);
            grant(c, TENANT_B, OPERATOR_B, com.idp.security.Roles.OPERADOR);
            grant(c, TENANT_C, OPERATOR_C, com.idp.security.Roles.OPERADOR);
            grant(c, TENANT_A, AUDITOR_A, com.idp.security.Roles.AUDITOR);
            grant(c, TENANT_D, REVISOR_D, com.idp.security.Roles.REVISOR);
            grant(c, TENANT_D, UPLOADER_D, com.idp.security.Roles.REVISOR);
            grant(c, TENANT_D, ADMIN_D, com.idp.security.Roles.TENANT_ADMIN);
        }
        try (Connection c = DriverManager.getConnection(QUALITY_URL, "sa", "")) {
            // Migraciones de quality-service por nombre: Flyway sobre classpath:db/migration colisiona con las de
            // document-service (mismo V1) cuando comparten JVM.
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V1__init_quality_schema.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V2__outbox_and_sample_id.sql"));
            c.createStatement().execute("create table tenants (id uuid primary key, status varchar(20) not null)");
            c.createStatement().execute("insert into tenants values ('" + TENANT_D + "', 'ACTIVE')");
            c.createStatement().execute("create table role_assignment (id uuid primary key, tenant_id uuid not null, "
                + "user_id varchar(255) not null, role varchar(50) not null, expires_at timestamp with time zone, "
                + "deleted_at timestamp with time zone)");
            grant(c, TENANT_D, STEWARD_D, com.idp.security.Roles.DATA_STEWARD);
        }
        for (String t : List.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D)) {
            // Silo del review-service: su esquema, las tablas del extraction-service que lee y el cargador del documento.
            try (Connection c = DriverManager.getConnection(reviewUrl(t), "sa", "")) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/tenant/V1__init_review_schema.sql"));
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/tenant/V3__task_origin.sql"));
                ScriptUtils.executeSqlScript(c, new ClassPathResource("extraction-silo-h2.sql"));
                c.createStatement().execute("create table document (id uuid primary key, "
                    + "uploaded_by varchar(128), status varchar(32), approved_by varchar(32), "
                    + "classification varchar(32))");
            }
        }
        for (String t : List.of(TENANT_A, TENANT_B, TENANT_C, TENANT_D)) {
            try (Connection c = DriverManager.getConnection(documentUrl(t), "sa", "")) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V1__init_document_schema.sql"));
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V2__pipeline_registry.sql"));
            }
            try (Connection c = DriverManager.getConnection(Overrides.extractionUrl(t), "sa", "")) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("extraction-silo-h2.sql"));
            }
        }
    }

    private static void grant(Connection c, String tenant, String user, String role) throws SQLException {
        c.createStatement().execute("insert into role_assignment (id, tenant_id, user_id, role) values ('"
            + UUID.randomUUID() + "', '" + tenant + "', '" + user + "', '" + role + "')");
    }

    static String reviewUrl(String tenantId) {
        return "jdbc:h2:mem:rev_" + tenantId + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    static String documentUrl(String tenantId) {
        return "jdbc:h2:mem:doc_" + tenantId + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    // ---- servicios --------------------------------------------------------------------------------------

    private Map<String, String> common(String name) {
        Map<String, String> p = new LinkedHashMap<>();
        // Sin application.yml/properties de los servicios (colisionan en el classpath comun): todo explicito.
        p.put("spring.config.name", "e2e-" + name);
        p.put("spring.application.name", name);
        p.put("server.port", "0");
        p.put("spring.main.banner-mode", "off");
        p.put("spring.kafka.bootstrap-servers", kafka.getBrokersAsString());
        p.put("spring.kafka.security.protocol", "PLAINTEXT");
        p.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        p.put("spring.kafka.consumer.enable-auto-commit", "false");
        p.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "http://localhost:1/jwks");
        p.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", Overrides.Shared.ISSUER);
        p.put("idp.security.audience", name);
        for (String m : List.of("chat", "embedding", "image", "moderation", "audio.speech", "audio.transcription")) {
            p.put("spring.ai.model." + m, "none");
        }
        p.put("management.endpoints.web.exposure.include", "health");
        p.put("idp.security.dev-mode", "true");
        return p;
    }

    private Map<String, String> documentProps() {
        Map<String, String> p = common("document-service");
        p.put("spring.flyway.enabled", "false");
        p.put("spring.kafka.consumer.group-id", "document-service");
        p.put("idp.control-db.url", CONTROL_URL);
        p.put("idp.control-db.username", "sa");
        p.put("idp.control-db.password", "");
        p.put("idp.tenant-directory.ttl", "1s");
        p.put("idp.tenant-db.jdbc-url-template", "jdbc:h2:mem:doc_{tenant};MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        p.put("idp.tenant-db.username", "sa");
        p.put("idp.tenant-db.password", "");
        p.put("idp.document.download-secret", "e2e-download-secret-that-is-at-least-32-bytes-long");
        p.put("idp.document.relay.interval", "200ms");
        p.put("services.renderer.url", "http://localhost:1");
        return p;
    }

    private Map<String, String> extractionProps() {
        Map<String, String> p = common("extraction-service");
        p.put("spring.flyway.enabled", "false");
        p.put("spring.kafka.consumer.group-id", "extraction-service");
        p.put("extraction.db.jsonb-columns", "false");
        p.put("extraction.relay.enabled", "true");
        p.put("extraction.relay.interval", "PT0.2S");
        p.put("extraction.control.url", CONTROL_URL);
        p.put("extraction.control.username", "sa");
        p.put("extraction.control.password", "");
        p.put("extraction.control.directory-ttl", "PT1S");
        return p;
    }

    private Map<String, String> auditProps() {
        Map<String, String> p = common("audit-service");
        p.put("spring.kafka.consumer.group-id", "audit-service");
        p.put("spring.datasource.url", CONTROL_URL);
        p.put("spring.datasource.username", "sa");
        p.put("spring.datasource.password", "");
        p.put("spring.flyway.enabled", "true");
        p.put("spring.flyway.table", "flyway_audit_history");
        p.put("spring.flyway.baseline-on-migrate", "true");
        p.put("spring.flyway.baseline-version", "0");
        p.put("spring.flyway.locations", "classpath:db/migration/common,classpath:db/migration/{vendor}");
        p.put("idp.audit.signing-key-id", "audit-signing");
        p.put("idp.audit.anchor.job-enabled", "false");
        p.put("idp.audit.worm.retention-days", "3650");
        return p;
    }

    private Map<String, String> qualityProps() {
        Map<String, String> p = common("quality-service");
        p.put("spring.datasource.url", QUALITY_URL);
        p.put("spring.datasource.username", "sa");
        p.put("spring.datasource.password", "");
        p.put("spring.flyway.enabled", "false");
        p.put("spring.kafka.consumer.group-id", "quality-service");
        p.put("quality.relay.enabled", "true");
        p.put("quality.relay.interval", "200ms");
        p.put("quality.scheduler.enabled", "false");
        // Solo la tipologia ZZ (del lazo ciego) entra al muestreo: los oficios EC de las demas pruebas no se tocan.
        p.put("quality.blind-sampling.rate", "0");
        p.put("quality.blind-sampling.seed", "e2e-blind-seed");
        p.put("quality.blind-sampling.rate-by-typology.ZZ", "1");
        return p;
    }

    private Map<String, String> reviewProps() {
        Map<String, String> p = common("review-service");
        p.put("spring.flyway.enabled", "false");
        p.put("spring.kafka.consumer.group-id", "review-service");
        p.put("idp.control-db.url", CONTROL_URL);
        p.put("idp.control-db.username", "sa");
        p.put("idp.control-db.password", "");
        p.put("idp.tenant-directory.ttl", "1s");
        p.put("idp.tenant-db.jdbc-url-template", "jdbc:h2:mem:rev_{tenant};MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        p.put("idp.tenant-db.username", "sa");
        p.put("idp.tenant-db.password", "");
        p.put("idp.review.public-base-url", "https://review.e2e.test");
        p.put("idp.review.crop-secret", "e2e-crop-secret-that-is-at-least-32-bytes-long");
        p.put("idp.review.relay.interval", "200ms");
        p.put("idp.review.escalation.enabled", "false");
        return p;
    }

    private int start(String name, Class<?> app, Class<?> overrides, Map<String, String> props) {
        String[] args = props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
            .toArray(String[]::new);
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(app, overrides)
            .web(WebApplicationType.SERVLET).run(args);
        contexts.add(ctx);
        String port = ctx.getEnvironment().getProperty("local.server.port");
        if (port == null) {
            throw new IllegalStateException("Sin puerto para " + name);
        }
        return Integer.parseInt(port);
    }

    private void close() {
        for (int i = contexts.size() - 1; i >= 0; i--) {
            try {
                contexts.get(i).close();
            } catch (RuntimeException ignored) {
                // cierre best-effort al terminar la JVM
            }
        }
        kafka.destroy();
    }

    // ---- utilidades de prueba ---------------------------------------------------------------------------

    String bearer(String tenant, String user) {
        return bearer(tenant, user, Overrides.Shared.ISSUER, List.of("document-service", "audit-service"));
    }

    String bearer(String tenant, String user, String issuer, List<String> audience) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(user).claim("tenant_id", tenant)
                .issuer(issuer).audience(audience).issueTime(new Date()).expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1)))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(jwtKey));
            return "Bearer " + jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Publica un evento ya serializado en el topico que la topologia asigna a su eventType (lo que haria el
     * productor de otro servicio).
     */
    void publish(String key, String json) {
        try {
            publishTo(TOPOLOGY.topicFor(JSON.readTree(json).path("eventType").asText()), key, json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Publica en un topico concreto (pruebas de suplantacion: evento por un topico que no es el de su productor). */
    void publishTo(String topic, String key, String json) {
        Properties props = new Properties();
        props.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafka.getBrokersAsString());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName());
        props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, json)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Todos los eventos publicados en los topicos de pipeline (no auditoria ni tenant), agrupados por topico. */
    List<JsonNode> events() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-observer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<Object[]> timed = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            for (String topic : TOPICS) {
                if (topic.startsWith("auditoria.") || topic.equals("idp.tenant.events")) {
                    continue;
                }
                TopicPartition tp = new TopicPartition(topic, 0);
                consumer.assign(List.of(tp));
                consumer.seekToBeginning(List.of(tp));
                long end = consumer.endOffsets(List.of(tp)).get(tp);
                while (consumer.position(tp) < end) {
                    for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                        timed.add(new Object[] {r.timestamp(), JSON.readTree(r.value())});
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        // Orden causal aproximado entre topicos: por timestamp del registro (estable dentro de cada topico).
        timed.sort(java.util.Comparator.comparingLong(o -> (Long) o[0]));
        List<JsonNode> out = new ArrayList<>();
        timed.forEach(o -> out.add((JsonNode) o[1]));
        return out;
    }
}
