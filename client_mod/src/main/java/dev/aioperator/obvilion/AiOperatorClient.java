package dev.aioperator.obvilion;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Operator Client — главная точка входа Fabric мода.
 * Запускает WebSocket-соединение с Core-сервером и обрабатывает команды камеры.
 */
public class AiOperatorClient implements ClientModInitializer {

    public static final String MOD_ID = "ai-operator-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static OperatorWebSocketClient wsClient;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[AI Operator] Mod загружен! Подключаемся к Core-серверу...");

        // Читаем конфиг и запускаем WebSocket клиент
        OperatorConfig config = OperatorConfig.load();
        wsClient = new OperatorWebSocketClient(config);
        wsClient.connect();

        // Каждый тик клиента — отправляем телеметрию и обрабатываем команды
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (wsClient != null) {
                wsClient.onTick(client);
            }
        });

        LOGGER.info("[AI Operator] Инициализация завершена. Core: {}", config.getCoreUrl());
    }

    public static OperatorWebSocketClient getWsClient() {
        return wsClient;
    }
}
