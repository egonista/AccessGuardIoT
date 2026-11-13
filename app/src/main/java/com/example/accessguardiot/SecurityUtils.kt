package com.example.accessguardiot

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utilidades de Ciberseguridad IoT: Norma ISO/IEC 27400, Hash SHA-256 y Cifrado AES-128-GCM
 */
object SecurityUtils {

    // Clave simétrica AES-128 inyectada en compilación desde secrets.properties (BuildConfig)
    private val CLAVE_AES: String
        get() = BuildConfig.AES_KEY.toByteArray(Charsets.UTF_8)
            .let { if (it.size >= 16) String(it.copyOfRange(0, 16), Charsets.UTF_8) else BuildConfig.AES_KEY.padEnd(16, '0') }
    private const val PREFIJO_HASH_SHA256 = "sha256:"
    private const val TAMANO_IV_GCM = 12
    private const val TAG_GCM_BITS = 128
    private const val ALFABETO_LLAVE = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    // Lista ampliada de credenciales de fábrica y triviales prohibidas según ISO/IEC 27400
    private val CLAVES_PROHIBIDAS = setOf(
        "admin", "admin/admin", "root", "user", "guest", "test", "default",
        "password", "pass", "admin123", "qwerty", "abc123",
        "1234", "12345", "123456", "12345678", "4321", "9876", "2580",
        "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
        "aaaa", "ffff"
    )

    private const val SECUENCIA_ASC = "0123456789abcdef"
    private const val SECUENCIA_DESC = "9876543210fedcba"

    /**
     * Valida si el texto ingresado corresponde a una contraseña, usuario o PIN genérico/secuencial prohibido.
     */
    fun esCredencialProhibida(texto: String): Boolean {
        val limpio = texto.trim().lowercase()
        if (limpio.isEmpty()) return false
        if (limpio in CLAVES_PROHIBIDAS) return true

        // Detectar si todos los caracteres son idénticos (ej. "0000", "7777", "aaaaa")
        if (limpio.length >= 3 && limpio.all { it == limpio[0] }) return true

        // Detectar secuencias consecutivas obvias de 4 o más dígitos (ej. "1234", "2345", "5678", "4321", "9876")
        if (limpio.length >= 4 && (SECUENCIA_ASC.contains(limpio) || SECUENCIA_DESC.contains(limpio))) {
            return true
        }

        return false
    }

    /**
     * Valida que el UID RFID y el PIN de la Botonera 4x4 cumplan un mínimo de seguridad (ISO/IEC 27400).
     * Retorna un mensaje de error si es inseguro, o null si es válido.
     */
    fun validarCredencialesAcceso(uid: String, pin: String): String? {
        val uidLimpio = uid.trim()
        val pinLimpio = pin.trim()

        if (uidLimpio.isBlank() || pinLimpio.isBlank()) {
            return "El identificador RFID y el PIN no pueden estar vacíos."
        }
        if (uidLimpio.length < 4 || esCredencialProhibida(uidLimpio)) {
            return "Identificador RFID inválido: debe tener al menos 4 caracteres y no usar secuencias genéricas."
        }
        if (pinLimpio.length < 4 || pinLimpio.length > 8) {
            return "PIN inválido: debe tener entre 4 y 8 caracteres."
        }
        // Caracteres válidos en una botonera matricial 4x4: 0-9, A-D, *, #
        val caracteresKeypadValidos = pinLimpio.uppercase().all { it in "0123456789ABCD*#" }
        if (!caracteresKeypadValidos) {
            return "El PIN solo puede contener caracteres válidos del teclado (0-9, A-D, *, #)."
        }
        if (esCredencialProhibida(pinLimpio)) {
            return "PIN rechazado: no se permiten códigos secuenciales o repetidos."
        }
        return null
    }

