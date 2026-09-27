let telemetryWs = null;
let currentStatus = {
  position: { x: 0, y: 100, z: 0, pitch: 15, yaw: 0, roll: 0, fov: 70 },
  mode: 'freecam',
  is_recording: false,
  recording_time_sec: 0,
  active_shader: 'ComplementaryReimagined',
  client_connected: false,
  emergency_lock: false,
  fps: 60
};

const canvas = document.getElementById('viewport-canvas');
const ctx = canvas.getContext('2d');

function resizeCanvas() {
  const container = canvas.parentElement;
  canvas.width = container.clientWidth * window.devicePixelRatio;
  canvas.height = container.clientHeight * window.devicePixelRatio;
}
window.addEventListener('resize', resizeCanvas);
resizeCanvas();

function drawHorizonRadar() {
  const w = canvas.width;
  const h = canvas.height;
  const cx = w / 2;
  const cy = h / 2;
  
  ctx.clearRect(0, 0, w, h);

  const pos = currentStatus.position || { x: 0, y: 100, z: 0, pitch: 0, yaw: 0, roll: 0 };
  const pitchRad = (pos.pitch * Math.PI) / 180;
  const rollRad = (pos.roll * Math.PI) / 180;
  const yaw = pos.yaw;

  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(-rollRad);

  const pitchOffset = pos.pitch * 3.5;

  const grad = ctx.createLinearGradient(0, -h, 0, h);
  grad.addColorStop(0, 'rgba(0, 120, 255, 0.08)');
  grad.addColorStop(0.5, 'rgba(0, 0, 0, 0.4)');
  grad.addColorStop(1, 'rgba(16, 185, 129, 0.05)');
  ctx.fillStyle = grad;
  ctx.fillRect(-w, -h * 2, w * 2, h * 4);

  ctx.beginPath();
  ctx.strokeStyle = '#00f0ff';
  ctx.lineWidth = 2 * window.devicePixelRatio;
  ctx.moveTo(-w, pitchOffset);
  ctx.lineTo(w, pitchOffset);
  ctx.stroke();

  ctx.strokeStyle = 'rgba(0, 240, 255, 0.35)';
  ctx.lineWidth = 1 * window.devicePixelRatio;
  ctx.fillStyle = 'rgba(0, 240, 255, 0.7)';
  ctx.font = `${11 * window.devicePixelRatio}px 'Outfit', sans-serif`;
  ctx.textAlign = 'right';

  for (let deg = -60; deg <= 60; deg += 15) {
    if (deg === 0) continue;
    const yLadder = pitchOffset - (deg * 3.5);
    const lineLen = deg % 30 === 0 ? 60 * window.devicePixelRatio : 35 * window.devicePixelRatio;

    ctx.beginPath();
    ctx.moveTo(-lineLen, yLadder);
    ctx.lineTo(lineLen, yLadder);
    ctx.stroke();

    ctx.fillText(`${deg > 0 ? '+' : ''}${deg}°`, -lineLen - 8, yLadder + 4);
  }

  ctx.restore();

  ctx.beginPath();
  ctx.strokeStyle = '#00f0ff';
  ctx.lineWidth = 2 * window.devicePixelRatio;
  ctx.arc(cx, cy, 14 * window.devicePixelRatio, 0, Math.PI * 2);
  ctx.moveTo(cx - 24 * window.devicePixelRatio, cy);
  ctx.lineTo(cx - 14 * window.devicePixelRatio, cy);
  ctx.moveTo(cx + 14 * window.devicePixelRatio, cy);
  ctx.lineTo(cx + 24 * window.devicePixelRatio, cy);
  ctx.moveTo(cx, cy - 24 * window.devicePixelRatio);
  ctx.lineTo(cx, cy - 14 * window.devicePixelRatio);
  ctx.moveTo(cx, cy + 14 * window.devicePixelRatio);
  ctx.lineTo(cx, cy + 24 * window.devicePixelRatio);
  ctx.stroke();

  ctx.fillStyle = 'rgba(10, 15, 25, 0.85)';
  ctx.fillRect(cx - 160 * window.devicePixelRatio, 12, 320 * window.devicePixelRatio, 36 * window.devicePixelRatio);
  ctx.strokeStyle = 'rgba(0, 240, 255, 0.3)';
  ctx.strokeRect(cx - 160 * window.devicePixelRatio, 12, 320 * window.devicePixelRatio, 36 * window.devicePixelRatio);

  ctx.fillStyle = '#fff';
  ctx.font = `bold ${14 * window.devicePixelRatio}px 'Outfit', sans-serif`;
  ctx.textAlign = 'center';
  const heading = ((yaw % 360) + 360) % 360;
  ctx.fillText(`HEADING: ${Math.round(heading)}°`, cx, 36 * window.devicePixelRatio);

  requestAnimationFrame(drawHorizonRadar);
}
requestAnimationFrame(drawHorizonRadar);

function initWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${window.location.host}/ws/telemetry`;

  telemetryWs = new WebSocket(wsUrl);

  telemetryWs.onopen = () => {
    console.log('[Telemetry] Connected to Core telemetry channel');
  };

  telemetryWs.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      updateTelemetryUI(data);
    } catch (e) {
      console.error('[Telemetry] Parse error', e);
    }
  };

  telemetryWs.onclose = () => {
    console.warn('[Telemetry] Connection lost. Reconnecting in 2s...');
    setTimeout(initWebSocket, 2000);
  };
}

function updateTelemetryUI(status) {
  currentStatus = status;

  const clientBadge = document.getElementById('badge-client');
  const clientLabel = document.getElementById('label-client');
  if (status.client_connected) {
    clientBadge.className = 'status-badge online';
    clientLabel.textContent = 'GPU Client Connected';
  } else {
    clientBadge.className = 'status-badge offline';
    clientLabel.textContent = 'GPU Client Disconnected';
  }

  document.getElementById('val-shader').textContent = status.active_shader || 'None';
  document.getElementById('val-mode').textContent = (status.mode || 'FREECAM').toUpperCase();

  const recBadge = document.getElementById('badge-recording');
  const recToggleBtn = document.getElementById('btn-record-toggle');
  if (status.is_recording) {
    recBadge.style.display = 'inline-flex';
    const mins = Math.floor((status.recording_time_sec || 0) / 60).toString().padStart(2, '0');
    const secs = Math.floor((status.recording_time_sec || 0) % 60).toString().padStart(2, '0');
    document.getElementById('val-rec-timer').textContent = `${mins}:${secs}`;
    recToggleBtn.textContent = '⏹ Остановить запись';
    recToggleBtn.classList.add('active');
  } else {
    recBadge.style.display = 'none';
    recToggleBtn.textContent = '⏺ Начать запись';
    recToggleBtn.classList.remove('active');
  }

  const emerBanner = document.getElementById('emergency-banner');
  if (status.emergency_lock) {
    emerBanner.classList.add('visible');
  } else {
    emerBanner.classList.remove('visible');
  }

  const pos = status.position || {};
  document.getElementById('val-x').textContent = (pos.x || 0).toFixed(1);
  document.getElementById('val-y').textContent = (pos.y || 0).toFixed(1);
  document.getElementById('val-z').textContent = (pos.z || 0).toFixed(1);
  document.getElementById('val-pitch').textContent = `${(pos.pitch || 0).toFixed(1)}°`;
  document.getElementById('val-yaw').textContent = `${(pos.yaw || 0).toFixed(1)}°`;
  document.getElementById('val-roll').textContent = `${(pos.roll || 0).toFixed(1)}°`;
  document.getElementById('val-fov').textContent = `${(pos.fov || 70).toFixed(1)}°`;
  document.getElementById('val-fps').textContent = Math.round(status.fps || 60);

  const fovSlider = document.getElementById('slider-fov');
  if (!fovSlider.matches(':active')) {
    fovSlider.value = Math.round(pos.fov || 70);
    document.getElementById('label-slider-fov').textContent = `${fovSlider.value}°`;
  }
}

async function loadPresets() {
  try {
    const res = await fetch('/api/presets');
    const presets = await res.json();
    const container = document.getElementById('presets-container');
    container.innerHTML = '';

    for (const [name, p] of Object.entries(presets)) {
      const card = document.createElement('div');
      card.className = `preset-card ${currentStatus.active_preset === name ? 'active' : ''}`;
      card.innerHTML = `
        <span class="preset-title">${p.name}</span>
        <span class="preset-desc">${p.description || `(${p.x}, ${p.y}, ${p.z})`}</span>
      `;
      card.onclick = () => applyPreset(name);
      container.appendChild(card);
    }
  } catch (e) {
    console.error('Error loading presets:', e);
  }
}

async function applyPreset(name) {
  const duration = parseFloat(document.getElementById('slider-duration').value);
  try {
    const res = await fetch('/api/presets/apply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Role': 'streamer' },
      body: JSON.stringify({ name, duration })
    });
    const data = await res.json();
    if (!res.ok) alert(data.detail || 'Error applying preset');
    loadPresets();
  } catch (e) {
    console.error(e);
  }
}

async function loadAuditLogs() {
  try {
    const res = await fetch('/api/logs?limit=15');
    const logs = await res.json();
    const container = document.getElementById('audit-logs');
    container.innerHTML = '';

    logs.forEach(log => {
      const item = document.createElement('div');
      item.className = 'audit-item';
      const roleClass = log.caller_role === 'ai_agent' ? 'ai' : log.caller_role;
      item.innerHTML = `
        <div>
          <span class="audit-role ${roleClass}">${log.caller_role}</span>
          <span class="audit-action">${log.action}</span>
        </div>
        <span class="audit-status ${log.status}">${log.status}</span>
      `;
      container.appendChild(item);
    });
  } catch (e) {
    console.error('Error loading logs:', e);
  }
}

document.getElementById('toggle-grid').addEventListener('click', function() {
  const grid = document.getElementById('rule-of-thirds');
  grid.classList.toggle('hidden');
  this.classList.toggle('active', !grid.classList.contains('hidden'));
});

document.getElementById('toggle-safe').addEventListener('click', function() {
  const safe = document.getElementById('safe-zone');
  safe.classList.toggle('hidden');
  this.classList.toggle('active', !safe.classList.contains('hidden'));
});

document.getElementById('slider-fov').addEventListener('input', function() {
  document.getElementById('label-slider-fov').textContent = `${this.value}°`;
});
document.getElementById('slider-fov').addEventListener('change', async function() {
  await fetch('/api/fov', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Role': 'streamer' },
    body: JSON.stringify({ fov: parseFloat(this.value), duration: 0.4 })
  });
});

document.getElementById('slider-duration').addEventListener('input', function() {
  document.getElementById('label-slider-duration').textContent = `${this.value}s`;
});

document.getElementById('slider-roll').addEventListener('input', function() {
  document.getElementById('label-slider-roll').textContent = `${this.value}°`;
});
document.getElementById('slider-roll').addEventListener('change', async function() {
  const pos = currentStatus.position || {};
  await fetch('/api/move', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Role': 'streamer' },
    body: JSON.stringify({
      x: pos.x, y: pos.y, z: pos.z,
      pitch: pos.pitch, yaw: pos.yaw, roll: parseFloat(this.value),
      duration: 0.5
    })
  });
});

document.getElementById('btn-record-toggle').addEventListener('click', async function() {
  const endpoint = currentStatus.is_recording ? '/api/recording/stop' : '/api/recording/start';
  await fetch(endpoint, { method: 'POST', headers: { 'X-Role': 'streamer' } });
});

document.getElementById('btn-return-player').addEventListener('click', async function() {
  await fetch('/api/camera/return_to_player', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Role': 'streamer' }
  });
});

document.getElementById('btn-orbit-current').addEventListener('click', async function() {
  const pos = currentStatus.position || {};
  await fetch('/api/orbit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Role': 'streamer' },
    body: JSON.stringify({
      center_x: pos.x, center_y: pos.y, center_z: pos.z,
      radius: 18.0, speed: 20.0, height_offset: 6.0
    })
  });
});

document.getElementById('btn-save-preset').addEventListener('click', async function() {
  const name = prompt('Введите название для нового пресета камеры:');
  if (!name) return;
  const desc = prompt('Краткое описание точки:');
  const res = await fetch('/api/presets/save', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Role': 'streamer' },
    body: JSON.stringify({ name, description: desc || '' })
  });
  if (res.ok) {
    alert(`Пресет "${name}" успешно сохранен!`);
    loadPresets();
  }
});

document.getElementById('btn-emergency-stop').addEventListener('click', async function() {
  if (confirm('Активировать СТОП-КРАН? Камера немедленно вернется на безопасный спавн, а управление будет заблокировано.')) {
    await fetch('/api/emergency_stop', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Role': 'streamer' },
      body: JSON.stringify({ reason: 'Нажата кнопка СТОП-КРАН в веб-панели' })
    });
    loadAuditLogs();
  }
});

document.getElementById('btn-emergency-reset').addEventListener('click', async function() {
  try {
    const res = await fetch('/api/emergency_reset', {
      method: 'POST',
      headers: { 
        'Content-Type': 'application/json',
        'X-Auth-Token': 'obl-operator-secret-2026'
      }
    });
    const data = await res.json();
    if (res.ok) {
      document.getElementById('emergency-banner').classList.remove('visible');
      alert('Стоп-кран успешно снят! Управление разблокировано.');
    } else {
      alert(data.detail || 'Ошибка разблокировки');
    }
  } catch (err) {
    alert('Ошибка соединения с сервером при разблокировке');
  }
  loadAuditLogs();
});

initWebSocket();
loadPresets();
loadAuditLogs();
setInterval(loadAuditLogs, 5000);
