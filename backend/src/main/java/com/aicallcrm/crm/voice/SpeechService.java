package com.aicallcrm.crm.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.ObjectMapper;

/**
 * The bridge to our own speech engine (voice-engine/): speech-to-text with an open Whisper model and
 * text-to-speech with an open Piper voice. The browser never talks to the engine directly; it goes through
 * here, behind the CRM's sign-in, so the engine can stay private on the server.
 */
@Service
public class SpeechService {

    public static final int MAX_AUDIO_BYTES = 5 * 1024 * 1024;

    /** {@code warmed}: both speech models loaded and run once (the engine's /health); null if it did not say. */
    public record SpeechStatus(boolean available, String detail, String sttModel, String voice, Boolean warmed,
                               String warmUpError) {
    }

    public record Heard(String text, Integer ms) {
    }

    public record SpeakRequest(@NotBlank @Size(max = 800) String text) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final AiProperties props;
    private final ObjectMapper mapper;

    public SpeechService(AiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public SpeechStatus status() {
        try {
            HttpResponse<String> res = HTTP.send(request("/health").timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return new SpeechStatus(false, "The speech engine answered " + res.statusCode() + ".", null, null,
                        null, null);
            }
            Map<String, Object> h = mapper.readValue(res.body(), Map.class);
            return new SpeechStatus(true, "Ready", str(h.get("stt_model")), str(h.get("voice")),
                    h.get("warmed") instanceof Boolean b ? b : null, str(h.get("warm_up_error")));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new SpeechStatus(false, "The speech engine is not running at " + props.speechUrl() + ".", null, null,
                    null, null);
        }
    }

    /**
     * Does the speech engine answer at all, within 2 seconds? Asked while a phone call is connecting
     * ({@link PlivoHooksController}), so it only looks for a 200 from {@code /health}: an engine still loading its models
     * can take the call, with a slower first reply.
     */
    public boolean answers() {
        try {
            return HTTP.send(request("/health").timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** Speech to text. The audio is whatever the browser recorded (WebM/Opus, WAV...); the engine decodes it. */
    @SuppressWarnings("unchecked")
    public Heard listen(MultipartFile audio) throws IOException {
        admin();
        if (audio == null || audio.isEmpty()) {
            throw ApiException.badRequest("No audio received");
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw ApiException.badRequest("That recording is too long");
        }
        HttpResponse<String> res = send(request("/stt")
                .header("content-type", audio.getContentType() == null ? "application/octet-stream" : audio.getContentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(audio.getBytes())).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = mapper.readValue(res.body(), Map.class);
        return new Heard(str(body.get("text")) == null ? "" : str(body.get("text")).trim(),
                body.get("ms") instanceof Number n ? n.intValue() : null);
    }

    /** Text to speech, returned as WAV audio. */
    public byte[] speak(SpeakRequest req) {
        admin();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", req.text().trim());
        return send(request("/tts").header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.speechUrl() + path)).timeout(Duration.ofSeconds(60));
        if (props.speechToken() != null && !props.speechToken().isBlank()) {
            b.header("x-speech-token", props.speechToken());
        }
        return b;
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
        try {
            HttpResponse<T> res = HTTP.send(req, handler);
            if (res.statusCode() / 100 != 2) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The speech engine could not handle that (" + res.statusCode() + ")");
            }
            return res;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The speech engine is not running. Start voice-engine first.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static void admin() {
        if (!CurrentUser.get().isAdmin()) {
            throw ApiException.forbidden("Only the admin can use the test console");
        }
    }

    @RestController
    static class SpeechController {

        private final SpeechService speech;
        private final Brains brains;

        SpeechController(SpeechService speech, Brains brains) {
            this.speech = speech;
            this.brains = brains;
        }

        /** What is ready right now: the speech engine and each brain. */
        @GetMapping("/api/voice/ai/status")
        public Map<String, Object> status() {
            admin();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("speech", speech.status());
            m.put("brains", brains.options());
            m.put("defaultBrain", brains.defaultKind());
            return m;
        }

        @PostMapping("/api/voice/speech/stt")
        public Heard listen(@RequestParam("audio") MultipartFile audio) throws IOException {
            return speech.listen(audio);
        }

        @PostMapping(value = "/api/voice/speech/tts", produces = "audio/wav")
        public ResponseEntity<byte[]> speak(@Valid @RequestBody SpeakRequest req) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(speech.speak(req));
        }
    }
}
