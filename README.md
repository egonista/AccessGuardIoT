# AccessGuard IoT

Aplicación móvil Android desarrollada en **Kotlin** y **Jetpack Compose** para la gestión, monitoreo y sincronización segura de control de acceso físico inteligente entre dispositivos en una red local Wi-Fi o Zona Wi-Fi (Hotspot).

## Arquitectura y Seguridad

- **Control de acceso basado en roles (`SUPERVISOR` y `OPERADOR`):**
  - **Supervisor:** Control completo de puerta, alarma, modos de acceso (`RFID`, `BOTONERA`, `RFID_PIN`, `LOCKDOWN`), gestión de credenciales RFID/PIN, administración de cuentas de operadores, rotación de contraseña de Supervisor, visualización/rotación de la llave de recuperación y auditoría completa de eventos.
  - **Operador:** Control operativo de apertura/cierre de puerta y alarma, consulta de política de seguridad vigente (solo lectura) y bitácora operativa filtrada de accesos (sin acceso a gestión de cuentas ni eventos administrativos).
- **Persistencia local (`acceso_iot.db` / SQLite v5):** Almacenamiento de usuarios con contraseñas protegidas mediante hash **SHA-256** con sal por usuario (`SecurityUtils.hashPassword`), estado del panel y bitácora de auditoría con trazabilidad exacta por usuario.
- **Políticas de credenciales (ISO/IEC 27400):** Validación de robustez para contraseñas de aplicación, identificadores RFID y PIN de botonera matricial 4x4, rechazando credenciales de fábrica, secuencias y patrones repetitivos.
- **Sincronización P2P cifrada (`WifiSocketManager`):** Autodescubrimiento en red local mediante `NsdManager` (mDNS `_accessguard._tcp`), UDP Broadcast enlazado por interfaz Wi-Fi/Hotspot (puerto `5001`), detección de gateways (`ConnectivityManager`), sondeo paralelo de subred y enlace TCP bidireccional persistente (puerto `5000`) con tramas cifradas en **AES-128-GCM** (`IV` de 12 bytes + tag de autenticación de 128 bits).
- **Usabilidad adaptable en Jetpack Compose:** Ajuste automático de márgenes ante teclado virtual (`consumeWindowInsets` + `imePadding()`), desplazamiento automático de campos enfocados (`BringIntoViewRequester`), acciones `Next`/`Done` y vista dedicada de recuperación.
- **Llave de recuperación sin secretos fijos en código:** En cada instalación se genera una **Llave de Recuperación y Autorización** aleatoria (`AG-XXXX-XXXX`) mediante `SecureRandom`, visible únicamente dentro del panel del Supervisor autenticado y con rotación manual o automática tras cada restablecimiento.

## Configuración y Compilación

### Requisitos
- Android Studio reciente (AGP `9.4.1`, Kotlin `2.2.10`, JDK 11+)
- SDK mínimo `24` (Android 7.0) / `compileSdk` y `targetSdk` `37`

### 1. Configurar la clave de cifrado local
Antes de compilar para dos dispositivos que deban sincronizarse entre sí, copia la plantilla de secretos:

```bash
cp secrets.properties.example secrets.properties
```

Edita `secrets.properties` (archivo ignorado por Git) y asigna exactamente **16 caracteres ASCII** (128 bits) en `ACCESSGUARD_AES_KEY`. **Ambos dispositivos deben compilarse con el mismo valor** para validar y descifrar las tramas AES-GCM.

> **Nota:** También puedes pasar la clave por la variable de entorno `ACCESSGUARD_AES_KEY`. Si no existe `secrets.properties` ni variable de entorno, `app/build.gradle.kts` genera una clave local derivada de la ruta del proyecto solo para facilitar la compilación individual en desarrollo.

### 2. Compilar y ejecutar pruebas

En Windows (PowerShell):

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

En Linux / macOS:

```bash
./gradlew testDebugUnitTest assembleDebug
```

### 3. Primer inicio recomendado
Al instalar por primera vez la aplicación se crean las cuentas iniciales de arranque (`supervisor` y `operador`). Como buena práctica, inicia sesión como Supervisor y:
1. Ve a **Seguridad de Supervisión y Recuperación → Cambiar mi contraseña de Supervisor** para reemplazar la clave inicial de arranque.
2. Verifica o rota la **Llave de Recuperación y Autorización** desde el mismo apartado si es necesario y actualiza los operadores desde **Gestión de Operadores**.
