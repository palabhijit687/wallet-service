package com.paytm.pml.wallet.web;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Prometheus scrape at the plain /metrics path (the brief asks to
 * "Expose /metrics"), mirroring what Actuator serves at /actuator/prometheus.
 * Kept open (no auth) so a scraper can reach it.
 *
 * The PrometheusMeterRegistry is always present because micrometer-registry-
 * prometheus is on the classpath and Actuator auto-configures it; injecting it
 * directly is more reliable than a @ConditionalOnBean on a scanned controller.
 */
@RestController
public class MetricsAliasController {

    private final PrometheusMeterRegistry registry;

    public MetricsAliasController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public ResponseEntity<String> scrape() {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/plain; version=0.0.4; charset=utf-8"))
                .body(registry.scrape());
    }
}