    /**
     * Valida que una contraseña cumpla estándares de robustez (ISO/IEC 27400 / 27001):
     * mínimo 8 caracteres, mayúscula, minúscula, número, no trivial y distinta al usuario.
     */
    fun validarPasswordRobusta(username: String, password: String): String? {
        val u = username.trim()
        val p = password.trim()

        if (p.isBlank()) {
            return "La contraseña no puede estar vacía."
        }
        if (esCredencialProhibida(p) || (u.isNotBlank() && p.equals(u, ignoreCase = true))) {
            return "Contraseña rechazada: no puede ser una clave común ni coincidir con el usuario."
        }
        if (p.length < 8) {
            return "Contraseña insuficiente: debe tener al menos 8 caracteres."
        }
        val tieneMayuscula = p.any { it.isUpperCase() }
        val tieneMinuscula = p.any { it.isLowerCase() }
        val tieneNumero = p.any { it.isDigit() }

        if (!tieneMayuscula || !tieneMinuscula || !tieneNumero) {
            return "Contraseña insuficiente: debe incluir mayúsculas, minúsculas y números."
        }
        return null
    }

    /**
     * Valida que el nuevo usuario Operador y su contraseña cumplan estándares de robustez (ISO/IEC 27400 / 27001).
     * Exige mínimo 8 caracteres, mayúscula, minúscula y número. Retorna null si es válida.
     */
    fun validarNuevoOperador(username: String, password: String): String? {
        val u = username.trim()
        val p = password.trim()

        if (u.isBlank() || p.isBlank()) {
            return "Debe ingresar un nombre de usuario y una contraseña."
        }
        if (u.any { it in "|~;" }) {
            return "El nombre de usuario contiene caracteres no permitidos."
        }
        if (u.length < 3 || esCredencialProhibida(u)) {
            return "Nombre de usuario inválido o reservado por política de seguridad."
        }
        return validarPasswordRobusta(u, p)
    }

    /**
     * Genera una llave aleatoria única por instalación o rotación (formato AG-XXXX-XXXX) usando SecureRandom.
     */
    fun generarCodigoAutorizacionAleatorio(): String {
        val random = SecureRandom()
        fun bloque(): String = buildString {
            repeat(4) {
                append(ALFABETO_LLAVE[random.nextInt(ALFABETO_LLAVE.length)])
            }
        }
        return "AG-${bloque()}-${bloque()}"
    }

    /** Calcula el hash SHA-256 con sal de dominio de la llave de recuperación/autorización. */
    fun hashCodigoAutorizacion(codigo: String): String {
        val limpio = codigo.trim().uppercase()
        val entrada = "AccessGuardIoT|RECOVERY|$limpio"
        val digest = MessageDigest.getInstance("SHA-256").digest(entrada.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$PREFIJO_HASH_SHA256$hex"
    }

    /** Verifica en tiempo constante un código de autorización frente al hash SHA-256 almacenado. */
    fun verificarCodigoAutorizacion(codigoIngresado: String, hashGuardado: String): Boolean {
        val limpioIngresado = codigoIngresado.trim()
        val limpioGuardado = hashGuardado.trim()
        if (limpioIngresado.isEmpty() || !esHashSha256(limpioGuardado)) return false
        val hashIngresado = hashCodigoAutorizacion(limpioIngresado).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(hashIngresado, limpioGuardado.toByteArray(Charsets.UTF_8))
    }

    /**
     * Valida el restablecimiento de contraseña desde el Login: exige la llave vigente de recuperación/autorización
     * generada por el sistema o la contraseña vigente del Supervisor (verificadas contra su hash SHA-256).
     */
    fun validarRestablecimientoPassword(
        username: String,
        codigoAutorizacion: String,
        nuevaPassword: String,
        codigoAutorizacionValido: Boolean = false,
        codigoSupervisorValido: Boolean = false
    ): String? {
        val u = username.trim()
        val codigo = codigoAutorizacion.trim()

        if (u.isBlank() || codigo.isBlank() || nuevaPassword.trim().isBlank()) {
            return "Completa usuario, código de autorización y la nueva contraseña."
        }
        if (!codigoAutorizacionValido && !codigoSupervisorValido) {
            return "Código de autorización inválido. Solicita la llave vigente al Supervisor."
        }
        return validarPasswordRobusta(u, nuevaPassword)
    }

    /**
     * Calcula el hash SHA-256 con sal por usuario/dominio ("sha256:<hex>") para nunca guardar
     * ni transmitir contraseñas en texto plano en SQLite o por la red.
     */
    fun hashPassword(username: String, password: String): String {
        val entrada = "AccessGuardIoT|${username.trim().lowercase()}|${password.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(entrada.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$PREFIJO_HASH_SHA256$hex"
    }

    /** Indica si una cadena ya corresponde a un digest SHA-256 con formato "sha256:<64 hex>". */
    fun esHashSha256(valor: String): Boolean {
        val limpio = valor.trim()
        return limpio.startsWith(PREFIJO_HASH_SHA256) &&
                limpio.length == PREFIJO_HASH_SHA256.length + 64
    }

    /** Devuelve el valor intacto si ya es hash SHA-256; en caso contrario lo convierte a SHA-256. */
    fun normalizarHashPassword(username: String, valor: String): String {
        val limpio = valor.trim()
        return if (esHashSha256(limpio)) limpio else hashPassword(username, limpio)
    }

    /** Verifica una contraseña ingresada frente al hash almacenado en SQLite (soporta migración legacy). */
    fun verificarPassword(username: String, passwordIngresada: String, valorGuardado: String): Boolean {
        val limpioIngresado = passwordIngresada.trim()
        val limpioGuardado = valorGuardado.trim()
        if (limpioIngresado.isEmpty() || limpioGuardado.isEmpty()) return false

        return if (esHashSha256(limpioGuardado)) {
            val hashIngresado = hashPassword(username, limpioIngresado).toByteArray(Charsets.UTF_8)
            MessageDigest.isEqual(hashIngresado, limpioGuardado.toByteArray(Charsets.UTF_8))
        } else {
            limpioIngresado == limpioGuardado
        }
    }

    /**
     * Cifra un texto plano con AES-128-GCM (confidencialidad + etiqueta de integridad de 128 bits + IV aleatorio)
     * y lo codifica en Base64 para viajar seguro incluso sobre Hotspot WPA2-Personal.
     */
    fun cifrarAES(texto: String): String {
        val iv = ByteArray(TAMANO_IV_GCM).also { SecureRandom().nextBytes(it) }
        val keySpec = SecretKeySpec(CLAVE_AES.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_GCM_BITS, iv))
        val bytesCifrados = cipher.doFinal(texto.toByteArray(Charsets.UTF_8))
        val combinado = ByteArray(iv.size + bytesCifrados.size)
        System.arraycopy(iv, 0, combinado, 0, iv.size)
        System.arraycopy(bytesCifrados, 0, combinado, iv.size, bytesCifrados.size)
        return Base64.encodeToString(combinado, Base64.NO_WRAP)
    }

