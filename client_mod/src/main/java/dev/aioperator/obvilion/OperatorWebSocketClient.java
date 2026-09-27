package dev.aioperator.obvilion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket-клиент для связи с AI Operator Core.
 * Протокол описан в PROTOCOL.md
 *
 * Возможности:
 *  - Автоматическое переподключение каждые 3 секунды при разрыве
 *  - Обработка команд: MOVE, ORBIT, SET_FOV, SET_SHADER, EMERGENCY_STOP
 *  - Отправка телеметрии 20 раз в секунду (каждый тик)
 */
public class OperatorWebSocketClient extends WebSocketListener {

    private final OperatorConfig config;
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private WebSocket webSocket;
    private CameraController cameraController;

    // Телеметрия отправляется раз в тик (20/сек), но не чаще раза в 50мс
    private long lastTelemetryMs = 0;
    private static final long TELEMETRY_INTERVAL_MS = 50;

    public OperatorWebSocketClient(OperatorConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)   // Бесконечный таймаут для WS
                .connectTimeout(5, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)      // Keep-alive пинги
                .build();
        this.cameraController = new CameraController();
    }

    /** Подключиться к Core-серверу */
    public void connect() {
        if (connected.get() || reconnecting.get()) return;

        AiOperatorClient.LOGGER.info("[AI Operator] Подключаемся к: {}", config.getCoreUrl());
        Request request = new Request.Builder()
                .url(config.getCoreUrl())
                .addHeader("X-Mod-Version", "1.0.0")
                .build();
        webSocket = httpClient.newWebSocket(request, this);
    }

    // ─── WebSocket Callbacks ───────────────────────────────────────────────────

    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        this.webSocket = webSocket;
        connected.set(true);
        reconnecting.set(false);
        AiOperatorClient.LOGGER.info("[AI Operator] ✅ Подключено к Core! Готов к работе.");

        // Отправляем приветственный пакет
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "CLIENT_HELLO");
        hello.addProperty("mod_version", "1.0.0");
        hello.addProperty("minecraft_version", "1.21.4");
        sendJson(hello);
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        try {
            JsonObject packet = gson.fromJson(text, JsonObject.class);
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
    public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
        connected.set(false);
        AiOperatorClient.LOGGER.warn("[AI Operator] ❌ Соединение разорвано: {}. Переподключение через 3 сек...", t.getMessage());
        scheduleReconnect();
    }

    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        connected.set(false);
        AiOperatorClient.LOGGER.info("[AI Operator] Соединение закрыто ({}): {}. Переподключение...", code, reason);
        scheduleReconnect();
    }

    // ─── Tick Handler ──────────────────────────────────────────────────────────

    /**
     * Вызывается каждый тик клиента (20 раз в секунду).
     * Обновляет плавное движение камеры и отправляет телеметрию.
     */
    public void onTick(MinecraftClient client) {
        if (client.player == null) return;

        // Обновляем плавное движение камеры
        cameraController.tick(client);

        // Отправляем телеметрию раз в 50мс
        long now = System.currentTimeMillis();
        if (connected.get() && (now - lastTelemetryMs) >= TELEMETRY_INTERVAL_MS) {
            lastTelemetryMs = now;
            sendTelemetry(client);
        }
    }

    // ─── Telemetry ─────────────────────────────────────────────────────────────

    private void sendTelemetry(MinecraftClient client) {
        try {
            JsonObject telemetry = new JsonObject();
            telemetry.addProperty("type", "TELEMETRY");

            // Позиция и ориентация из FreeCam если активна, иначе игрок
            double[] pos = cameraController.getCurrentPosition(client);
            float[] rot = cameraController.getCurrentRotation(client);

            telemetry.addProperty("x", pos[0]);
            telemetry.addProperty("y", pos[1]);
            telemetry.addProperty("z", pos[2]);
            telemetry.addProperty("pitch", rot[0]);
            telemetry.addProperty("yaw", rot[1]);
            telemetry.addProperty("roll", 0.0f);
            telemetry.addProperty("fov", client.options.getFov().getValue());
            telemetry.addProperty("fps", client.getCurrentFps());
            telemetry.addProperty("ping", client.player.networkHandler != null ? 0 : -1);
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
            webSocket.send(gson.toJson(obj));
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
