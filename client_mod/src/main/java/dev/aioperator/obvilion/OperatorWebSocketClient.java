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
 * WebSocket-клиент для связи с AI Operator Core.
 * Использует стандартный java.net.http.WebSocket (встроен в Java 21).
 * Полностью независим от Minecraft классов в байткоде (через MinecraftBridge).
 */
public class OperatorWebSocketClient implements WebSocket.Listener {

    private final OperatorConfig config;
    private final Gson gson = new Gson();
    private final HttpClient httpClient;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private WebSocket webSocket;
    private final CameraController cameraController;
    private final StringBuilder messageBuffer = new StringBuilder();

    private long lastTelemetryMs = 0;
    private static final long TELEMETRY_INTERVAL_MS = 50;

    public OperatorWebSocketClient(OperatorConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.cameraController = new CameraController();
    }

    /** Подключиться к Core-серверу */
    public void connect() {
        if (connected.get() || reconnecting.get()) return;

        AiOperatorClient.LOGGER.info("[AI Operator] Подключаемся к: {}", config.getCoreUrl());
        try {
            httpClient.newWebSocketBuilder()
                    .header("X-Mod-Version", "1.0.0")
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(config.getCoreUrl()), this)
                    .thenAccept(ws -> {
                        this.webSocket = ws;
                    })
                    .exceptionally(t -> {
                        connected.set(false);
                        AiOperatorClient.LOGGER.warn("[AI Operator] ❌ Ошибка подключения: {}. Повтор через 3 сек...", t.getMessage());
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            connected.set(false);
            AiOperatorClient.LOGGER.error("[AI Operator] Ошибка создания сокета: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    // ─── WebSocket Callbacks ───────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        connected.set(true);
        reconnecting.set(false);
        AiOperatorClient.LOGGER.info("[AI Operator] ✅ Подключено к Core! Готов к работе.");

        // Приветственный пакет
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "CLIENT_HELLO");
        hello.addProperty("mod_version", "1.0.0");
        hello.addProperty("minecraft_version", "26.1.2");
        sendJson(hello);

        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            String text = messageBuffer.toString();
            messageBuffer.setLength(0);
            dispatchMessage(text);
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    private void dispatchMessage(String text) {
        try {
            JsonObject packet = gson.fromJson(text, JsonObject.class);
            if (packet == null || !packet.has("type")) return;
            String type = packet.get("type").getAsString();

            AiOperatorClient.LOGGER.debug("[AI Operator] Получена команда: {}", type);

            switch (type) {
                case "MOVE"            -> cameraController.handleMove(packet);
                case "ORBIT"           -> cameraController.handleOrbit(packet);
                case "SET_FOV"         -> cameraController.handleSetFov(packet);
                case "SET_SHADER"      -> cameraController.handleSetShader(packet);
                case "START_RECORDING" -> cameraController.startRecording();
                case "STOP_RECORDING"  -> cameraController.stopRecording();
                case "EMERGENCY_STOP"  -> cameraController.handleEmergencyStop(packet);
                default -> AiOperatorClient.LOGGER.warn("[AI Operator] Неизвестная команда: {}", type);
            }
        } catch (Exception e) {
            AiOperatorClient.LOGGER.error("[AI Operator] Ошибка разбора пакета: {}", e.getMessage());
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected.set(false);
        AiOperatorClient.LOGGER.info("[AI Operator] Соединение закрыто ({}): {}. Переподключение...", statusCode, reason);
        scheduleReconnect();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected.set(false);
        AiOperatorClient.LOGGER.warn("[AI Operator] ❌ Ошибка соединения: {}. Переподключение через 3 сек...", error.getMessage());
        scheduleReconnect();
    }

    // ─── Tick Handler ──────────────────────────────────────────────────────────

    /**
     * Вызывается каждые 50мс из AiOperatorClient фонового таймера.
     */
    public void onTick() {
        if (!MinecraftBridge.isPlayerInWorld()) return;

        // Обновляем плавное движение камеры
        cameraController.tick();

        // Отправляем телеметрию раз в 50мс
        long now = System.currentTimeMillis();
        if (connected.get() && (now - lastTelemetryMs) >= TELEMETRY_INTERVAL_MS) {
            lastTelemetryMs = now;
            sendTelemetry();
        }
    }

    // ─── Telemetry ─────────────────────────────────────────────────────────────

    private void sendTelemetry() {
        try {
            JsonObject telemetry = new JsonObject();
            telemetry.addProperty("type", "TELEMETRY");

            double[] pos = cameraController.getCurrentPosition();
            float[] rot = cameraController.getCurrentRotation();

            telemetry.addProperty("x", pos[0]);
            telemetry.addProperty("y", pos[1]);
            telemetry.addProperty("z", pos[2]);
            telemetry.addProperty("pitch", rot[0]);
            telemetry.addProperty("yaw", rot[1]);
            telemetry.addProperty("roll", 0.0f);
            telemetry.addProperty("fov", MinecraftBridge.getFov());
            telemetry.addProperty("fps", MinecraftBridge.getFps());
            telemetry.addProperty("ping", 0);
            telemetry.addProperty("is_recording", cameraController.isRecording());
            telemetry.addProperty("shader", cameraController.getCurrentShader());
            telemetry.addProperty("freecam_active", cameraController.isFreecamActive());

            sendJson(telemetry);
        } catch (Exception e) {
            AiOperatorClient.LOGGER.debug("[AI Operator] Ошибка телеметрии: {}", e.getMessage());
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private void sendJson(JsonObject obj) {
        if (webSocket != null && connected.get()) {
            webSocket.sendText(gson.toJson(obj), true);
        }
    }

    private void scheduleReconnect() {
        if (reconnecting.getAndSet(true)) return;
        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(3000);
                reconnecting.set(false);
                connect();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "ai-operator-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    public boolean isConnected() {
        return connected.get();
    }
}
