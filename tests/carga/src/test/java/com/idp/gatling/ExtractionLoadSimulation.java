package com.idp.gatling;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

public class ExtractionLoadSimulation extends Simulation {

    HttpProtocolBuilder httpProtocol = http
        .baseUrl(System.getProperty("baseUrl", "http://localhost:8080"))
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")
        .header("X-Tenant-ID", "tenant-gatling-load");

    ScenarioBuilder scn = scenario("Extraccion de Oficios - Load Test")
        .exec(http("Submit Extraction")
            .post("/api/v1/extractions")
            .body(StringBody("{ \"documentId\": \"doc-#{randomUuid()}\", \"typology\": \"embargo\" }"))
            .check(status().is(202))
        );

    {
        setUp(
            scn.injectOpen(
                rampUsersPerSec(10).to(100).during(Duration.ofSeconds(30)),
                constantUsersPerSec(100).during(Duration.ofMinutes(2))
            )
        ).protocols(httpProtocol)
         .assertions(
             global().responseTime().max().lt(500),
             global().successfulRequests().percent().gt(99.0)
         );
    }
}
