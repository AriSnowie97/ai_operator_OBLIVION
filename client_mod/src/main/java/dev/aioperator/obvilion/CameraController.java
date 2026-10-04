package dev.aioperator.obvilion;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

/**
 * CameraController — обрабатывает все команды движения камеры.
 * Полностью независим от версий Minecraft (нет прямых импортов Minecraft).
 *
 * Использует Freecam API (если мод установлен) для плавного перемещения.
 *
 * Поддерживаемые команды:
 *  - MOVE: плавное перемещение в точку за duration секунд
 *  - ORBIT: вращение вокруг центральной точки
 *  - SET_FOV: изменение угла обзора
 *  - SET_SHADER: смена шейдерпака через Iris API
 *  - EMERGENCY_STOP: мгновенная остановка
 */
public class CameraController {

    // ─── Состояние ─────────────────────────────────────────────────────────────
    private boolean freecamActive = false;
    private boolean isRecording = false;
    private boolean manualOverride = false;
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

    // Текущая позиция и ориентация
    private double currentX, currentY, currentZ;
    private float currentPitch, currentYaw;

    // ─── Ручной режим игрока ───────────────────────────────────────────────────

    public void setManualOverride(boolean override) {
        this.manualOverride = override;
        if (override) {
            exitCameraMode();
        }
    }

    public boolean isManualOverride() {
        return manualOverride;
    }

    // ─── Команды ───────────────────────────────────────────────────────────────

    public void handleMove(JsonObject packet) {
        if (manualOverride) {
            AiOperatorClient.LOGGER.info("[Camera] MOVE отклонен: включен ручной режим игрока (.cam off)");
            return;
        }
        targetX = packet.get("x").getAsDouble();
        targetY = packet.get("y").getAsDouble();
        targetZ = packet.get("z").getAsDouble();
        targetPitch = packet.has("pitch") ? packet.get("pitch").getAsFloat() : currentPitch;
        targetYaw   = packet.has("yaw")   ? packet.get("yaw").getAsFloat()   : currentYaw;
        float duration = packet.has("duration") ? packet.get("duration").getAsFloat() : 2.0f;
        smoothing = packet.has("smoothing") ? packet.get("smoothing").getAsString() : "cinematic";

        if (startX == 0 && startY == 0 && startZ == 0) {
            double[] playerPos = MinecraftBridge.getPlayerPos();
            float[] playerRot = MinecraftBridge.getPlayerRot();
            currentX = playerPos[0];
            currentY = playerPos[1];
            currentZ = playerPos[2];
            currentPitch = playerRot[0];
            currentYaw = playerRot[1];
        }

        startX = currentX; startY = currentY; startZ = currentZ;
        startPitch = currentPitch; startYaw = currentYaw;

        moveDurationTicks = duration * 20f;   // секунды → тики
        moveElapsedTicks  = 0f;
        isMoving = true;
        isOrbiting = false;   // Прерываем орбиту если была

        AiOperatorClient.LOGGER.info("[Camera] MOVE → ({}, {}, {}) за {} сек", targetX, targetY, targetZ, duration);
    }

    public void handleOrbit(JsonObject packet) {
        if (manualOverride) {
            AiOperatorClient.LOGGER.info("[Camera] ORBIT отклонен: включен ручной режим игрока (.cam off)");
            return;
        }
        if (packet.has("center")) {
            var centerElem = packet.get("center");
            if (centerElem.isJsonArray()) {
                var arr = centerElem.getAsJsonArray();
                orbitCenterX = arr.get(0).getAsDouble();
                orbitCenterY = arr.get(1).getAsDouble();
                orbitCenterZ = arr.get(2).getAsDouble();
            } else if (centerElem.isJsonObject()) {
                var obj = centerElem.getAsJsonObject();
                orbitCenterX = obj.get("x").getAsDouble();
                orbitCenterY = obj.get("y").getAsDouble();
                orbitCenterZ = obj.get("z").getAsDouble();
            }
        }
        orbitRadius = packet.has("radius") ? packet.get("radius").getAsDouble() : 20.0;
        orbitSpeed  = packet.has("speed")  ? packet.get("speed").getAsDouble()  : 15.0;
        orbitHeight = packet.has("height") ? packet.get("height").getAsDouble() : 5.0;

        orbitAngle = Math.toDegrees(Math.atan2(currentZ - orbitCenterZ, currentX - orbitCenterX));
        isOrbiting = true;
        isMoving = false;

        AiOperatorClient.LOGGER.info("[Camera] ORBIT вокруг ({}, {}, {}) R={} speed={}°/s",
                orbitCenterX, orbitCenterY, orbitCenterZ, orbitRadius, orbitSpeed);
    }

