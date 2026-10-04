package dev.aioperator.obvilion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InGameCommandHandler — обработчик внутриигровых команд оператора.
 * Перехватывает сообщения из чата клиента (как с префиксом точки .cam, так и со слэшем /cam),
 * чтобы сервер не получал их и не ругался "Невідома команда".
 *
 * Команды:
 *  .cam off / .cam exit — полностью выключить режим камеры и вернуться в тело игрока
 *  .cam unlock — снять аварийную блокировку стоп-крана
 *  .cam front — вид спереди на лицо персонажа
 *  .cam back — вид сзади за спиной
 *  .cam side — кинематографичный вид сбоку
 *  .cam top — вид сверху (изометрия)
 *  .cam tp X Y Z — переместить камеру по точным координатам
 *  .cam save <имя> — сохранить текущий ракурс камеры в память
 *  .cam goto <имя> — прилететь к сохранённой точке
 *  .cam list — список сохранённых точек
 *  .cam lock — зафиксировать камеру на месте и вернуть управление персонажу
 *  .cam free — включить / выключить Freecam для ручного полёта
 *  .cam orbit [радиус] — запустить плавную орбиту вокруг игрока
 *  .cam stop — экстренная остановка (выход из камеры к игроку)
 *  .cam fov <число> — изменить FOV
 *  .cam help — список команд
 */
public class InGameCommandHandler {

    private static final Map<String, double[]> savedWaypoints = new ConcurrentHashMap<>();

