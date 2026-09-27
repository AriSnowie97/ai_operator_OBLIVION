@echo off
chcp 65001 >nul
echo ============================================
echo   AI Operator — Сборка Fabric мода
echo ============================================
echo.

:: Проверяем наличие Java
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ОШИБКА] Java не найдена! Используем встроенную Java из Minecraft...
    set "JAVA_HOME=C:\Users\%USERNAME%\.minecraftx\jre\java-runtime-epsilon"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

cd /d "%~dp0"

echo [1/3] Скачиваем Gradle Wrapper...
if not exist "gradlew.bat" (
    curl -L -o gradle-wrapper.jar "https://services.gradle.org/distributions/gradle-8.8-bin.zip" 2>nul
    :: Создаём gradlew вручную если нет
    echo @rem Gradle wrapper > gradlew.bat
    echo @java -jar gradle\wrapper\gradle-wrapper.jar %%* >> gradlew.bat
)

echo [2/3] Собираем мод (это займёт 2-5 минут в первый раз)...
call gradlew.bat build --info 2>&1

if %errorlevel% neq 0 (
    echo.
    echo [ОШИБКА] Сборка не удалась. Проверьте вывод выше.
    pause
    exit /b 1
)

echo.
echo [3/3] Копируем мод в папку OBVILION...
set "MODS_DIR=C:\Users\%USERNAME%\.minecraftx\instances\OBVILION\mods"
set "MOD_JAR=build\libs\ai-operator-client-1.0.0.jar"

if exist "%MOD_JAR%" (
    :: Удаляем старую версию если есть
    del /f /q "%MODS_DIR%\ai-operator-client-*.jar" 2>nul
    copy "%MOD_JAR%" "%MODS_DIR%\" >nul
    echo [OK] Мод скопирован в: %MODS_DIR%
) else (
    echo [ОШИБКА] JAR файл не найден: %MOD_JAR%
)

echo.
echo ============================================
echo   Готово! Запускай OBVILION в TLauncher.
echo   Мод подключится к: ws://localhost:8765/ws/client
echo   Для смены адреса: .minecraftx\instances\OBVILION\config\aioperator.properties
echo ============================================
pause
