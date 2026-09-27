# Minecraft GPU Client ↔ AI Operator Core Protocol

Данная спецификация описывает сетевой протокол взаимодействия между GPU-клиентом Minecraft (Fabric 1.21.1+ с Iris Shaders) и управляющим ядром (Railway / Local Core).

## Архитектура подключения
- Клиент инициирует **исходящее** WebSocket-соединение к адресу:
  `ws://<core-host>:<port>/ws/client`
- Преимущество: **Клиенту с GPU не нужен белый IP-адрес** — соединение работает через NAT, домашний роутер, ngrok или туннель Cloudflare.
- При разрыве соединения клиент автоматически повторяет попытку каждые 3 секунды.

---

## Пакеты от Ядра к Клиенту (Core -> Client)

### 1. Перемещение камеры (`MOVE`)
```json
{
  "type": "MOVE",
  "x": 124.5,
  "y": 85.0,
  "z": -312.0,
  "pitch": 20.0,
  "yaw": 90.0,
  "roll": 0.0,
  "duration": 2.0,
  "smoothing": "cinematic"
}
```

### 2. Орбита вокруг точки (`ORBIT`)
```json
{
  "type": "ORBIT",
  "center": [100.0, 75.0, -200.0],
  "radius": 20.0,
  "speed": 15.0,
  "height": 5.0
}
```

### 3. Изменение угла обзора / Зум (`SET_FOV`)
```json
{
  "type": "SET_FOV",
  "fov": 45.0,
  "duration": 0.5
}
```

### 4. Смена шейдерпака (`SET_SHADER`)
```json
{
  "type": "SET_SHADER",
  "shaderpack": "ComplementaryReimagined",
  "profile": "High"
}
```

### 5. Запись видео (`START_RECORDING` / `STOP_RECORDING`)
```json
{ "type": "START_RECORDING" }
```
```json
{ "type": "STOP_RECORDING" }
```

### 6. Стоп-кран (`EMERGENCY_STOP`)
```json
{
  "type": "EMERGENCY_STOP",
  "safe_position": {
    "x": 0.0,
    "y": 100.0,
    "z": 0.0,
    "pitch": 0.0,
    "yaw": 0.0,
    "roll": 0.0,
    "fov": 70.0
  }
}
```

---

## Пакеты от Клиента к Ядру (Client -> Core)

### Телеметрия в реальном времени (`TELEMETRY`)
Отправляется с частотой тиков (20 раз в секунду):
```json
{
  "type": "TELEMETRY",
  "x": 124.5,
  "y": 85.0,
  "z": -312.0,
  "pitch": 18.2,
  "yaw": 92.4,
  "roll": 0.0,
  "fov": 68.5,
  "fps": 115.0,
  "ping": 14.2,
  "is_recording": false,
  "shader": "ComplementaryReimagined"
}
```
