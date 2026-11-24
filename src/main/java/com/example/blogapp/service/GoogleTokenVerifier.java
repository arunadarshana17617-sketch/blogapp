package com.example.blogapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GoogleTokenVerifier {

    private static final String CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final JsonParser json = JsonParserFactory.getJsonParser();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();
    private volatile Instant keysFetchedAt = Instant.EPOCH;

    private final String clientId;

    public GoogleTokenVerifier(@Value("${google.client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public record GoogleUser(String sub, String email, boolean emailVerified, String name, String picture) {}

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientId.startsWith("YOUR_");
    }

    public String getClientId() {
        return clientId;
    }

    public GoogleUser verify(String idToken) throws GeneralSecurityException {
        if (!isConfigured()) throw new GeneralSecurityException("Google login is not configured");
        if (idToken == null) throw new GeneralSecurityException("Missing token");

        String[] parts = idToken.split("\\.");
        if (parts.length != 3) throw new GeneralSecurityException("Malformed token");

        Map<String, Object> header = decodeJson(parts[0]);
        Map<String, Object> payload = decodeJson(parts[1]);

        if (!"RS256".equals(str(header, "alg"))) {
            throw new GeneralSecurityException("Unsupported algorithm");
        }

        PublicKey key = getKey(str(header, "kid"));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        boolean ok;
        try {
            ok = sig.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Malformed signature");
        }
        if (!ok) throw new GeneralSecurityException("Bad signature");

        if (!ISSUERS.contains(str(payload, "iss"))) {
            throw new GeneralSecurityException("Wrong issuer");
        }
        if (!clientId.equals(str(payload, "aud"))) {
            throw new GeneralSecurityException("Token was not issued for this app");
        }
        if (num(payload, "exp") <= Instant.now().getEpochSecond()) {
            throw new GeneralSecurityException("Token expired");
        }

        String sub = str(payload, "sub");
        String email = str(payload, "email");
        if (sub.isBlank() || email.isBlank()) throw new GeneralSecurityException("Missing claims");

        Object ev = payload.get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(ev) || "true".equals(String.valueOf(ev));

        return new GoogleUser(sub, email.toLowerCase(), emailVerified,
                str(payload, "name"), str(payload, "picture"));
    }

    private static String str(Map<?, ?> m, String key) {
        Object o = m.get(key);
        return o == null ? "" : o.toString();
    }

    private static long num(Map<?, ?> m, String key) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.longValue();
        try {
            return o == null ? 0 : (long) Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, Object> decodeJson(String part) throws GeneralSecurityException {
        try {
            return json.parseMap(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("Malformed token");
        }
    }

    private PublicKey getKey(String kid) throws GeneralSecurityException {
        PublicKey key = keys.get(kid);
        boolean stale = Duration.between(keysFetchedAt, Instant.now()).toHours() >= 1;
        boolean canRetry = Duration.between(keysFetchedAt, Instant.now()).toSeconds() >= 60;
        if (key == null || stale) {
            if (stale || canRetry) refreshKeys();
            key = keys.get(kid);
        }
        if (key == null) throw new GeneralSecurityException("Unknown signing key");
        return key;
    }

    private synchronized void refreshKeys() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(CERTS_URL))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("Google certs HTTP " + res.statusCode());

            Map<String, PublicKey> fresh = new ConcurrentHashMap<>();
            KeyFactory kf = KeyFactory.getInstance("RSA");
            Object list = json.parseMap(res.body()).get("keys");
            if (list instanceof List<?> items) {
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> k)) continue;
                    if (!"RSA".equals(str(k, "kty"))) continue;
                    BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(str(k, "n")));
                    BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(str(k, "e")));
                    fresh.put(str(k, "kid"), kf.generatePublic(new RSAPublicKeySpec(n, e)));
                }
            }
            keys.clear();
            keys.putAll(fresh);
            keysFetchedAt = Instant.now();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching Google keys", ie);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Could not fetch Google keys", e);
        }
    }
}