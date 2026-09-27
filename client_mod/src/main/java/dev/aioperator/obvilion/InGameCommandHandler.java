package dev.aioperator.obvilion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

/**
 * InGameCommandHandler — обработчик внутриигровых команд оператора.
 * Перехватывает сообщения из чата клиента (как с префиксом точки .cam, так и со слэшем /cam),
 * чтобы сервер не получал их и не ругался "Невідома команда".
 *
 * Команды:
 *  .cam orbit [радиус] — запустить плавную орбиту вокруг игрока
 *  .cam back / .cam reset — вернуть камеру к игроку
 *  .cam stop — экстренная остановка
 *  .cam fov <число> — изменить FOV
 *  .cam free — включить / выключить Freecam
 *  .cam help — список команд
 */
public class InGameCommandHandler {

    public static void register(CameraController cameraController) {
        // Перехват обычного чата (например, .cam orbit или .op orbit)
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            if (trimmed.startsWith(".cam") || trimmed.startsWith(".op")) {
                String args = trimmed.replaceFirst("^\\.(cam|op)", "").trim();
                handleCommand(args, cameraController);
                return false; // Отменяем отправку на сервер!
            }
            return true;
        });

        // Перехват слэш-команд (например, /cam orbit или /op orbit)
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String trimmed = command.trim();
            if (trimmed.startsWith("cam") || trimmed.startsWith("op")) {
                String args = trimmed.replaceFirst("^(cam|op)", "").trim();
                handleCommand(args, cameraController);
                return false; // Отменяем отправку на сервер!
            }
            return true;
        });

        AiOperatorClient.LOGGER.info("[AI Operator] Внутриигровые команды чата (.cam / /cam) зарегистрированы!");
    }

    private static void handleCommand(String args, CameraController cameraController) {
        String[] parts = args.isEmpty() ? new String[0] : args.split("\\s+");
        String sub = parts.length > 0 ? parts[0].toLowerCase() : "help";

        switch (sub) {
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
            case "back", "reset", "return" -> {
                cameraController.handleReturnToPlayer();
                MinecraftBridge.printChatMessage("§b[AI Operator] §e📍 Камера вернулась к персонажу!");
            }
            case "stop" -> {
                cameraController.handleEmergencyStop(new JsonObject());
                MinecraftBridge.printChatMessage("§b[AI Operator] §c🛑 Движение камеры остановлено.");
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
                    MinecraftBridge.printChatMessage("§b[AI Operator] Freecam: " + (isEnabled ? "§aВКЛ" : "§cВЫКЛ"));
                } catch (Exception e) {
                    MinecraftBridge.printChatMessage("§cFreecam не найден.");
                }
            }
            default -> {
                MinecraftBridge.printChatMessage("§b§l=== AI CAMERA OPERATOR ===");
                MinecraftBridge.printChatMessage("§e.cam orbit [радиус] §7- запустить облёт вокруг себя");
                MinecraftBridge.printChatMessage("§e.cam back §7- вернуть камеру к игроку");
                MinecraftBridge.printChatMessage("§e.cam stop §7- экстренная остановка");
                MinecraftBridge.printChatMessage("§e.cam fov <число> §7- установить угол обзора");
                MinecraftBridge.printChatMessage("§e.cam free §7- вкл/выкл свободную камеру");
                MinecraftBridge.printChatMessage("§7(Команды можно писать как через точку §f.cam§7, так и через слэш §f/cam§7)");
            }
        }
    }
}
