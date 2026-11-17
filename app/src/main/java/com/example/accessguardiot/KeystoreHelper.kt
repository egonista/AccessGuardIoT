package com.example.accessguardiot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Resguardo de claves y secretos locales usando Android Keystore (AES-256-GCM no exportable).
 * La llave criptográfica reside en el almacén seguro del sistema operativo Android.
 */
object KeystoreHelper {

    private const val PROVEEDOR_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_LLAVE_LOCAL = "AccessGuardIoT_LocalMasterKey"
    private const val TRANSFORMACION_AES_GCM = "AES/GCM/NoPadding"
    private const val PREFIJO_KEYSTORE = "ks:"
    private const val TAMANO_IV_GCM = 12
    private const val TAG_GCM_BITS = 128

    @Volatile
    private var llaveCache: SecretKey? = null

    /** Indica si un valor almacenado en SQLite ya está sellado por Android Keystore. */
    fun esValorSellado(valor: String): Boolean {
        return valor.trim().startsWith(PREFIJO_KEYSTORE)
    }

    /**
     * Cifra un secreto local usando la clave AES-256-GCM protegida en AndroidKeyStore.
     * Retorna "ks:<Base64(iv + textoCifrado)>", o null si Keystore no está disponible en el entorno.
     */
    @Synchronized
    fun cifrarLocal(textoPlano: String): String? {
        if (textoPlano.isEmpty()) return ""
        return try {
            val llave = obtenerOCrearLlave() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMACION_AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, llave)
            val iv = cipher.iv
            val bytesCifrados = cipher.doFinal(textoPlano.toByteArray(Charsets.UTF_8))

            val combinado = ByteArray(iv.size + bytesCifrados.size)
            System.arraycopy(iv, 0, combinado, 0, iv.size)
            System.arraycopy(bytesCifrados, 0, combinado, iv.size, bytesCifrados.size)
            PREFIJO_KEYSTORE + Base64.encodeToString(combinado, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Descifra un valor previamente sellado con [cifrarLocal] usando AndroidKeyStore.
     * Retorna null si la cadena no tiene el formato esperado o falla la autenticación GCM.
     */
    @Synchronized
    fun descifrarLocal(valorSellado: String): String? {
        val limpio = valorSellado.trim()
        if (!limpio.startsWith(PREFIJO_KEYSTORE)) return null
        return try {
            val datos = Base64.decode(limpio.removePrefix(PREFIJO_KEYSTORE), Base64.NO_WRAP)
            if (datos.size <= TAMANO_IV_GCM + 16) return null

            val iv = datos.copyOfRange(0, TAMANO_IV_GCM)
            val payload = datos.copyOfRange(TAMANO_IV_GCM, datos.size)
            val llave = obtenerOCrearLlave() ?: return null

            val cipher = Cipher.getInstance(TRANSFORMACION_AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, llave, GCMParameterSpec(TAG_GCM_BITS, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun obtenerOCrearLlave(): SecretKey? {
        llaveCache?.let { return it }
        val keyStore = KeyStore.getInstance(PROVEEDOR_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ALIAS_LLAVE_LOCAL)) {
            val existente = keyStore.getKey(ALIAS_LLAVE_LOCAL, null) as? SecretKey
            if (existente != null) {
                llaveCache = existente
                return existente
            }
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS_LLAVE_LOCAL,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey().also { llaveCache = it }
    }
}
