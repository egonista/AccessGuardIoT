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
    val timestampOperadores: Long = 0L
)

/**
 * Base de datos local SQLite nativa (acceso_iot.db)
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "acceso_iot.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Tabla de usuarios con roles diferenciados (SUPERVISOR vs OPERADOR)
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

        // 4. Insertar cuentas por defecto (con contraseñas seguras según ISO/IEC 27400)
        db.execSQL("INSERT INTO usuarios (username, password, rol) VALUES ('supervisor', 'Sup#2026IoT', 'SUPERVISOR')")
        db.execSQL("INSERT INTO usuarios (username, password, rol) VALUES ('operador', 'Ope#2026Lab', 'OPERADOR')")

        // 5. Evento inicial de auditoría
        val horaInicial = obtenerHoraActual()
        db.execSQL(
            "INSERT INTO bitacora_eventos (fecha_hora, usuario_rol, detalle) VALUES ('$horaInicial', 'SISTEMA', 'Base de datos acceso_iot.db inicializada')"
        )
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
                timestamp_operadores INTEGER DEFAULT 0
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
    }

    /**
     * Verifica las credenciales en SQLite y devuelve el rol ("SUPERVISOR" u "OPERADOR"),
     * o null si las credenciales son incorrectas.
     */
    fun validarUsuario(username: String, password: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT rol FROM usuarios WHERE username = ? AND password = ?",
            arrayOf(username.trim(), password.trim())
        )
        var rolEncontrado: String? = null
        if (cursor.moveToFirst()) {
            rolEncontrado = cursor.getString(0)
        }
        cursor.close()
        return rolEncontrado
    }

    /**
     * Registra un nuevo usuario exclusivamente con el rol "OPERADOR" en SQLite.
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
            put("password", passLimpia)
            put("rol", "OPERADOR")
        }
        return db.insert("usuarios", null, valores) != -1L
    }

    /**
     * CRUD (Update): Actualiza la contraseña de un usuario con rol "OPERADOR" en SQLite.
     * Retorna true si el operador existía y su clave fue actualizada.
     */
    fun actualizarPasswordOperador(username: String, nuevaPassword: String): Boolean {
        val userLimpio = username.trim()
        val passLimpia = nuevaPassword.trim()
        if (userLimpio.isBlank() || passLimpia.isBlank()) return false

        val db = writableDatabase
        val valores = ContentValues().apply {
            put("password", passLimpia)
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

    /** Obtiene la contraseña vigente del Supervisor en SQLite para autorizar recuperación o sincronizar. */
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

    /**
     * Actualiza la contraseña de cualquier cuenta existente (SUPERVISOR u OPERADOR) desde Recuperación.
     * Retorna el rol actualizado o null si el usuario no existe.
     */
    fun actualizarPasswordUsuario(username: String, nuevaPassword: String): String? {
        val userLimpio = username.trim()
        val passLimpia = nuevaPassword.trim()
        if (userLimpio.isBlank() || passLimpia.isBlank()) return null

        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT rol FROM usuarios WHERE LOWER(username) = LOWER(?) LIMIT 1",
            arrayOf(userLimpio)
        )
        var rolEncontrado: String? = null
        if (cursor.moveToFirst()) {
            rolEncontrado = cursor.getString(0)
        }
        cursor.close()
        if (rolEncontrado == null) return null

        val valores = ContentValues().apply {
            put("password", passLimpia)
        }
        val filas = db.update(
            "usuarios",
            valores,
            "LOWER(username) = LOWER(?)",
            arrayOf(userLimpio)
        )
        return if (filas > 0) rolEncontrado else null
    }

    /** Sincroniza la contraseña del Supervisor recibida por Wi-Fi desde el otro dispositivo. */
    fun sincronizarPasswordSupervisor(nuevaPassword: String) {
        val passLimpia = nuevaPassword.trim()
        if (passLimpia.isBlank()) return
        val db = writableDatabase
        val valores = ContentValues().apply {
            put("password", passLimpia)
        }
        db.update("usuarios", valores, "rol = 'SUPERVISOR'", null)
    }

    /**
     * Sincroniza la tabla local de Operadores en SQLite con la lista recibida desde el otro dispositivo
     * (reflejando altas, cambios de contraseña y bajas hechas por el Supervisor).
     */
    fun sincronizarListaOperadores(operadoresRemotos: List<UsuarioOperador>) {
        if (operadoresRemotos.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("usuarios", "rol = 'OPERADOR'", null)
            for (op in operadoresRemotos) {
                val valores = ContentValues().apply {
                    put("username", op.username.trim())
                    put("password", op.password.trim())
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
        val db = readableDatabase
        crearTablaEstadoPanel(db)
        val cursor = db.rawQuery(
            "SELECT puerta_abierta, angulo_servo, buzzer_activo, modo_acceso, uid_rfid, pin_botonera, timestamp_modificacion, ultima_ip, timestamp_operadores FROM estado_panel WHERE id = 1",
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
            timestampOperadores = 0L
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
                timestampOperadores = cursor.getLong(8)
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
