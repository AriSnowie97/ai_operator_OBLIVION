package dev.aioperator.obvilion;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * CameraController — обрабатывает все команды движения камеры.
 *
 * Использует Freecam API (если мод установлен) для плавного перемещения.
 * Если Freecam не установлен — работает через стандартный spectator режим.
 *
 * Поддерживаемые команды (из PROTOCOL.md):
 *  - MOVE: плавное перемещение в точку за duration секунд
 *  - ORBIT: вращение вокруг центральной точки
 *  - SET_FOV: изменение угла обзора
 *  - SET_SHADER: смена шейдерпака через Iris API
 *  - EMERGENCY_STOP: мгновенная остановка в безопасную позицию
 */
public class CameraController {

    // ─── Состояние ─────────────────────────────────────────────────────────────
    private boolean freecamActive = false;
    private boolean isRecording = false;
    private String currentShader = "none";

    // MOVE интерполяция
    private boolean isMoving = false;
    private double startX, startY, startZ;
    private double targetX, targetY, targetZ;
    private float startPitch, startYaw;
    private float targetPitch, targetYaw;
    private float moveDurationTicks;
    private float moveElapsedTicks;
    private String smoothing = "cinematic";

    // ORBIT состояние
    private boolean isOrbiting = false;
    private double orbitCenterX, orbitCenterY, orbitCenterZ;
    private double orbitRadius;
    private double orbitSpeed;    // градусы в секунду
    private double orbitHeight;
    private double orbitAngle = 0.0;

    // Текущая позиция и ориентация (в spectator / freecam пространстве)
    private double currentX, currentY, currentZ;
    private float currentPitch, currentYaw;

    // ─── Команды ───────────────────────────────────────────────────────────────

    public void handleMove(JsonObject packet) {
        targetX = packet.get("x").getAsDouble();
        targetY = packet.get("y").getAsDouble();
        targetZ = packet.get("z").getAsDouble();
        targetPitch = packet.has("pitch") ? packet.get("pitch").getAsFloat() : currentPitch;
        targetYaw   = packet.has("yaw")   ? packet.get("yaw").getAsFloat()   : currentYaw;
        float duration = packet.has("duration") ? packet.get("duration").getAsFloat() : 2.0f;
        smoothing = packet.has("smoothing") ? packet.get("smoothing").getAsString() : "cinematic";

        startX = currentX; startY = currentY; startZ = currentZ;
        startPitch = currentPitch; startYaw = currentYaw;

        moveDurationTicks = duration * 20f;   // секунды → тики
        moveElapsedTicks  = 0f;
        isMoving = true;
        isOrbiting = false;   // Прерываем орбиту если была

        AiOperatorClient.LOGGER.info("[Camera] MOVE → ({}, {}, {}) за {} сек", targetX, targetY, targetZ, duration);
    }

    public void handleOrbit(JsonObject packet) {
        JsonObject center = packet.getAsJsonObject("center");
        if (center == null && packet.has("center")) {
            // center может быть массивом [x, y, z]
            var arr = packet.getAsJsonArray("center");
            orbitCenterX = arr.get(0).getAsDouble();
            orbitCenterY = arr.get(1).getAsDouble();
            orbitCenterZ = arr.get(2).getAsDouble();
        } else if (center != null) {
            orbitCenterX = center.get("x").getAsDouble();
            orbitCenterY = center.get("y").getAsDouble();
            orbitCenterZ = center.get("z").getAsDouble();
        }
        orbitRadius = packet.has("radius") ? packet.get("radius").getAsDouble() : 20.0;
        orbitSpeed  = packet.has("speed")  ? packet.get("speed").getAsDouble()  : 15.0;
        orbitHeight = packet.has("height") ? packet.get("height").getAsDouble() : 5.0;

        // Вычисляем начальный угол из текущей позиции
        orbitAngle = Math.toDegrees(Math.atan2(currentZ - orbitCenterZ, currentX - orbitCenterX));
        isOrbiting = true;
        isMoving = false;

        AiOperatorClient.LOGGER.info("[Camera] ORBIT вокруг ({}, {}, {}) R={} speed={}°/s",
                orbitCenterX, orbitCenterY, orbitCenterZ, orbitRadius, orbitSpeed);
    }