    public void handleReturnToPlayer() {
        setPresetBehind();
    }

    public void setPresetBehind() {
        double[] p = MinecraftBridge.getPlayerPos();
        float[] r = MinecraftBridge.getPlayerRot();
        double yawRad = Math.toRadians(r[1]);
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);

        targetX = p[0] - lookX * 3.5;
        targetY = p[1] + 2.0;
        targetZ = p[2] - lookZ * 3.5;
        targetPitch = 12f;
        targetYaw = r[1];
        startMoveTransition(15f);
        AiOperatorClient.LOGGER.info("[Camera] Пресет 'сзади': ({}, {}, {})", targetX, targetY, targetZ);
    }

    public void setPresetFront() {
        double[] p = MinecraftBridge.getPlayerPos();
        float[] r = MinecraftBridge.getPlayerRot();
        double yawRad = Math.toRadians(r[1]);
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);

        targetX = p[0] + lookX * 3.5;
        targetY = p[1] + 1.6;
        targetZ = p[2] + lookZ * 3.5;
        targetPitch = 5f;
        targetYaw = (r[1] + 180f) % 360f;
        startMoveTransition(15f);
        AiOperatorClient.LOGGER.info("[Camera] Пресет 'спереди': ({}, {}, {})", targetX, targetY, targetZ);
    }

    public void setPresetSide(boolean rightSide) {
        double[] p = MinecraftBridge.getPlayerPos();
        float[] r = MinecraftBridge.getPlayerRot();
        double yawRad = Math.toRadians(r[1]);
        double perpX = rightSide ? Math.cos(yawRad) : -Math.cos(yawRad);
        double perpZ = rightSide ? Math.sin(yawRad) : -Math.sin(yawRad);

        targetX = p[0] + perpX * 3.5;
        targetY = p[1] + 1.8;
        targetZ = p[2] + perpZ * 3.5;
        targetPitch = 8f;
        targetYaw = rightSide ? (r[1] - 90f) : (r[1] + 90f);
        startMoveTransition(15f);
        AiOperatorClient.LOGGER.info("[Camera] Пресет 'сбоку': ({}, {}, {})", targetX, targetY, targetZ);
    }

    public void setPresetTop() {
        double[] p = MinecraftBridge.getPlayerPos();
        float[] r = MinecraftBridge.getPlayerRot();
        double yawRad = Math.toRadians(r[1]);
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);

        targetX = p[0] - lookX * 4.0;
        targetY = p[1] + 8.0;
        targetZ = p[2] - lookZ * 4.0;
        targetPitch = 45f;
        targetYaw = r[1];
        startMoveTransition(20f);
        AiOperatorClient.LOGGER.info("[Camera] Пресет 'сверху': ({}, {}, {})", targetX, targetY, targetZ);
    }

    public void moveToPosition(double x, double y, double z, float pitch, float yaw, float durationSec) {
        targetX = x;
        targetY = y;
        targetZ = z;
        targetPitch = pitch;
        targetYaw = yaw;
        smoothing = "cinematic";
        startMoveTransition(durationSec * 20f);
        AiOperatorClient.LOGGER.info("[Camera] Перемещение к ({}, {}, {}) за {} сек", x, y, z, durationSec);
    }

    public void setPositionImmediate(double x, double y, double z, float pitch, float yaw) {
        currentX = x;
        currentY = y;
        currentZ = z;
        currentPitch = pitch;
        currentYaw = yaw;
        isMoving = false;
        isOrbiting = false;
        applyPosition();
        AiOperatorClient.LOGGER.info("[Camera] Мгновенная позиция ({}, {}, {})", x, y, z);
    }

    private void startMoveTransition(float ticks) {
        if (startX == 0 && startY == 0 && startZ == 0) {
            double[] p = MinecraftBridge.getPlayerPos();
            float[] r = MinecraftBridge.getPlayerRot();
            currentX = p[0]; currentY = p[1]; currentZ = p[2];
            currentPitch = r[0]; currentYaw = r[1];
        }
        startX = currentX; startY = currentY; startZ = currentZ;
        startPitch = currentPitch; startYaw = currentYaw;
        moveDurationTicks = Math.max(ticks, 1f);
        moveElapsedTicks = 0f;
        isMoving = true;
        isOrbiting = false;
    }

    public boolean togglePlayerControl() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("freecam")) return false;
            Class<?> freecamClass = Class.forName("net.xolt.freecam.Freecam");
            if (!(boolean) freecamClass.getMethod("isEnabled").invoke(null)) {
                freecamClass.getMethod("toggle").invoke(null);
            }
            freecamClass.getMethod("switchControls").invoke(null);
            return (boolean) freecamClass.getMethod("isPlayerControlEnabled").invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    public void handleSetFov(JsonObject packet) {
        float fov = packet.get("fov").getAsFloat();
        MinecraftBridge.setFov((int) fov);
        AiOperatorClient.LOGGER.info("[Camera] SET_FOV → {}°", fov);
    }

    public void handleSetShader(JsonObject packet) {
        String shaderpack = packet.has("shaderpack") ? packet.get("shaderpack").getAsString() : "none";
        String profile    = packet.has("profile")    ? packet.get("profile").getAsString()    : "Medium";

        MinecraftBridge.execute(() -> {
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

    public void exitCameraMode() {
        isMoving = false;
        isOrbiting = false;
        try {
            if (FabricLoader.getInstance().isModLoaded("freecam")) {
                Class<?> freecamClass = Class.forName("net.xolt.freecam.Freecam");
                boolean isEnabled = (boolean) freecamClass.getMethod("isEnabled").invoke(null);
                if (isEnabled) {
                    freecamClass.getMethod("toggle").invoke(null);
                }
            }
        } catch (Exception ignored) {}
        freecamActive = false;
        AiOperatorClient.LOGGER.info("[Camera] Выход из режима свободной камеры.");
    }

    public void handleEmergencyStop(JsonObject packet) {
        isMoving = false;
        isOrbiting = false;
        exitCameraMode();
        MinecraftBridge.printChatMessage("§c[AI Operator] 🛑 СТОП-КРАН активирован! Свободная камера отключена, вы вернулись к персонажу.");
        AiOperatorClient.LOGGER.warn("[Camera] 🚨 EMERGENCY STOP! Свободная камера отключена, возврат игроку.");
    }

    public void startRecording() {
        isRecording = true;
        AiOperatorClient.LOGGER.info("[Camera] 🔴 Запись начата.");
    }

    public void stopRecording() {
        isRecording = false;
        AiOperatorClient.LOGGER.info("[Camera] ⬛ Запись остановлена.");
    }

    // ─── Tick ──────────────────────────────────────────────────────────────────

    public void tick() {
        if (!MinecraftBridge.isPlayerInWorld()) return;

        if (isMoving) {
            tickMove();
        } else if (isOrbiting) {
            tickOrbit();
        }
    }

    private void tickMove() {
        moveElapsedTicks++;
        float t = Math.min(moveElapsedTicks / moveDurationTicks, 1.0f);

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

        applyPosition();

        if (t >= 1.0f) {
            isMoving = false;
            AiOperatorClient.LOGGER.debug("[Camera] MOVE завершено.");
        }
    }

    private void tickOrbit() {
        orbitAngle += orbitSpeed / 20.0;
        if (orbitAngle >= 360.0) orbitAngle -= 360.0;

        double radAngle = Math.toRadians(orbitAngle);
        currentX = orbitCenterX + orbitRadius * Math.cos(radAngle);
        currentY = orbitCenterY + orbitHeight;
        currentZ = orbitCenterZ + orbitRadius * Math.sin(radAngle);

        double dx = orbitCenterX - currentX;
        double dz = orbitCenterZ - currentZ;
        currentYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dist = Math.sqrt(dx * dx + dz * dz);
        currentPitch = (float) -Math.toDegrees(Math.atan2(orbitHeight, dist));

        applyPosition();
    }

    private void applyPosition() {
        MinecraftBridge.execute(() -> {
            tryApplyViaFreecam(currentX, currentY, currentZ, currentPitch, currentYaw);
        });
    }

    /**
     * Управляет FreeCamera через reflection API Freecam мода.
     * Freecam 1.5.x: net.xolt.freecam.Freecam + FreecamPosition
     */
    private boolean tryApplyViaFreecam(double x, double y, double z, float pitch, float yaw) {
        try {
            if (!FabricLoader.getInstance().isModLoaded("freecam")) return false;

            Class<?> freecamClass = Class.forName("net.xolt.freecam.Freecam");

            // 1. Активируем Freecam если ещё не активен (статический метод isEnabled())
            boolean isActive = (boolean) freecamClass.getMethod("isEnabled").invoke(null);
            if (!isActive) {
                freecamClass.getMethod("toggle").invoke(null);
                freecamActive = true;
                AiOperatorClient.LOGGER.info("[Camera] Freecam активирован автоматически!");
            } else {
                freecamActive = true;
            }

            // 2. Убеждаемся, что управление остаётся у игрока (чтобы игрок мог ходить/бегать/играть)
            boolean isPlayerControl = (boolean) freecamClass.getMethod("isPlayerControlEnabled").invoke(null);
            if (!isPlayerControl) {
                freecamClass.getMethod("switchControls").invoke(null);
                AiOperatorClient.LOGGER.info("[Camera] Управление переключено на игрока (камера автономна)!");
            }

            // 3. Создаём FreecamPosition и передаём в Freecam.moveToPosition
            Class<?> posClass = Class.forName("net.xolt.freecam.util.FreecamPosition");
            Object player = MinecraftBridge.getPlayer();
            Object posObj = null;
            for (var ctor : posClass.getConstructors()) {
                if (ctor.getParameterCount() == 1) {
                    posObj = ctor.newInstance(player);
                    break;
                }
            }

            if (posObj != null) {
                posClass.getField("x").setDouble(posObj, x);
                posClass.getField("y").setDouble(posObj, y);
                posClass.getField("z").setDouble(posObj, z);
                posClass.getField("pitch").setFloat(posObj, pitch);
                posClass.getField("yaw").setFloat(posObj, yaw);

                var moveToPosMethod = freecamClass.getMethod("moveToPosition", posClass);
                moveToPosMethod.invoke(null, posObj);
                return true;
            }
        } catch (ClassNotFoundException e) {
            // Freecam не установлен
        } catch (Exception e) {
            AiOperatorClient.LOGGER.debug("[Camera] Freecam reflection: {}", e.getMessage());
        }
        return false;
    }

    // ─── Геттеры ───────────────────────────────────────────────────────────────

    public double[] getCurrentPosition() {
        if (!isMoving && !isOrbiting && !freecamActive) {
            double[] p = MinecraftBridge.getPlayerPos();
            currentX = p[0];
            currentY = p[1];
            currentZ = p[2];
        }
        return new double[]{currentX, currentY, currentZ};
    }

    public float[] getCurrentRotation() {
        if (!isMoving && !isOrbiting && !freecamActive) {
            float[] r = MinecraftBridge.getPlayerRot();
            currentPitch = r[0];
            currentYaw   = r[1];
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
