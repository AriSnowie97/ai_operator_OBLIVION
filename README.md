# 🎬 Minecraft AI Camera Operator (OBLIVION)

Виртуальный кинематографический «ИИ-оператор» камеры для серверов Minecraft (версии **1.21.1+**). 
Создан для стримеров и контент-мейкеров: обеспечивает плавные пролёты, орбиты, зум, пресеты ракурсов и съёмку с поддержкой шейдеров (**Iris + Sodium**), не давая преимуществ в геймплее.

Управляется параллельно:
1. **ИИ-агентом** через протокол **MCP (Model Context Protocol)**.
2. **Человеком (стримером)** через современный Web-дашборд или команды в чате (`/cam`).

---

## 🏛️ Архитектура системы

```
┌────────────────────────────────────────────────────────┐
│  AI Agent (Claude / Gemini / Cursor) via MCP Protocol  │
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│              CORE SERVER (FastAPI / 24/7)               │
│  - MCP Server (12 инструментов управления)             │
│  - Policy Engine (RBAC: Admin, Streamer, AI, Viewer)   │
│  - Token-Bucket Rate Limiter & Large Jump Cooldown     │
│  - World Coordinate Clamp & Blacklist Zones            │
│  - Стоп-Кран (Emergency Stop to Safe Spawn)            │
│  - SQLite Audit Logger (Хранение истории 7+ дней)      │
│  - Web Dashboard (HUD, Сетка третей, Safe Zone, Радар) │
└──────────────────────────┬─────────────────────────────┘
                           │ Outbound WebSocket (/ws/client)
┌──────────────────────────▼─────────────────────────────┐
│          MINECRAFT GPU CLIENT (Fabric 1.21.1+)         │
│  - Iris Shaders (Complementary / BSL / Solas)          │
│  - Sodium (High FPS)                                   │
│  - Freecam / Smooth Kinematic Spline Engine            │
│  - Auto-reconnect & Telemetry Feed (20 TPS)            │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 Быстрый старт

### 1. Установка и зависимости (уже выполнено в проекте)
```bash
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

### 2. Запуск ядра (Сервер и Веб-панель)
Дважды кликните `start_server.bat` или запустите:
```bash
.venv\Scripts\python run_server.py
```
Панель управления доступна по адресу: **http://localhost:8000**

### 3. Запуск симулятора GPU-клиента (для мгновенного теста)
Дважды кликните `start_mock_client.bat` или запустите:
```bash
.venv\Scripts\python run_mock_client.py
```
Симулятор подключится по WebSocket, начнет отдавать живую телеметрию (координаты, углы, FPS, шейдер) и реагировать на команды в браузере или через MCP.

---

## 🤖 Подключение к ИИ-агентам (MCP Server)

Сервер поддерживает стандарт **Model Context Protocol (v2)**.

### Конфигурация для Claude Desktop (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "minecraft-camera": {
      "command": "C:\\Users\\arina\\source\\repos\\AriSnowie97\\ai_operator_for_OBLIVION\\.venv\\Scripts\\python.exe",
      "args": [
        "C:\\Users\\arina\\source\\repos\\AriSnowie97\\ai_operator_for_OBLIVION\\core\\mcp_server.py"
      ]
    }
  }
}
```

### Доступные инструменты ИИ (MCP Tools):
- `get_camera_status()` — телеметрия в реальном времени.
- `move_camera(x, y, z, pitch, yaw, roll, duration, smoothing)` — плавный полет.
- `orbit_point(center_x, center_y, center_z, radius, speed, height)` — кинематографическая орбита.
- `set_fov(fov, duration)` — регулировка зума.
- `apply_preset(name, duration)` — переход на сохраненную точку.
- `save_preset(name, description)` — сохранение текущего ракурса.
- `list_presets()` — список всех сохраненных точек.
- `start_recording()` / `stop_recording()` — управление записью.
- `set_shader(shaderpack, profile)` — смена шейдерпака Iris.
- `emergency_stop(reason)` — **СТОП-КРАН**: мгновенный возврат на безопасный спавн и блокировка управления.
- `get_audit_logs(limit)` — просмотр журнала аудита.

---

## 🔒 Безопасность и правила (Policy Engine)

| Роль | Лимит команд | Доступ к шейдерам | Ограничения |
|---|---|---|---|
| **Admin** | Без лимита (100/сек) | Да | Снятие аварийной блокировки, обход черных списков |
| **Streamer** | 1 ком / 0.5 сек | Нет | Защита от резких прыжков, проверка зон |
| **AI Agent** | 5 ком / сек | Да | Координаты обрезаются (clamp), запрет закрытых зон |
| **Viewer** | 0 ком (Read-only) | Нет | Только просмотр телеметрии |

- **Черный список зон**: задается в `configs/blacklist_zones.json` (координаты приватных баз, админ-зон).
- **Стоп-кран**: возвращает камеру на `safe_spawn` и блокирует любые команды до разблокировки токеном админа (`configs/config.yaml`).
- **Аудит**: все вызовы сохраняются в `data/audit.db` с привязкой к роли и времени.

---

## ☁️ Деплой 24/7 на Railway

1. Репозиторий готов к развертыванию (содержит `Dockerfile` и `railway.json`).
2. В Railway создайте новый проект из этого репозитория.
3. Добавьте переменную окружения `SECRET_TOKEN` (токен администратора).
4. Ваша веб-панель и WebSocket-мост будут работать в облаке 24/7!