    public void handleSetFov(JsonObject packet) {
        float fov = packet.get("fov").getAsFloat();
        // Применяем FOV через Minecraft options (thread-safe через schedule)
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                client.options.getFov().setValue((int) fov);
                AiOperatorClient.LOGGER.info("[Camera] SET_FOV → {}°", fov);
            }
        });
    }

    public void handleSetShader(JsonObject packet) {
        String shaderpack = packet.has("shaderpack") ? packet.get("shaderpack").getAsString() : "none";
        String profile    = packet.has("profile")    ? packet.get("profile").getAsString()    : "Medium";

        // Iris API — вызываем через reflection чтобы не требовать Iris как зависимость
        MinecraftClient.getInstance().execute(() -> {
            try {
                Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = irisApi.getMethod("getInstance").invoke(null);
                if ("none".equalsIgnoreCase(shaderpack)) {
                    irisApi.getMethod("setShaderpackDisabled").invoke(instance);
                } else {
                    irisApi.getMethod("setCurrentShaderpack", String.class).invoke(instance, shaderpack);
                }
                currentShader = shaderpack;
                AiOperatorClient.LOGGER.info("[Camera] SET_SHADER → {} ({})", shaderpack, profile);
            } catch (ClassNotFoundException e) {
                AiOperatorClient.LOGGER.warn("[Camera] Iris не установлен! Смена шейдеров недоступна.");
            } catch (Exception e) {
                AiOperatorClient.LOGGER.error("[Camera] Ошибка смены шейдера: {}", e.getMessage());
            }
        });
    }

    public void handleEmergencyStop(JsonObject packet) {
        isMoving = false;
        isOrbiting = false;

        if (packet.has("safe_position")) {
            JsonObject safe = packet.getAsJsonObject("safe_position");
            currentX = safe.get("x").getAsDouble();
            currentY = safe.get("y").getAsDouble();
            currentZ = safe.get("z").getAsDouble();
            currentPitch = safe.get("pitch").getAsFloat();
            currentYaw   = safe.get("yaw").getAsFloat();

            // Телепортируем spectator-игрока в безопасную позицию
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.setPosition(currentX, currentY, currentZ);
                    client.player.setPitch(currentPitch);
                    client.player.setYaw(currentYaw);
                }
            });
        }

        AiOperatorClient.LOGGER.warn("[Camera] 🚨 EMERGENCY STOP! Камера остановлена.");
    }

    public void startRecording() {
        isRecording = true;
        AiOperatorClient.LOGGER.info("[Camera] 🔴 Запись начата.");
        // TODO: интеграция с ReplayMod или FFMPEG capture
    }

    public void stopRecording() {
        isRecording = false;
        AiOperatorClient.LOGGER.info("[Camera] ⬛ Запись остановлена.");
    }

    // ─── Tick (вызывается каждый игровой тик) ─────────────────────────────────

    public void tick(MinecraftClient client) {
        if (client.player == null) return;

        if (isMoving) {
            tickMove(client);
        } else if (isOrbiting) {
            tickOrbit(client);
        }
    }

    private void tickMove(MinecraftClient client) {
        moveElapsedTicks++;
        float t = Math.min(moveElapsedTicks / moveDurationTicks, 1.0f);

        // Функция сглаживания
        float smoothT = switch (smoothing) {
            case "cinematic" -> easeInOutCubic(t);
            case "linear"    -> t;
            case "ease_in"   -> t * t;
            case "ease_out"  -> 1 - (1 - t) * (1 - t);
            default          -> easeInOutCubic(t);
        };

        currentX = lerp(startX, targetX, smoothT);
        currentY = lerp(startY, targetY, smoothT);
        currentZ = lerp(startZ, targetZ, smoothT);
        currentPitch = (float) lerp(startPitch, targetPitch, smoothT);
        currentYaw   = lerpAngle(startYaw, targetYaw, smoothT);

        applyPositionToPlayer(client);

        if (t >= 1.0f) {
            isMoving = false;
            AiOperatorClient.LOGGER.debug("[Camera] MOVE завершено.");
        }
    }

    private void tickOrbit(MinecraftClient client) {
        // Угол растёт на speed градусов в секунду (1 тик = 1/20 сек)
        orbitAngle += orbitSpeed / 20.0;
        if (orbitAngle >= 360.0) orbitAngle -= 360.0;

        double radAngle = Math.toRadians(orbitAngle);
        currentX = orbitCenterX + orbitRadius * Math.cos(radAngle);
        currentY = orbitCenterY + orbitHeight;
        currentZ = orbitCenterZ + orbitRadius * Math.sin(radAngle);

        // Всегда смотрим в центр орбиты
        double dx = orbitCenterX - currentX;
        double dz = orbitCenterZ - currentZ;
        currentYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dist = Math.sqrt(dx * dx + dz * dz);
        currentPitch = (float) -Math.toDegrees(Math.atan2(orbitHeight, dist));

        applyPositionToPlayer(client);
    }

    private void applyPositionToPlayer(MinecraftClient client) {
        if (client.player == null) return;

        // Пробуем управлять через Freecam мод (net.xolt.freecam)
        if (tryApplyViaFreecam(currentX, currentY, currentZ, currentPitch, currentYaw)) {
            return;
        }

        // Fallback: двигаем игрока напрямую (spectator режим)
        client.player.setPosition(currentX, currentY, currentZ);
        client.player.setPitch(currentPitch);
        client.player.setYaw(currentYaw);
    }

    /**
     * Управляет FreeCamera через reflection API Freecam мода.
     * Freecam 1.5.x: net.xolt.freecam.Freecam + FreeCamera entity
     */
    private boolean tryApplyViaFreecam(double x, double y, double z, float pitch, float yaw) {
        try {
            // Проверяем что Freecam загружен
            if (!FabricLoader.getInstance().isModLoaded("freecam")) return false;

            // Активируем Freecam если ещё не активен
            Class<?> freecamClass = Class.forName("net.xolt.freecam.Freecam");
            Object freecamInstance = freecamClass.getMethod("getInstance").invoke(null);

            boolean isActive = (boolean) freecamClass.getMethod("isEnabled").invoke(freecamInstance);
            if (!isActive) {
                // Включаем Freecam программно
                freecamClass.getMethod("toggle").invoke(freecamInstance);
                freecamActive = true;
                AiOperatorClient.LOGGER.info("[Camera] Freecam активирован автоматически!");
            }

            // Получаем FreeCamera entity и двигаем её
            Entity freeCamera = (Entity) freecamClass.getMethod("getFreeCamera").invoke(freecamInstance);
            if (freeCamera != null) {
                freeCamera.setPosition(x, y, z);
                freeCamera.setPitch(pitch);
                freeCamera.setYaw(yaw);
                return true;
            }
        } catch (ClassNotFoundException e) {
            // Freecam не установлен — тихо игнорируем, используем fallback
        } catch (Exception e) {
            AiOperatorClient.LOGGER.debug("[Camera] Freecam API error: {}", e.getMessage());
        }
        return false;
    }


    // ─── Геттеры ───────────────────────────────────────────────────────────────

    public double[] getCurrentPosition(MinecraftClient client) {
        if (client.player != null && !isMoving && !isOrbiting) {
            currentX = client.player.getX();
            currentY = client.player.getY();
            currentZ = client.player.getZ();
        }
        return new double[]{currentX, currentY, currentZ};
    }

    public float[] getCurrentRotation(MinecraftClient client) {
        if (client.player != null && !isMoving && !isOrbiting) {
            currentPitch = client.player.getPitch();
            currentYaw   = client.player.getYaw();
        }
        return new float[]{currentPitch, currentYaw};
    }

    public boolean isRecording()     { return isRecording; }
    public boolean isFreecamActive() { return freecamActive; }
    public String getCurrentShader() { return currentShader; }

    // ─── Math Helpers ──────────────────────────────────────────────────────────

    private static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }

    /** Интерполяция углов по кратчайшему пути (учёт перехода 360→0°) */
    private static float lerpAngle(float a, float b, float t) {
        float diff = b - a;
        while (diff >  180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return a + diff * t;
    }

    private static float easeInOutCubic(float t) {
        return t < 0.5f
                ? 4 * t * t * t
                : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }
}
