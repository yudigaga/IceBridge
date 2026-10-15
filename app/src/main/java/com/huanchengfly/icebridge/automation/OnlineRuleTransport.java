package com.huanchengfly.icebridge.automation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Bounded HTTPS transport shared without app Context, providers, databases or app executors. */
final class OnlineRuleTransport {
    static final HttpUrl MANIFEST_URL = HttpUrl.parse(
            "https://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json");
    static final HttpUrl FALLBACK_MANIFEST_URL = HttpUrl.parse(
            "https://cdn.jsdelivr.net/gh/yudigaga/IceBridge@rules/manifest.json");
    static final long REFRESH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    static final long PRIMARY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(18);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    String fetch(HttpUrl url, int maxBytes, long deadline) throws IOException {
        if (url == null || !"https".equals(url.scheme()) || maxBytes <= 0) {
            throw new IOException("request-invalid");
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("refresh-deadline");
        Request request = new Request.Builder().url(url).header("User-Agent", "IceBridge-online-rules/1")
                .header("Cache-Control", "no-cache").build();
        Call call = client.newCall(request);
        // Manifest, bundle, and fallback all consume the caller's one task deadline.
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("http-" + response.code());
            if (response.body().contentLength() > maxBytes) throw new IOException("response-too-large");
            try (InputStream input = response.body().byteStream()) {
                return readUtf8Bounded(input, maxBytes);
            }
        }
    }

    /** Only a validated relative JSON path beneath the manifest directory may be resolved. */
    static HttpUrl bundleUrl(HttpUrl manifestUrl, String path) throws IOException {
        if (manifestUrl == null || !"https".equals(manifestUrl.scheme()) || path == null
                || path.startsWith("/") || path.contains("..") || path.contains("\\")
                || path.contains(":") || path.contains("%") || path.contains("?") || path.contains("#")) {
            throw new IOException("bundle-url-invalid");
        }
        HttpUrl base = manifestUrl.resolve(".");
        HttpUrl result = base == null ? null : base.resolve(path);
        if (result == null || !result.scheme().equals(manifestUrl.scheme())
                || !result.host().equals(manifestUrl.host()) || result.port() != manifestUrl.port()
                || !result.encodedPath().startsWith(base.encodedPath())) throw new IOException("bundle-origin");
        return result;
    }

    static String readUtf8Bounded(InputStream input, int maxBytes) throws IOException {
        if (input == null) throw new IOException("response-body-invalid");
        if (maxBytes <= 0) throw new IOException("response-limit-invalid");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) throw new IOException("response-too-large");
            output.write(buffer, 0, read);
            total += read;
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }
}
