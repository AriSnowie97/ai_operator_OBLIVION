package dev.aioperator.obvilion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Конфиг мода — читается из .minecraftx/instances/OBVILION/config/aioperator.properties
 *
 * Пример файла:
 *   core.url=ws://localhost:8765/ws/client
 *   core.token=my-secret-token
 *   telemetry.interval_ms=50
 */
public class OperatorConfig {

    private static final String DEFAULT_URL = "ws://localhost:8765/ws/client";
    private static final String CONFIG_FILENAME = "aioperator.properties";

    private final String coreUrl;
    private final String token;
    private final int telemetryIntervalMs;

    private OperatorConfig(String coreUrl, String token, int telemetryIntervalMs) {
        this.coreUrl = coreUrl;
        this.token = token;
        this.telemetryIntervalMs = telemetryIntervalMs;
    }

    public static OperatorConfig load() {
        // Ищем конфиг в папке config/ инстанса
        Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir();
        Path configFile = configDir.resolve(CONFIG_FILENAME);

        Properties props = new Properties();

        if (Files.exists(configFile)) {
            try (var reader = Files.newBufferedReader(configFile)) {
                props.load(reader);
                AiOperatorClient.LOGGER.info("[Config] Загружен конфиг: {}", configFile);
            } catch (IOException e) {
                AiOperatorClient.LOGGER.warn("[Config] Ошибка чтения конфига: {}", e.getMessage());
            }
        } else {
            // Создаём конфиг по умолчанию
            createDefault(configFile);
            AiOperatorClient.LOGGER.info("[Config] Создан конфиг по умолчанию: {}", configFile);
        }

        String url = props.getProperty("core.url", DEFAULT_URL);
        String token = props.getProperty("core.token", "");
        int interval = Integer.parseInt(props.getProperty("telemetry.interval_ms", "50"));

        return new OperatorConfig(url, token, interval);
    }

    private static void createDefault(Path configFile) {
        String defaultContent = """
                # AI Operator Client — конфигурация
                # Адрес Core-сервера (ws:// или wss://)
                core.url=ws://localhost:8765/ws/client
                
                # Токен аутентификации (опционально)
                core.token=
                
                # Интервал отправки телеметрии в миллисекундах
                telemetry.interval_ms=50
                """;
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, defaultContent);
        } catch (IOException e) {
            AiOperatorClient.LOGGER.warn("[Config] Не удалось создать конфиг по умолчанию: {}", e.getMessage());
        }
    }

    public String getCoreUrl()          { return coreUrl; }
    public String getToken()            { return token; }
    public int getTelemetryIntervalMs() { return telemetryIntervalMs; }
}
