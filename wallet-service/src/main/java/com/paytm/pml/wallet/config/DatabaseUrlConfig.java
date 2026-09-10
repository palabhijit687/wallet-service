package com.paytm.pml.wallet.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Railway (and Heroku/Render) expose Postgres as a single DATABASE_URL in the
 * libpq form: postgresql://user:pass@host:port/db. Spring's spring.datasource.url
 * needs a JDBC URL plus separate username/password. This post-processor detects
 * DATABASE_URL and translates it, so on Railway you only need to set
 * DATABASE_URL (or reference the Postgres plugin's variable) with nothing else.
 *
 * If DATABASE_URL is absent, the existing DB_URL/DB_USER/DB_PASSWORD env vars
 * (and their local docker-compose defaults) are used unchanged.
 *
 * Registered via META-INF/spring.factories so it runs before the datasource
 * is created.
 */
public class DatabaseUrlConfig implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()
                || databaseUrl.startsWith("jdbc:")) {
            return; // nothing to translate
        }
        try {
            URI uri = URI.create(databaseUrl);
            String userInfo = uri.getUserInfo();      // "user:pass" (may be null)
            String user = null;
            String password = null;
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                user = parts[0];
                password = parts.length > 1 ? parts[1] : "";
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String query = uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "";
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + query;

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbcUrl);
            if (user != null) {
                props.put("spring.datasource.username", user);
                props.put("spring.datasource.password", password);
            }
            // Highest precedence so it overrides the DB_URL-based defaults.
            env.getPropertySources().addFirst(
                    new MapPropertySource("railwayDatabaseUrl", props));
        } catch (RuntimeException ex) {
            // Leave the fallback config in place if the URL can't be parsed.
            System.err.println("Could not parse DATABASE_URL, using DB_URL fallback: " + ex.getMessage());
        }
    }
}
