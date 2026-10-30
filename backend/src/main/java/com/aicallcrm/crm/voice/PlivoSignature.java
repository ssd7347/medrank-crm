package com.aicallcrm.crm.voice;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Plivo's request signature, version 3 ({@code X-Plivo-Signature-V3} with {@code X-Plivo-Signature-V3-Nonce}),
 * built exactly as Plivo's own SDK does (plivo-python {@code utils/signature_v3.py}): the callback URL without its
 * query, then its query sorted as {@code k=v&...}; for a POST with form fields a {@code ?} (and a {@code .} when
 * there was a query) and the fields sorted by name as {@code namevalue...}; then {@code .nonce}; HMAC-SHA256 with
 * the auth token, Base64. The header may carry several signatures separated by commas.
 */
final class PlivoSignature {

    private PlivoSignature() {
    }

    static boolean valid(String authToken, String method, String url, Map<String, List<String>> form, String nonce,
                         String header) {
        if (authToken == null || authToken.isBlank() || nonce == null || header == null) {
            return false;
        }
        byte[] expected = sign(authToken, base(method, url, form), nonce).getBytes(StandardCharsets.UTF_8);
        for (String candidate : header.split(",")) {
            if (MessageDigest.isEqual(expected, candidate.trim().getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /** The string that is signed, before {@code .nonce}. */
    static String base(String method, String url, Map<String, List<String>> form) {
        URI uri = URI.create(url);
        String bare = uri.getScheme() + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
        String query = sortedQuery(parseQuery(uri.getRawQuery()));
        if ("GET".equalsIgnoreCase(method)) {
            // A GET signs its query; Plivo sends no form fields with one.
            return query.isEmpty() ? bare : bare + "?" + query;
        }
        boolean noFields = form == null || form.isEmpty();
        StringBuilder out = new StringBuilder(bare);
        if (!query.isEmpty() || !noFields) {
            out.append('?').append(query);
        }
        if (!query.isEmpty() && !noFields) {
            out.append('.');
        }
        if (!noFields) {
            for (Map.Entry<String, List<String>> e : new TreeMap<>(form).entrySet()) {
                List<String> values = new ArrayList<>(e.getValue());
                values.sort(null);
                values.forEach(v -> out.append(e.getKey()).append(v));
            }
        }
        return out.toString();
    }

    static String sign(String authToken, String base, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((base + "." + nonce).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decoded, as Python's parse_qs gives them, keeping blank values. */
    static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> out = new TreeMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }

    private static String sortedQuery(Map<String, List<String>> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : new TreeMap<>(params).entrySet()) {
            List<String> values = new ArrayList<>(e.getValue());
            values.sort(null);
            values.forEach(v -> parts.add(e.getKey() + "=" + v));
        }
        return String.join("&", parts);
    }
}
