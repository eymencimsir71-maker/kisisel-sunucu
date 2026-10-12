
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Kişisel asistan sunucusu. API anahtarları ortam değişkenlerinde durur, uygulamaya gitmez.
 * Dil modellerini sırayla dener: bir sağlayıcı hata verirse 5 dakika devre dışı kalır.
 */
public class Main {

    static final String APP_TOKEN = env("APP_TOKEN", "");
    static final String TAVILY_KEY = env("TAVILY_API_KEY", "");
    static final String ELEVEN_KEY = env("ELEVENLABS_API_KEY", "");
    static final String ELEVEN_VOICE = env("ELEVENLABS_VOICE_ID", "");
    static final long COOLDOWN_MS = 300_000L;
    static final String SYSTEM_PROMPT =
            "Sen Türkçe konuşan, kısa ve net cevap veren kişisel bir asistansın. Cevapların en fazla 3 cümle olsun.";

    /** Sıra önemli: ilk kullanılabilen sağlayıcı kullanılır. Alanlar: ad, temel adres, anahtar adı, model. */
    static final String[][] PROVIDERS = {
            {"groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY", "llama-3.3-70b-versatile"},
            {"gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "GEMINI_API_KEY", "gemini-2.0-flash"},
            {"cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY", "llama3.1-8b"},
            {"mistral", "https://api.mistral.ai/v1", "MISTRAL_API_KEY", "mistral-small-latest"},
            {"openrouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "meta-llama/llama-3.3-70b-instruct:free"},
            {"nvidia", "https://integrate.api.nvidia.com/v1", "NVIDIA_API_KEY", "meta/llama-3.1-8b-instruct"},
            {"openai", "https://api.openai.com/v1", "OPENAI_API_KEY", "gpt-4o-mini"},
            {"perplexity", "https://api.perplexity.ai", "PERPLEXITY_API_KEY", "sonar"}
    };

    static final Map<String, Long> cooldown = new ConcurrentHashMap<>();
    static final Gson gson = new Gson();
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", Main::health);
        server.createContext("/chat", Main::chat);
        server.createContext("/tts", Main::tts);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Sunucu çalışıyor, port " + port);
    }

    // ---------- Uç noktalar ----------

    static void health(HttpExchange ex) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonArray aktif = new JsonArray();
        for (String[] p : PROVIDERS) {
            if (isAvailable(p)) aktif.add(p[0]);
        }
        out.add("aktif", aktif);
        out.addProperty("arama_tavily", !TAVILY_KEY.isEmpty());
        out.addProperty("ses_elevenlabs", !ELEVEN_KEY.isEmpty() && !ELEVEN_VOICE.isEmpty());
        sendJson(ex, 200, out);
    }

    static void chat(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, error("Yöntem desteklenmiyor"));
            return;
        }
        if (!authorized(ex)) {
            sendJson(ex, 401, error("Yetkisiz"));
            return;
        }
        JsonObject body;
        try {
            body = gson.fromJson(readBody(ex), JsonObject.class);
        } catch (Exception e) {
            sendJson(ex, 400, error("İstek okunamadı"));
            return;
        }
        if (body == null || !body.has("message")) {
            sendJson(ex, 400, error("Mesaj eksik"));
            return;
        }

        String message = body.get("message").getAsString();
        boolean web = body.has("web") && body.get("web").getAsBoolean();

        String userText = message;
        if (web && !TAVILY_KEY.isEmpty()) {
            try {
                userText += "\n\nWeb sonuçları:\n" + tavilySearch(message);
            } catch (Exception ignored) {
                // Web araması çalışmazsa sadece modelle cevap verilir.
            }
        }

        JsonArray messages = new JsonArray();
        messages.add(msg("system", SYSTEM_PROMPT));
        if (body.has("history") && body.get("history").isJsonArray()) {
            JsonArray hist = body.getAsJsonArray("history");
            for (int i = Math.max(0, hist.size() - 10); i < hist.size(); i++) {
                messages.add(hist.get(i));
            }
        }
        messages.add(msg("user", userText));

        for (String[] p : PROVIDERS) {
            if (!isAvailable(p)) continue;
            try {
                String text = askProvider(p, messages);
                JsonObject out = new JsonObject();
                out.addProperty("reply", text);
                out.addProperty("provider", p[0]);
                sendJson(ex, 200, out);
                return;
            } catch (Exception e) {
                cooldown.put(p[0], System.currentTimeMillis() + COOLDOWN_MS);
            }
        }
        sendJson(ex, 503, error("Şu an hiçbir model yanıt vermiyor"));
    }

    static void tts(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, error("Yöntem desteklenmiyor"));
            return;
        }
        if (!authorized(ex)) {
            sendJson(ex, 401, error("Yetkisiz"));
            return;
        }
        if (ELEVEN_KEY.isEmpty() || ELEVEN_VOICE.isEmpty()) {
            sendJson(ex, 503, error("Ses servisi ayarlı değil"));
            return;
        }
        JsonObject body;
        try {
            body = gson.fromJson(readBody(ex), JsonObject.class);
        } catch (Exception e) {
            sendJson(ex, 400, error("İstek okunamadı"));
            return;
        }
        if (body == null || !body.has("text")) {
            sendJson(ex, 400, error("Metin eksik"));
            return;
        }
        String text = body.get("text").getAsString();
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }

        JsonObject req = new JsonObject();
        req.addProperty("text", text);
        req.addProperty("model_id", "eleven_multilingual_v2");
        HttpRequest r = HttpRequest.newBuilder(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + ELEVEN_VOICE))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("xi-api-key", ELEVEN_KEY)
                .header("Accept", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<byte[]> res = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                sendJson(ex, 502, error("Ses üretilemedi"));
                return;
            }
            send(ex, 200, "audio/mpeg", res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ex, 500, error("İşlem iptal edildi"));
        }
    }

    // ---------- Dil modelleri ve arama ----------

    static String askProvider(String[] p, JsonArray messages) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("model", p[3]);
        req.add("messages", messages);
        req.addProperty("max_tokens", 300);
        HttpRequest r = HttpRequest.newBuilder(URI.create(p[1] + "/chat/completions"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + env(p[2], ""))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new IOException(p[0] + " HTTP " + res.statusCode());
        }
        JsonObject json = gson.fromJson(res.body(), JsonObject.class);
        return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString().trim();
    }

    static String tavilySearch(String query) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("api_key", TAVILY_KEY);
        req.addProperty("query", query);
        req.addProperty("max_results", 3);
        HttpRequest r = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new IOException("Tavily HTTP " + res.statusCode());
        }
        JsonArray items = gson.fromJson(res.body(), JsonObject.class).getAsJsonArray("results");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            String content = item.get("content").getAsString();
            sb.append(item.get("title").getAsString()).append(": ")
                    .append(content.length() > 300 ? content.substring(0, 300) : content)
                    .append('\n');
        }
        return sb.toString();
    }

    // ---------- Yardımcılar ----------

    static boolean isAvailable(String[] p) {
        String key = env(p[2], "");
        Long until = cooldown.get(p[0]);
        return !key.isEmpty() && (until == null || until < System.currentTimeMillis());
    }

    static boolean authorized(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst("X-App-Token");
        return !APP_TOKEN.isEmpty() && APP_TOKEN.equals(token);
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    static JsonObject error(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("hata", text);
        return o;
    }

    static void sendJson(HttpExchange ex, int code, JsonObject obj) throws IOException {
        send(ex, code, "application/json; charset=utf-8", gson.toJson(obj).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
