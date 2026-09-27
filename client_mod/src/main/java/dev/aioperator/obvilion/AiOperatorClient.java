package dev.aioperator.obvilion;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI Operator Client — главная точка входа Fabric мода.
 * Запускает WebSocket-соединение с Core-сервером и обрабатывает команды камеры.
 * Полностью независим от версий Minecraft благодаря MinecraftBridge.
 */
public class AiOperatorClient implements ClientModInitializer {

    public static final String MOD_ID = "ai-operator-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static OperatorWebSocketClient wsClient;
    private static ScheduledExecutorService ticker;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[AI Operator] Mod загружен! Подключаемся к Core-серверу...");

        // Читаем конфиг и запускаем WebSocket клиент
        OperatorConfig config = OperatorConfig.load();
        wsClient = new OperatorWebSocketClient(config);
        wsClient.connect();

        // Запускаем таймер тиков (20 раз в секунду, каждые 50мс)
        // Не зависит от Fabric API и версий маппингов Minecraft!
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-operator-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(() -> {
            try {
                if (wsClient != null) {
                    wsClient.onTick();
                }
            } catch (Throwable t) {
                LOGGER.debug("[AI Operator] Ошибка в цикле тика: {}", t.getMessage());
            }
        }, 100, 50, TimeUnit.MILLISECONDS);

        // Регистрируем внутриигровые команды чата (.cam / /cam)
        InGameCommandHandler.register(wsClient.getCameraController());

        LOGGER.info("[AI Operator] Инициализация завершена. Core: {}", config.getCoreUrl());
    }

    public static OperatorWebSocketClient getWsClient() {
        return wsClient;
    }
}
