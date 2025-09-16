package com.trade.frankenstein.trader.ui.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class ApiClient {

    private static final RestTemplate RT = new RestTemplate();
    private static final ObjectMapper M = new ObjectMapper();

    private ApiClient() {
    }

    public static <T> T get(String url, Class<T> type) {
        try {
            ResponseEntity<T> r = RT.getForEntity(abs(url), type);
            return r.getBody();
        } catch (HttpStatusCodeException ex) {
            throw new RuntimeException(buildMessage("GET", url, ex));
        } catch (Throwable t) {
            throw new RuntimeException("GET " + url + " failed: " + t.getMessage(), t);
        }
    }

    public static <T> T get(String url, TypeReference<T> typeRef) {
        try {
            ResponseEntity<String> r = RT.getForEntity(abs(url), String.class);
            if (r.getBody() == null) return null;
            return M.readValue(r.getBody(), typeRef);
        } catch (HttpStatusCodeException ex) {
            throw new RuntimeException(buildMessage("GET", url, ex));
        } catch (Throwable t) {
            throw new RuntimeException("GET " + url + " failed: " + t.getMessage(), t);
        }
    }

    public static <T> T post(String url, Object body, Class<T> type) {
        HttpEntity<?> req;
        if (body == null) {
            req = new HttpEntity<>(new byte[0]); // no Content-Type header
        } else {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            req = new HttpEntity<>(body, h);
        }
        ResponseEntity<T> r = RT.exchange(abs(url), HttpMethod.POST, req, type);
        return r.getBody();
    }

    private static String abs(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        try {
            String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            return base + (u.startsWith("/") ? u : "/" + u);
        } catch (Throwable ignore) {
            String port = System.getProperty("server.port", "8080");
            String base = "http://localhost:" + port;
            return base + (u.startsWith("/") ? u : "/" + u);
        }
    }

    public static Map<String, Object> getMap(String url) {
        return get(url, new TypeReference<Map<String, Object>>() {
        });
    }

    public static List<Map<String, Object>> getListOfMap(String url) {
        return get(url, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    public static ObjectMapper json() {
        return M;
    }

    private static String buildMessage(String method, String url, HttpStatusCodeException ex) {
        String body = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
        return method + " " + url + " returned " + ex.getRawStatusCode() + " (" + ex.getStatusText() + "): " + body;
    }

    public static JsonNode readTree(String json) {
        try {
            return M.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