    /**
     * Filtra la bitácora según principio de Mínimo Privilegio (RBAC):
     * - SUPERVISOR ve la auditoría completa (operación física, gestión de cuentas y alertas).
     * - OPERADOR ve solo eventos operativos y accesos físicos, ocultando altas/bajas/claves de usuarios y alertas internas.
     */
    fun esEventoOperativoVisible(usuarioRol: String, detalle: String, esSupervisor: Boolean): Boolean {
        if (esSupervisor) return true
        val actorUpper = usuarioRol.trim().uppercase()
        if (actorUpper.startsWith("SEGURIDAD")) return false

        val detalleLower = detalle.trim().lowercase()
        val esEventoAdministrativoDeCuenta =
            detalleLower.contains("cuenta de operador") ||
                detalleLower.contains("cuenta del operador") ||
                detalleLower.contains("creó la cuenta") ||
                detalleLower.contains("eliminó la cuenta") ||
                detalleLower.contains("contraseña") ||
                detalleLower.contains("llave de autorización") ||
                detalleLower.contains("llave de recuperación") ||
                detalleLower.contains("restableció") ||
                detalleLower.contains("eliminada por el supervisor") ||
                detalleLower.contains("clave genérica") ||
                detalleLower.contains("credencial restringida") ||
                detalleLower.contains("intentos fallidos")
        return !esEventoAdministrativoDeCuenta
    }

    /**
     * Verifica la etiqueta de autenticidad e integridad y descifra una trama AES-128-GCM en Base64.
     * Descarta automáticamente cualquier trama adulterada o sin cifrado válido.
     */
    fun descifrarAES(cifrado: String): String? {
        return try {
            val combinado = Base64.decode(cifrado.trim(), Base64.NO_WRAP)
            if (combinado.size <= TAMANO_IV_GCM + 16) return null
            val iv = combinado.copyOfRange(0, TAMANO_IV_GCM)
            val payload = combinado.copyOfRange(TAMANO_IV_GCM, combinado.size)
            val keySpec = SecretKeySpec(CLAVE_AES.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_GCM_BITS, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
