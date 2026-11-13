package com.example.accessguardiot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Modelo de datos para cada registro de la bitácora SQLite
data class EventoBitacora(
    val id: Int,
    val fechaHora: String,
    val usuarioRol: String,
    val detalle: String
)

// Modelo de datos para cuentas con rol OPERADOR registradas en SQLite
data class UsuarioOperador(
    val username: String,
    val password: String
)

// Modelo para persistir el último estado del panel en SQLite al cerrar/abrir la app
data class EstadoGuardadoSQLite(
    val puertaAbierta: Boolean,
    val anguloServo: Int,
    val buzzerActivo: Boolean,
    val modoAcceso: String,
    val uidRfid: String,
    val pinBotonera: String,
    val timestampModificacion: Long,
    val ultimaIpDestino: String,
    val timestampOperadores: Long = 0L,
    val timestampCodigoAuth: Long = 0L
)

/**
 * Base de datos local SQLite nativa (acceso_iot.db)
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "acceso_iot.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Tabla de usuarios con roles diferenciados (SUPERVISOR vs OPERADOR) y clave hasheada en SHA-256
        db.execSQL(
            """
            CREATE TABLE usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE,
                password TEXT,
                rol TEXT
            )
            """.trimIndent()
        )

        // 2. Tabla de bitácora de movimientos IoT
        db.execSQL(
            """
            CREATE TABLE bitacora_eventos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fecha_hora TEXT,
                usuario_rol TEXT,
                detalle TEXT
            )
            """.trimIndent()
        )

        // 3. Tabla de estado persistente del panel (para recuperar estado al cerrar y reabrir la app)
        crearTablaEstadoPanel(db)

        // 4. Insertar cuentas iniciales con contraseñas robustas ISO/IEC 27400 hasheadas en SHA-256
        insertarUsuarioInicial(db, "supervisor", "Sup#2026IoT", "SUPERVISOR")
        insertarUsuarioInicial(db, "operador", "Ope#2026Lab", "OPERADOR")

        // 5. Evento inicial de auditoría
        val horaInicial = obtenerHoraActual()
        db.execSQL(
            "INSERT INTO bitacora_eventos (fecha_hora, usuario_rol, detalle) VALUES ('$horaInicial', 'SISTEMA', 'Servicio de control de acceso inicializado')"
        )
    }

    private fun insertarUsuarioInicial(db: SQLiteDatabase, username: String, passwordPlana: String, rol: String) {
        val valores = ContentValues().apply {
            put("username", username)
            put("password", SecurityUtils.hashPassword(username, passwordPlana))
            put("rol", rol)
        }
        db.insertWithOnConflict("usuarios", null, valores, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun crearTablaEstadoPanel(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS estado_panel (
                id INTEGER PRIMARY KEY,
                puerta_abierta INTEGER,
                angulo_servo INTEGER,
                buzzer_activo INTEGER,
                modo_acceso TEXT,
                uid_rfid TEXT,
                pin_botonera TEXT,
                timestamp_modificacion INTEGER,
                ultima_ip TEXT,
                timestamp_operadores INTEGER DEFAULT 0,
                codigo_auth_hash TEXT DEFAULT '',
                codigo_auth_cifrado TEXT DEFAULT '',
                timestamp_codigo_auth INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO estado_panel 
            (id, puerta_abierta, angulo_servo, buzzer_activo, modo_acceso, uid_rfid, pin_botonera, timestamp_modificacion, ultima_ip, timestamp_operadores)
            VALUES (1, 0, 0, 0, 'RFID', 'A4-9B-3C-12', '4829', 0, '192.168.43.1', 0)
            """.trimIndent()
        )
        asegurarCodigoAutorizacion(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            crearTablaEstadoPanel(db)
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE estado_panel ADD COLUMN timestamp_operadores INTEGER DEFAULT 0")
            } catch (_: Exception) {
            }
        }
        if (oldVersion < 4) {
            migrarPasswordsASha256(db)
        }
        if (oldVersion < 5) {
            for (stmt in listOf(
                "ALTER TABLE estado_panel ADD COLUMN codigo_auth_hash TEXT DEFAULT ''",
                "ALTER TABLE estado_panel ADD COLUMN codigo_auth_cifrado TEXT DEFAULT ''",
                "ALTER TABLE estado_panel ADD COLUMN timestamp_codigo_auth INTEGER DEFAULT 0"
            )) {
                try {
                    db.execSQL(stmt)
                } catch (_: Exception) {
                }
            }
            asegurarCodigoAutorizacion(db)
        }
    }

    private fun asegurarCodigoAutorizacion(db: SQLiteDatabase) {
        var hashActual = ""
        var cifradoActual = ""
        val cursor = db.rawQuery(
            "SELECT codigo_auth_hash, codigo_auth_cifrado FROM estado_panel WHERE id = 1",
            null
        )
        if (cursor.moveToFirst()) {
            hashActual = cursor.getString(0) ?: ""
            cifradoActual = cursor.getString(1) ?: ""
        }
        cursor.close()

        val cifradoLegible = SecurityUtils.descifrarAES(cifradoActual)
        if (!SecurityUtils.esHashSha256(hashActual) || cifradoLegible.isNullOrBlank()) {
            val nuevaLlave = SecurityUtils.generarCodigoAutorizacionAleatorio()
            val valores = ContentValues().apply {
                put("codigo_auth_hash", SecurityUtils.hashCodigoAutorizacion(nuevaLlave))
                put("codigo_auth_cifrado", SecurityUtils.cifrarAES(nuevaLlave))
                put("timestamp_codigo_auth", 1L)
            }
            db.update("estado_panel", valores, "id = 1", null)
        }
    }

    /** Convierte cualquier contraseña previa en texto plano dentro de SQLite a su hash SHA-256. */
    private fun migrarPasswordsASha256(db: SQLiteDatabase) {
        val pendientes = mutableListOf<Triple<Int, String, String>>()
        val cursor = db.rawQuery("SELECT id, username, password FROM usuarios", null)
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(0)
                val user = cursor.getString(1) ?: ""
                val pass = cursor.getString(2) ?: ""
                if (user.isNotBlank() && pass.isNotBlank() && !SecurityUtils.esHashSha256(pass)) {
                    pendientes.add(Triple(id, user, pass))
                }
            } while (cursor.moveToNext())
        }
        cursor.close()

        for ((id, user, pass) in pendientes) {
            val valores = ContentValues().apply {
                put("password", SecurityUtils.hashPassword(user, pass))
            }
            db.update("usuarios", valores, "id = ?", arrayOf(id.toString()))
        }
    }

    /**
     * Verifica las credenciales en SQLite comparando contra el hash SHA-256 almacenado
     * y devuelve un Par(rol, nombreUsuarioExacto), o null si las credenciales son incorrectas.
     */
    fun validarUsuarioConNombre(username: String, password: String): Pair<String, String>? {
        val userLimpio = username.trim()
        val passLimpia = password.trim()
        if (userLimpio.isBlank() || passLimpia.isBlank()) return null

        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT id, username, password, rol FROM usuarios WHERE LOWER(username) = LOWER(?) LIMIT 1",
            arrayOf(userLimpio)
        )
        var rolEncontrado: String? = null
        var idEncontrado = -1
        var userReal = userLimpio
        var passGuardada = ""
        if (cursor.moveToFirst()) {
            idEncontrado = cursor.getInt(0)
            userReal = cursor.getString(1) ?: userLimpio
            passGuardada = cursor.getString(2) ?: ""
            val rol = cursor.getString(3)
            if (SecurityUtils.verificarPassword(userReal, passLimpia, passGuardada)) {
                rolEncontrado = rol
            }
        }
        cursor.close()

        // Migración automática por seguridad si existía una fila heredada sin hashear
        if (rolEncontrado != null && idEncontrado != -1 && !SecurityUtils.esHashSha256(passGuardada)) {
            val valores = ContentValues().apply {
                put("password", SecurityUtils.hashPassword(userReal, passLimpia))
            }
            db.update("usuarios", valores, "id = ?", arrayOf(idEncontrado.toString()))
        }
        return rolEncontrado?.let { it to userReal }
    }

    /**
     * Verifica las credenciales en SQLite comparando contra el hash SHA-256 almacenado
     * y devuelve el rol ("SUPERVISOR" u "OPERADOR"), o null si las credenciales son incorrectas.
     */
    fun validarUsuario(username: String, password: String): String? {
        return validarUsuarioConNombre(username, password)?.first
    }

    /**
     * Registra un nuevo usuario exclusivamente con el rol "OPERADOR" en SQLite (almacenado con hash SHA-256).
     * Retorna true si se creó con éxito, o false si el nombre de usuario ya existe.
     */
    fun registrarOperador(username: String, password: String): Boolean {
        val userLimpio = username.trim()
        val passLimpia = password.trim()
        if (userLimpio.isBlank() || passLimpia.isBlank()) return false

        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM usuarios WHERE LOWER(username) = LOWER(?)",
            arrayOf(userLimpio)
        )
        var existe = false
        if (cursor.moveToFirst()) {
            existe = cursor.getInt(0) > 0
        }
        cursor.close()

        if (existe) return false

        val valores = ContentValues().apply {
            put("username", userLimpio)
            put("password", SecurityUtils.hashPassword(userLimpio, passLimpia))
            put("rol", "OPERADOR")
        }
        return db.insert("usuarios", null, valores) != -1L
    }

    /**
     * CRUD (Update): Actualiza la contraseña (hash SHA-256) de un usuario con rol "OPERADOR" en SQLite.
     * Retorna true si el operador existía y su clave fue actualizada.
     */
    fun actualizarPasswordOperador(username: String, nuevaPassword: String): Boolean {
        val userLimpio = username.trim()
        val passLimpia = nuevaPassword.trim()
        if (userLimpio.isBlank() || passLimpia.isBlank()) return false

        val db = writableDatabase
        val valores = ContentValues().apply {
            put("password", SecurityUtils.hashPassword(userLimpio, passLimpia))
        }
        val filasAfectadas = db.update(
            "usuarios",
            valores,
            "LOWER(username) = LOWER(?) AND rol = 'OPERADOR'",
            arrayOf(userLimpio)
        )
        return filasAfectadas > 0
    }

    /**
     * CRUD (Delete): Elimina una cuenta de usuario con rol "OPERADOR" en SQLite.
     * Retorna true si fue eliminado con éxito.
     */
    fun eliminarOperador(username: String): Boolean {
        val userLimpio = username.trim()
        if (userLimpio.isBlank()) return false

        val db = writableDatabase
        val filasBorradas = db.delete(
            "usuarios",
            "LOWER(username) = LOWER(?) AND rol = 'OPERADOR'",
            arrayOf(userLimpio)
        )
        return filasBorradas > 0
    }

    /** Verifica si existe una cuenta con ese nombre de usuario en SQLite. */
    fun existeUsuario(username: String): Boolean {
        val userLimpio = username.trim()
        if (userLimpio.isBlank()) return false
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM usuarios WHERE LOWER(username) = LOWER(?)",
            arrayOf(userLimpio)
        )
        var existe = false
        if (cursor.moveToFirst()) {
            existe = cursor.getInt(0) > 0
        }
        cursor.close()
        return existe
    }

    /** Obtiene el hash SHA-256 vigente de la contraseña del Supervisor en SQLite para sincronizar. */
    fun obtenerPasswordSupervisor(): String {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT password FROM usuarios WHERE rol = 'SUPERVISOR' LIMIT 1",
            null
        )
        var pass = ""
        if (cursor.moveToFirst()) {
            pass = cursor.getString(0) ?: ""
        }
        cursor.close()
        return pass
    }

    /** Verifica si el código ingresado coincide con la contraseña vigente del Supervisor en SQLite. */
    fun esCodigoSupervisorValido(codigo: String): Boolean {
        val codigoLimpio = codigo.trim()
        if (codigoLimpio.isBlank()) return false
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT username, password FROM usuarios WHERE rol = 'SUPERVISOR' LIMIT 1",
            null
        )
        var valido = false
        if (cursor.moveToFirst()) {
            val userSup = cursor.getString(0) ?: ""
            val hashSup = cursor.getString(1) ?: ""
            valido = SecurityUtils.verificarPassword(userSup, codigoLimpio, hashSup)
        }
        cursor.close()
        return valido
    }

    /** Verifica si el código ingresado coincide con el hash SHA-256 de la Llave de Recuperación vigente. */
    fun esCodigoAutorizacionValido(codigo: String): Boolean {
        val db = writableDatabase
        crearTablaEstadoPanel(db)
        val cursor = db.rawQuery(
            "SELECT codigo_auth_hash FROM estado_panel WHERE id = 1",
            null
        )
        var hashGuardado = ""
        if (cursor.moveToFirst()) {
            hashGuardado = cursor.getString(0) ?: ""
        }
        cursor.close()
        return SecurityUtils.verificarCodigoAutorizacion(codigo, hashGuardado)
    }

    /** Devuelve la Llave de Recuperación/Autorización actual para mostrarla únicamente al Supervisor autenticado. */
    fun obtenerCodigoAutorizacionVisible(): String {
        val db = writableDatabase
        crearTablaEstadoPanel(db)
        val cursor = db.rawQuery(
            "SELECT codigo_auth_cifrado FROM estado_panel WHERE id = 1",
            null
        )
        var cifrado = ""
        if (cursor.moveToFirst()) {
            cifrado = cursor.getString(0) ?: ""
        }
        cursor.close()
        val plano = SecurityUtils.descifrarAES(cifrado)
        return if (!plano.isNullOrBlank()) plano else rotarCodigoAutorizacion()
    }

    /** Genera y guarda una nueva Llave de Recuperación aleatoria con su hash SHA-256. */
    fun rotarCodigoAutorizacion(): String {
        val db = writableDatabase
        crearTablaEstadoPanel(db)
        val nuevaLlave = SecurityUtils.generarCodigoAutorizacionAleatorio()
        val nuevoHash = SecurityUtils.hashCodigoAutorizacion(nuevaLlave)
        val nuevoCifrado = SecurityUtils.cifrarAES(nuevaLlave)
        val ahora = System.currentTimeMillis()
        val valores = ContentValues().apply {
            put("codigo_auth_hash", nuevoHash)
            put("codigo_auth_cifrado", nuevoCifrado)
            put("timestamp_codigo_auth", ahora)
        }
        db.update("estado_panel", valores, "id = 1", null)
        return nuevaLlave
    }

    /** Devuelve (hash, cifrado, timestamp) de la Llave de Recuperación para sincronización cifrada entre dispositivos. */
    fun obtenerDatosSyncCodigoAuth(): Triple<String, String, Long> {
        val db = writableDatabase
        crearTablaEstadoPanel(db)
        val cursor = db.rawQuery(
            "SELECT codigo_auth_hash, codigo_auth_cifrado, timestamp_codigo_auth FROM estado_panel WHERE id = 1",
            null
        )
        var triple = Triple("", "", 0L)
        if (cursor.moveToFirst()) {
            triple = Triple(
                cursor.getString(0) ?: "",
                cursor.getString(1) ?: "",
                cursor.getLong(2)
            )
        }
        cursor.close()
        return triple
    }

    /** Aplica una Llave de Recuperación sincronizada desde el otro terminal. */
    fun sincronizarCodigoAuth(hash: String, cifrado: String, timestamp: Long) {
        if (!SecurityUtils.esHashSha256(hash) || cifrado.isBlank()) return
        val db = writableDatabase
        crearTablaEstadoPanel(db)
        val valores = ContentValues().apply {
            put("codigo_auth_hash", hash.trim())
            put("codigo_auth_cifrado", cifrado.trim())
            put("timestamp_codigo_auth", timestamp)
        }
        db.update("estado_panel", valores, "id = 1", null)
    }

    /**
     * Cambia la contraseña de la cuenta SUPERVISOR validando primero su contraseña vigente en SHA-256.
     * Retorna null si se actualizó con éxito, o el mensaje de error si falló.
     */
    fun cambiarPasswordSupervisor(passwordActual: String, nuevaPassword: String): String? {
        val actualLimpia = passwordActual.trim()
        val nuevaLimpia = nuevaPassword.trim()
        if (actualLimpia.isBlank() || nuevaLimpia.isBlank()) {
            return "Completa la contraseña actual y la nueva contraseña."
        }

        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT username, password FROM usuarios WHERE rol = 'SUPERVISOR' LIMIT 1",
            null
        )
        var userSup = ""
        var hashActual = ""
        if (cursor.moveToFirst()) {
            userSup = cursor.getString(0) ?: ""
            hashActual = cursor.getString(1) ?: ""
        }
        cursor.close()

        if (userSup.isBlank()) return "Cuenta de Supervisor no encontrada."
        if (!SecurityUtils.verificarPassword(userSup, actualLimpia, hashActual)) {
            return "La contraseña actual del Supervisor es incorrecta."
        }

        val errorRobustez = SecurityUtils.validarPasswordRobusta(userSup, nuevaLimpia)
        if (errorRobustez != null) return errorRobustez

        val valores = ContentValues().apply {
            put("password", SecurityUtils.hashPassword(userSup, nuevaLimpia))
        }
        val filas = db.update("usuarios", valores, "rol = 'SUPERVISOR'", null)
        return if (filas > 0) null else "No se pudo actualizar la contraseña."
    }

    /**
     * Actualiza la contraseña de cualquier cuenta existente (SUPERVISOR u OPERADOR) desde Recuperación,
     * guardándola hasheada con SHA-256. Retorna el rol actualizado o null si el usuario no existe.
     */
    fun actualizarPasswordUsuario(username: String, nuevaPassword: String): String? {
        val userLimpio = username.trim()
        val passLimpia = nuevaPassword.trim()
        if (userLimpio.isBlank() || passLimpia.isBlank()) return null

        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT username, rol FROM usuarios WHERE LOWER(username) = LOWER(?) LIMIT 1",
            arrayOf(userLimpio)
        )
        var userReal = userLimpio
        var rolEncontrado: String? = null
        if (cursor.moveToFirst()) {
            userReal = cursor.getString(0) ?: userLimpio
            rolEncontrado = cursor.getString(1)
        }
        cursor.close()
        if (rolEncontrado == null) return null

        val valores = ContentValues().apply {
            put("password", SecurityUtils.hashPassword(userReal, passLimpia))
        }
        val filas = db.update(
            "usuarios",
            valores,
            "LOWER(username) = LOWER(?)",
            arrayOf(userReal)
        )
        return if (filas > 0) rolEncontrado else null
    }

    /** Sincroniza el hash SHA-256 de la clave del Supervisor recibido por Wi-Fi desde el otro dispositivo. */
    fun sincronizarPasswordSupervisor(nuevaPasswordOHash: String) {
        val valorLimpio = nuevaPasswordOHash.trim()
        if (valorLimpio.isBlank()) return
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT username FROM usuarios WHERE rol = 'SUPERVISOR' LIMIT 1",
            null
        )
        var userSup = "supervisor"
        if (cursor.moveToFirst()) {
            userSup = cursor.getString(0) ?: "supervisor"
        }
        cursor.close()

        val valores = ContentValues().apply {
            put("password", SecurityUtils.normalizarHashPassword(userSup, valorLimpio))
        }
        db.update("usuarios", valores, "rol = 'SUPERVISOR'", null)
    }

    /**
     * Sincroniza la tabla local de Operadores en SQLite con la lista de hashes SHA-256 recibida
     * desde el otro dispositivo (reflejando altas, cambios de contraseña y bajas hechas por el Supervisor).
     */
    fun sincronizarListaOperadores(operadoresRemotos: List<UsuarioOperador>) {
        if (operadoresRemotos.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("usuarios", "rol = 'OPERADOR'", null)
            for (op in operadoresRemotos) {
                val userLimpio = op.username.trim()
                val hashSeguro = SecurityUtils.normalizarHashPassword(userLimpio, op.password)
                val valores = ContentValues().apply {
                    put("username", userLimpio)
                    put("password", hashSeguro)
                    put("rol", "OPERADOR")
                }
                db.insertWithOnConflict("usuarios", null, valores, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Obtiene la lista de usuarios con rol OPERADOR registrados en SQLite.
     */
    fun obtenerOperadores(): List<UsuarioOperador> {
        val lista = mutableListOf<UsuarioOperador>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT username, password FROM usuarios WHERE rol = 'OPERADOR' ORDER BY id ASC",
            null
        )
        if (cursor.moveToFirst()) {
            do {
                lista.add(
                    UsuarioOperador(
                        username = cursor.getString(0),
                        password = cursor.getString(1)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return lista
    }

    /**
     * Inserta un nuevo movimiento en la tabla bitacora_eventos.
     */
    fun insertarEvento(usuarioRol: String, detalle: String, fechaHora: String = obtenerHoraActual()) {
        val db = writableDatabase
        val valores = ContentValues().apply {
            put("fecha_hora", fechaHora)
            put("usuario_rol", usuarioRol)
            put("detalle", detalle)
        }
        db.insert("bitacora_eventos", null, valores)
    }

    /**
     * Inserta un evento recibido por sincronización Wi-Fi solo si aún no existe en SQLite,
     * evitando duplicados cuando una app se cierra y se vuelve a abrir.
     */
    fun insertarEventoSiNoExiste(fechaHora: String, usuarioRol: String, detalle: String): Boolean {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM bitacora_eventos WHERE fecha_hora = ? AND usuario_rol = ? AND detalle = ?",
            arrayOf(fechaHora, usuarioRol, detalle)
        )
        var existe = false
        if (cursor.moveToFirst()) {
            existe = cursor.getInt(0) > 0
        }
        cursor.close()

        if (!existe) {
            val valores = ContentValues().apply {
                put("fecha_hora", fechaHora)
                put("usuario_rol", usuarioRol)
                put("detalle", detalle)
            }
            db.insert("bitacora_eventos", null, valores)
            return true
        }
        return false
    }

    /**
     * Obtiene todos los movimientos del más reciente al más antiguo (para el LazyColumn).
     */
    fun obtenerEventos(): List<EventoBitacora> {
        val lista = mutableListOf<EventoBitacora>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, fecha_hora, usuario_rol, detalle FROM bitacora_eventos ORDER BY id DESC",
            null
        )

        if (cursor.moveToFirst()) {
            do {
                lista.add(
                    EventoBitacora(
                        id = cursor.getInt(0),
                        fechaHora = cursor.getString(1),
                        usuarioRol = cursor.getString(2),
                        detalle = cursor.getString(3)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return lista
    }

    /**
     * Obtiene los últimos eventos en orden cronológico (de más antiguo a más reciente)
     * para empaquetarlos y sincronizarlos por Wi-Fi cuando se restablece la conexión.
     */
    fun obtenerEventosParaSincronizar(limite: Int = 30): List<EventoBitacora> {
        val ultimos = obtenerEventos()
            .filter { it.usuarioRol != "SISTEMA" }
            .take(limite)
        return ultimos.reversed()
    }

    /**
     * Guarda el estado actual de los controles en SQLite para no perderlo si se cierra la app.
     */
    fun guardarEstadoPanel(
        puertaAbierta: Boolean,
        anguloServo: Int,
        buzzerActivo: Boolean,
        modoAcceso: String,
        uidRfid: String,
        pinBotonera: String,
        timestampModificacion: Long,
        ultimaIp: String,
        timestampOperadores: Long = 0L
    ) {
        val db = writableDatabase
        crearTablaEstadoPanel(db)
        val valores = ContentValues().apply {
            put("puerta_abierta", if (puertaAbierta) 1 else 0)
            put("angulo_servo", anguloServo)
            put("buzzer_activo", if (buzzerActivo) 1 else 0)
            put("modo_acceso", modoAcceso)
            put("uid_rfid", uidRfid)
            put("pin_botonera", pinBotonera)
            put("timestamp_modificacion", timestampModificacion)
            put("ultima_ip", ultimaIp)
            put("timestamp_operadores", timestampOperadores)
        }
        db.update("estado_panel", valores, "id = 1", null)
    }

    /**
     * Carga el último estado guardado del panel desde SQLite.
     */
    fun obtenerEstadoPanel(): EstadoGuardadoSQLite {
        val db = writableDatabase
        crearTablaEstadoPanel(db)
        val cursor = db.rawQuery(
            "SELECT puerta_abierta, angulo_servo, buzzer_activo, modo_acceso, uid_rfid, pin_botonera, timestamp_modificacion, ultima_ip, timestamp_operadores, timestamp_codigo_auth FROM estado_panel WHERE id = 1",
            null
        )
        var estado = EstadoGuardadoSQLite(
            puertaAbierta = false,
            anguloServo = 0,
            buzzerActivo = false,
            modoAcceso = "RFID",
            uidRfid = "A4-9B-3C-12",
            pinBotonera = "4829",
            timestampModificacion = 0L,
            ultimaIpDestino = "192.168.43.1",
            timestampOperadores = 0L,
            timestampCodigoAuth = 0L
        )
        if (cursor.moveToFirst()) {
            estado = EstadoGuardadoSQLite(
                puertaAbierta = cursor.getInt(0) == 1,
                anguloServo = cursor.getInt(1),
                buzzerActivo = cursor.getInt(2) == 1,
                modoAcceso = cursor.getString(3) ?: "RFID",
                uidRfid = cursor.getString(4) ?: "A4-9B-3C-12",
                pinBotonera = cursor.getString(5) ?: "4829",
                timestampModificacion = cursor.getLong(6),
                ultimaIpDestino = cursor.getString(7) ?: "192.168.43.1",
                timestampOperadores = cursor.getLong(8),
                timestampCodigoAuth = cursor.getLong(9)
            )
        }
        cursor.close()
        return estado
    }

    private fun obtenerHoraActual(): String {
        val formato = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formato.format(Date())
    }
}
