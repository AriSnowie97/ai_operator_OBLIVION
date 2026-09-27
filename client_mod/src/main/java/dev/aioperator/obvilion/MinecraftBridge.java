package dev.aioperator.obvilion;

import java.util.concurrent.Executor;

/**
 * MinecraftBridge — универсальный мост к Minecraft клиенту.
 * Использует reflection, чтобы быть 100% независимым от версии Minecraft
 * и типа маппингов (Yarn vs Mojang mappings в 1.21.x / 26.x).
 *
 * Предотвращает любые NoClassDefFoundError: net/minecraft/client/MinecraftClient!
 */
public class MinecraftBridge {

    private static Class<?> mcClass;
    private static Object mcInstance;

    public static Object getMinecraft() {
        if (mcInstance == null) {
            try {
                // Minecraft 26.x / Mojang mappings
                mcClass = Class.forName("net.minecraft.client.Minecraft");
                mcInstance = mcClass.getMethod("getInstance").invoke(null);
            } catch (ClassNotFoundException e) {
                try {
                    // Fallback: Yarn mappings
                    mcClass = Class.forName("net.minecraft.client.MinecraftClient");
                    mcInstance = mcClass.getMethod("getInstance").invoke(null);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        return mcInstance;
    }

    public static void execute(Runnable runnable) {
        Object mc = getMinecraft();
        if (mc instanceof Executor executor) {
            executor.execute(runnable);
        } else {
            runnable.run();
        }
    }

    public static Object getPlayer() {
        Object mc = getMinecraft();
        if (mc == null) return null;
        try {
            return mcClass.getField("player").get(mc);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isPlayerInWorld() {
        return getPlayer() != null;
    }

    public static double[] getPlayerPos() {
        Object player = getPlayer();
        if (player == null) return new double[]{0, 100, 0};
        try {
            double x = (double) player.getClass().getMethod("getX").invoke(player);
            double y = (double) player.getClass().getMethod("getY").invoke(player);
            double z = (double) player.getClass().getMethod("getZ").invoke(player);
            return new double[]{x, y, z};
        } catch (Exception e) {
            return new double[]{0, 100, 0};
        }
    }

    public static float[] getPlayerRot() {
        Object player = getPlayer();
        if (player == null) return new float[]{0, 0};
        try {
            float pitch = 0f, yaw = 0f;
            try {
                // Mojang mappings: getXRot, getYRot
                pitch = (float) player.getClass().getMethod("getXRot").invoke(player);
                yaw   = (float) player.getClass().getMethod("getYRot").invoke(player);
            } catch (NoSuchMethodException e) {
                // Yarn mappings: getPitch, getYaw
                pitch = (float) player.getClass().getMethod("getPitch").invoke(player);
                yaw   = (float) player.getClass().getMethod("getYaw").invoke(player);
            }
            return new float[]{pitch, yaw};
        } catch (Exception e) {
            return new float[]{0, 0};
        }
    }

    public static int getFps() {
        Object mc = getMinecraft();
        if (mc == null) return 60;
        try {
            return (int) mcClass.getMethod("getCurrentFps").invoke(mc);
        } catch (Exception e) {
            try {
                return (int) mcClass.getMethod("getFps").invoke(mc);
            } catch (Exception ex) {
                return 60;
            }
        }
    }

    public static int getFov() {
        Object mc = getMinecraft();
        if (mc == null) return 70;
        try {
            Object options = mcClass.getField("options").get(mc);
            try {
                Object fovOption = options.getClass().getMethod("fov").invoke(options);
                return (int) fovOption.getClass().getMethod("get").invoke(fovOption);
            } catch (Exception ex) {
                Object fovOption = options.getClass().getMethod("getFov").invoke(options);
                return (int) fovOption.getClass().getMethod("getValue").invoke(fovOption);
            }
        } catch (Exception e) {
            return 70;
        }
    }

    public static void setFov(int fov) {
        execute(() -> {
            try {
                Object mc = getMinecraft();
                if (mc == null) return;
                Object options = mcClass.getField("options").get(mc);
                try {
                    Object fovOption = options.getClass().getMethod("fov").invoke(options);
                    fovOption.getClass().getMethod("set", Object.class).invoke(fovOption, fov);
                } catch (Exception ex) {
                    Object fovOption = options.getClass().getMethod("getFov").invoke(options);
                    fovOption.getClass().getMethod("setValue", Object.class).invoke(fovOption, fov);
                }
            } catch (Exception ignored) {}
        });
    }

    public static void printChatMessage(String message) {
        execute(() -> {
            try {
                Object player = getPlayer();
                if (player == null) return;
                try {
                    // Mojang mappings
                    Class<?> compClass = Class.forName("net.minecraft.network.chat.Component");
                    Object comp = compClass.getMethod("literal", String.class).invoke(null, message);
                    player.getClass().getMethod("sendSystemMessage", compClass).invoke(player, comp);
                } catch (ClassNotFoundException e) {
                    // Yarn mappings
                    Class<?> textClass = Class.forName("net.minecraft.text.Text");
                    Object comp = textClass.getMethod("literal", String.class).invoke(null, message);
                    player.getClass().getMethod("sendMessage", textClass, boolean.class).invoke(player, comp, false);
                }
            } catch (Exception ignored) {}
        });
    }
}