    public static void register(OperatorWebSocketClient wsClient) {
        CameraController cameraController = wsClient.getCameraController();

        // Перехват обычного чата (например, .cam orbit или .op orbit)
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            if (trimmed.equals(".cam") || trimmed.startsWith(".cam ") || trimmed.equals(".op") || trimmed.startsWith(".op ")) {
                String args = trimmed.replaceFirst("^\\.(cam|op)", "").trim();
                handleCommand(args, cameraController, wsClient);
                return false; // Отменяем отправку на сервер!
            }
            return true;
        });

        // Перехват слэш-команд (ТОЛЬКО /cam или /aioperator, НЕ трогая /op и другие серверные команды)
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String trimmed = command.trim();
            if (trimmed.equals("cam") || trimmed.startsWith("cam ") || trimmed.equals("aioperator") || trimmed.startsWith("aioperator ")) {
                String args = trimmed.replaceFirst("^(cam|aioperator)", "").trim();
                handleCommand(args, cameraController, wsClient);
                return false; // Отменяем отправку на сервер!
            }
            return true;
        });

        AiOperatorClient.LOGGER.info("[AI Operator] Внутриигровые команды чата (.cam / /cam) зарегистрированы!");
    }

    private static void handleCommand(String args, CameraController cameraController, OperatorWebSocketClient wsClient) {
        String[] parts = args.isEmpty() ? new String[0] : args.split("\\s+");
        String sub = parts.length > 0 ? parts[0].toLowerCase() : "help";

        switch (sub) {
            case "off", "exit", "close", "disable", "pause" -> {
                cameraController.setManualOverride(true);
                MinecraftBridge.printChatMessage("§b[AI Operator] §aСвободная камера отключена! AI-оператор на паузе. (Чтобы снова включить: .cam on)");
            }
            case "on", "resume", "start", "enable" -> {
                cameraController.setManualOverride(false);
                MinecraftBridge.printChatMessage("§b[AI Operator] §aAI-оператор возобновил работу! Камера слушает команды.");
            }
            case "unlock", "unblock", "reset_lock" -> {
                cameraController.setManualOverride(false);
                cameraController.exitCameraMode();
                if (wsClient != null) {
                    wsClient.unlockEmergency();
                }
                MinecraftBridge.printChatMessage("§b[AI Operator] §aЗапрос на снятие блокировки стоп-крана отправлен!");
            }
            case "front" -> {
                cameraController.setPresetFront();
                MinecraftBridge.printChatMessage("§b[AI Operator] §a🎥 Ракурс: СПЕРЕДИ (вид на лицо)!");
            }
            case "back", "reset", "return" -> {
                cameraController.setPresetBehind();
                MinecraftBridge.printChatMessage("§b[AI Operator] §e📍 Ракурс: СЗАДИ (вид за спиной)!");
            }
            case "side" -> {
                cameraController.setPresetSide(true);
                MinecraftBridge.printChatMessage("§b[AI Operator] §d🎬 Ракурс: СБОКУ (профиль)!");
            }
            case "top" -> {
                cameraController.setPresetTop();
                MinecraftBridge.printChatMessage("§b[AI Operator] §6🦅 Ракурс: СВЕРХУ (изометрия)!");
            }
            case "tp", "pos", "goto_pos" -> {
                if (parts.length >= 4) {
                    try {
                        double x = Double.parseDouble(parts[1]);
                        double y = Double.parseDouble(parts[2]);
                        double z = Double.parseDouble(parts[3]);
                        float pitch = parts.length >= 5 ? Float.parseFloat(parts[4]) : 0f;
                        float yaw   = parts.length >= 6 ? Float.parseFloat(parts[5]) : 0f;
                        cameraController.moveToPosition(x, y, z, pitch, yaw, 1.5f);
                        MinecraftBridge.printChatMessage("§b[AI Operator] §a🚀 Камера летит к (" + Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z) + ")");
                    } catch (Exception e) {
                        MinecraftBridge.printChatMessage("§cИспользование: .cam tp <X> <Y> <Z> [pitch] [yaw]");
                    }
                } else {
                    MinecraftBridge.printChatMessage("§cИспользование: .cam tp <X> <Y> <Z> [pitch] [yaw]");
                }
            }
            case "save" -> {
                if (parts.length >= 2) {
                    String name = parts[1].toLowerCase();
                    double[] pos = cameraController.getCurrentPosition();
                    float[] rot = cameraController.getCurrentRotation();
                    savedWaypoints.put(name, new double[]{pos[0], pos[1], pos[2], rot[0], rot[1]});
                    MinecraftBridge.printChatMessage("§b[AI Operator] §a💾 Точка '" + name + "' сохранена! (" +
                            Math.round(pos[0]) + ", " + Math.round(pos[1]) + ", " + Math.round(pos[2]) + ")");
                } else {
                    MinecraftBridge.printChatMessage("§cИспользование: .cam save <название>");
                }
            }
            case "goto", "load" -> {
                if (parts.length >= 2) {
                    String name = parts[1].toLowerCase();
                    double[] pt = savedWaypoints.get(name);
                    if (pt != null) {
                        cameraController.moveToPosition(pt[0], pt[1], pt[2], (float) pt[3], (float) pt[4], 2.0f);
                        MinecraftBridge.printChatMessage("§b[AI Operator] §a✈️ Перелёт к точке '" + name + "'!");
                    } else {
                        MinecraftBridge.printChatMessage("§cТочка '" + name + "' не найдена. Список: .cam list");
                    }
                } else {
                    MinecraftBridge.printChatMessage("§cИспользование: .cam goto <название>");
                }
            }
            case "list", "points" -> {
                if (savedWaypoints.isEmpty()) {
                    MinecraftBridge.printChatMessage("§b[AI Operator] §7Нет сохранённых точек. Используйте: .cam save <имя>");
                } else {
                    MinecraftBridge.printChatMessage("§b[AI Operator] §eСохранённые ракурсы:");
                    savedWaypoints.forEach((name, pt) -> {
                        MinecraftBridge.printChatMessage("§7 - §f" + name + " §7(" +
                                Math.round(pt[0]) + ", " + Math.round(pt[1]) + ", " + Math.round(pt[2]) + ")");
                    });
                }
            }
            case "lock", "player", "ctrl" -> {
                boolean playerCtrl = cameraController.togglePlayerControl();
                if (playerCtrl) {
                    MinecraftBridge.printChatMessage("§b[AI Operator] §a🔒 Камера зафиксирована! Управление на игроке (вы можете играть).");
                } else {
                    MinecraftBridge.printChatMessage("§b[AI Operator] §e🕹️ Управление на камере (полет на WASD).");
                }
            }
            case "orbit" -> {
                double[] p = MinecraftBridge.getPlayerPos();
                double radius = 10.0;
                double speed = 15.0;
                double height = 3.5;
                if (parts.length > 1) {
                    try { radius = Double.parseDouble(parts[1]); } catch (Exception ignored) {}
                }
                JsonObject pkt = new JsonObject();
                JsonArray arr = new JsonArray();
                arr.add(p[0]); arr.add(p[1]); arr.add(p[2]);
                pkt.add("center", arr);
                pkt.addProperty("radius", radius);
                pkt.addProperty("speed", speed);
                pkt.addProperty("height", height);
                cameraController.handleOrbit(pkt);
                MinecraftBridge.printChatMessage("§b[AI Operator] §a🌀 Орбита вокруг вас запущена (радиус: " + radius + "м)!");
            }
            case "stop" -> {
                cameraController.handleEmergencyStop(new JsonObject());
                if (wsClient != null) {
                    wsClient.unlockEmergency();
                }
            }
            case "fov" -> {
                if (parts.length > 1) {
                    try {
                        int fov = Integer.parseInt(parts[1]);
                        MinecraftBridge.setFov(fov);
                        MinecraftBridge.printChatMessage("§b[AI Operator] §d🔍 FOV установлен на " + fov + "°");
                    } catch (Exception e) {
                        MinecraftBridge.printChatMessage("§cИспользование: .cam fov <число>");
                    }
                } else {
                    MinecraftBridge.printChatMessage("§b[AI Operator] Текущий FOV: " + MinecraftBridge.getFov() + "°");
                }
            }
            case "free" -> {
                try {
                    Class<?> freecamClass = Class.forName("net.xolt.freecam.Freecam");
                    freecamClass.getMethod("toggle").invoke(null);
                    boolean isEnabled = (boolean) freecamClass.getMethod("isEnabled").invoke(null);
                    MinecraftBridge.printChatMessage("§b[AI Operator] Freecam: " + (isEnabled ? "§aВКЛ (летайте на WASD)" : "§cВЫКЛ"));
                } catch (Exception e) {
                    MinecraftBridge.printChatMessage("§cFreecam не найден.");
                }
            }
            default -> {
                MinecraftBridge.printChatMessage("§b§l=== AI CAMERA OPERATOR ===");
                MinecraftBridge.printChatMessage("§a.cam off §7(или .cam exit) - §eвыйти из камеры в тело игрока");
                MinecraftBridge.printChatMessage("§a.cam unlock §7- снять блокировку стоп-крана");
                MinecraftBridge.printChatMessage("§e.cam front §7- поставить камеру перед лицом");
                MinecraftBridge.printChatMessage("§e.cam back §7- поставить камеру сзади");
                MinecraftBridge.printChatMessage("§e.cam side §7- кинематографичный вид сбоку");
                MinecraftBridge.printChatMessage("§e.cam top §7- вид сверху (изометрия)");
                MinecraftBridge.printChatMessage("§e.cam orbit [R] §7- запустить облёт вокруг вас");
                MinecraftBridge.printChatMessage("§e.cam save <имя> §7- сохранить текущий ракурс");
                MinecraftBridge.printChatMessage("§e.cam goto <имя> §7- прилететь к сохранённой точке");
                MinecraftBridge.printChatMessage("§e.cam lock §7- закрепить камеру и бегать игроком");
                MinecraftBridge.printChatMessage("§e.cam tp X Y Z §7- перелететь по координатам");
                MinecraftBridge.printChatMessage("§e.cam free §7- свободный полёт камеры на WASD");
                MinecraftBridge.printChatMessage("§e.cam stop §7- экстренная остановка");
                MinecraftBridge.printChatMessage("§e.cam fov <число> §7- изменить угол обзора");
                MinecraftBridge.printChatMessage("§7(Команды работают и через слэш: §f/cam ...§7)");
            }
        }
    }
}
