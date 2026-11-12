package com.example.accessguardiot

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Utilidades de Ciberseguridad IoT: Norma ISO/IEC 27400 y Cifrado AES-128
 */
object SecurityUtils {

    // Clave simétrica fija de 16 caracteres (128 bits) para AES
    private const val CLAVE_AES = "ClaveIoTInacap16"

    // Código de autorización OT para restablecer contraseñas desde el Login
    const val CODIGO_AUTORIZACION_OT = "OT#2026Seg"

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
            return "Rechazado: El UID RFID y el PIN 4x4 no pueden estar vacíos."
        }
        if (uidLimpio.length < 4 || esCredencialProhibida(uidLimpio)) {
            return "Rechazado por ISO/IEC 27400: El UID RFID debe tener al menos 4 caracteres y no ser trivial (ej. 0000, 1111, AAAA)."
        }
        if (pinLimpio.length < 4 || pinLimpio.length > 8) {
            return "Rechazado por ISO/IEC 27400: El PIN de la botonera 4x4 debe tener entre 4 y 8 caracteres."
        }
        // Caracteres válidos en una botonera matricial 4x4: 0-9, A-D, *, #
        val caracteresKeypadValidos = pinLimpio.uppercase().all { it in "0123456789ABCD*#" }
        if (!caracteresKeypadValidos) {
            return "El PIN solo puede contener teclas válidas de la Botonera 4x4 (0-9, A-D, *, #)."
        }
        if (esCredencialProhibida(pinLimpio)) {
            return "Rechazado por ISO/IEC 27400: No se permiten PINs secuenciales (1234, 4321, 2580) ni repetidos (0000, 1111, 2222...)."
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
            return "Rechazado por ISO/IEC 27400: La contraseña no puede ser genérica (admin, 1234, 0000...) ni igual al nombre de usuario."
        }
        if (p.length < 8) {
            return "Seguridad insuficiente (ISO/IEC 27400): La contraseña debe tener al menos 8 caracteres."
        }
        val tieneMayuscula = p.any { it.isUpperCase() }
        val tieneMinuscula = p.any { it.isLowerCase() }
        val tieneNumero = p.any { it.isDigit() }

        if (!tieneMayuscula || !tieneMinuscula || !tieneNumero) {
            return "Seguridad insuficiente (ISO/IEC 27400): La contraseña debe incluir mayúsculas, minúsculas y números (ej. Operador2026)."
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
        if (u.length < 3 || esCredencialProhibida(u)) {
            return "Rechazado por ISO/IEC 27400: Nombre de usuario inválido o reservado de fábrica (admin, root, guest, user)."
        }
        return validarPasswordRobusta(u, p)
    }

    /**
     * Valida el restablecimiento de contraseña desde el Login: exige código de autorización OT válido
     * (o contraseña vigente del Supervisor) y que la nueva clave cumpla ISO/IEC 27400.
     */
    fun validarRestablecimientoPassword(
        username: String,
        codigoAutorizacion: String,
        nuevaPassword: String,
        passwordSupervisorActual: String? = null
    ): String? {
        val u = username.trim()
        val codigo = codigoAutorizacion.trim()

        if (u.isBlank() || codigo.isBlank() || nuevaPassword.trim().isBlank()) {
            return "Completa usuario, código de autorización OT y la nueva contraseña."
        }
        val codigoValido = (codigo == CODIGO_AUTORIZACION_OT) ||
                (!passwordSupervisorActual.isNullOrBlank() && codigo == passwordSupervisorActual.trim())
        if (!codigoValido) {
            return "Código de autorización inválido. Verifica el código OT entregado por el Supervisor."
        }
        return validarPasswordRobusta(u, nuevaPassword)
    }

    /**
     * Cifra un texto plano en AES-128 (AES/ECB/PKCS5Padding) y lo codifica en Base64.
     */
    fun cifrarAES(texto: String): String {
        val keySpec = SecretKeySpec(CLAVE_AES.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val bytesCifrados = cipher.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytesCifrados, Base64.NO_WRAP)
    }

    /**
     * Descifra una cadena en Base64 usando AES-128 y devuelve el texto original.
     */
    fun descifrarAES(cifrado: String): String? {
        return try {
            val keySpec = SecretKeySpec(CLAVE_AES.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val bytesDecodificados = Base64.decode(cifrado.trim(), Base64.NO_WRAP)
            String(cipher.doFinal(bytesDecodificados), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
