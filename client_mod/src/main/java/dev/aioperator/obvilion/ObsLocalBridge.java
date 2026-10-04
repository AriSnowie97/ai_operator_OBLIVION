package dev.aioperator.obvilion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ObsLocalBridge — связывает клиент Minecraft с локальным OBS Studio на том же ПК.
 * Позволяет удаленному серверу (Railway) управлять записью в локальном OBS через WebSocket мода!
 */
public class ObsLocalBridge implements WebSocket.Listener {

    private static final ObsLocalBridge INSTANCE = new ObsLocalBridge();
    private static final String OBS_URL = "ws://localhost:4455";

    private final Gson gson = new Gson();
    private final HttpClient httpClient;
    private WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final StringBuilder messageBuffer = new StringBuilder();

    private ObsLocalBridge() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public static ObsLocalBridge getInstance() {
        return INSTANCE;
    }

    public void start() {
        connect();
    }

    public void connect() {
        if (connected.get()) return;
        try {
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create(OBS_URL), this)
                    .thenAccept(ws -> {
                        this.webSocket = ws;
                    })
                    .exceptionally(t -> {
                        connected.set(false);
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            scheduleReconnect();
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            String fullMessage = messageBuffer.toString();
            messageBuffer.setLength(0);
            handleObsMessage(fullMessage);
        }
        webSocket.request(1);
        return null;
    }

    private void handleObsMessage(String raw) {
        try {
            JsonObject json = gson.fromJson(raw, JsonObject.class);
            if (!json.has("op")) return;
            int op = json.get("op").getAsInt();

            if (op == 0) { // Hello from OBS WebSocket v5
                JsonObject d = new JsonObject();
                d.addProperty("rpcVersion", 1);
                d.addProperty("eventSubscriptions", 33); // General + Outputs

                JsonObject identify = new JsonObject();
                identify.addProperty("op", 1);
                identify.add("d", d);
                sendJson(identify, true);

            } else if (op == 2) { // Identified successfully
                connected.set(true);
                AiOperatorClient.LOGGER.info("[OBS Bridge] ✅ Успешно подключено к OBS Studio на порту 4455!");

                // Query initial status
                JsonObject req = new JsonObject();
                req.addProperty("op", 6);
                JsonObject d = new JsonObject();
                d.addProperty("requestType", "GetRecordStatus");
                d.addProperty("requestId", "init_rec_status");
                req.add("d", d);
                sendJson(req, false);

            } else if (op == 5) { // Event
                JsonObject d = json.getAsJsonObject("d");
                if (d != null && d.has("eventType") && "RecordStateChanged".equals(d.get("eventType").getAsString())) {
                    JsonObject evtData = d.getAsJsonObject("eventData");
                    if (evtData != null && evtData.has("outputActive")) {
                        recording.set(evtData.get("outputActive").getAsBoolean());
                    }
                }
            } else if (op == 7) { // RequestResponse
                JsonObject d = json.getAsJsonObject("d");
                if (d != null && d.has("requestId") && "init_rec_status".equals(d.get("requestId").getAsString())) {
                    JsonObject respData = d.getAsJsonObject("responseData");
                    if (respData != null && respData.has("outputActive")) {
                        recording.set(respData.get("outputActive").getAsBoolean());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public void startRecording() {
        if (!connected.get()) return;
        JsonObject req = new JsonObject();
        req.addProperty("op", 6);
        JsonObject d = new JsonObject();
        d.addProperty("requestType", "StartRecord");
        d.addProperty("requestId", "req_start_" + System.currentTimeMillis());
        req.add("d", d);
        sendJson(req, false);
        recording.set(true);
        AiOperatorClient.LOGGER.info("[OBS Bridge] 🔴 Отправлен запрос StartRecord в OBS Studio!");
    }

    public void stopRecording() {
        if (!connected.get()) return;
        JsonObject req = new JsonObject();
        req.addProperty("op", 6);
        JsonObject d = new JsonObject();
        d.addProperty("requestType", "StopRecord");
        d.addProperty("requestId", "req_stop_" + System.currentTimeMillis());
        req.add("d", d);
        sendJson(req, false);
        recording.set(false);
        AiOperatorClient.LOGGER.info("[OBS Bridge] ⏹ Отправлен запрос StopRecord в OBS Studio!");
    }

    private void sendJson(JsonObject obj, boolean force) {
        if (webSocket != null && (connected.get() || force)) {
            webSocket.sendText(gson.toJson(obj), true);
        }
    }

    private void scheduleReconnect() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
                connect();
            } catch (InterruptedException ignored) {}
        }, "obs-bridge-reconnect");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected.set(false);
        scheduleReconnect();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected.set(false);
        scheduleReconnect();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isRecording() {
        return recording.get();
    }
}
