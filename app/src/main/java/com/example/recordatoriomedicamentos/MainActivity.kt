package com.example.recordatoriomedicamentos

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    data class Registro(
        val id: String = "",
        val medicamento: String = "",
        val dosis: String = "",
        val horaIngresada: String = "",
        val resultado: String = "",
        val fechaRegistro: Long = 0
    ) {
        fun textoHistorial(): String =
            "Medicamento: $medicamento\nDosis: $dosis\n" +
                "Estado: ${if (resultado == "TOMADA") "Tomada" else "No tomada"}\n" +
                "Fecha: ${DateFormat.getDateTimeInstance().format(Date(fechaRegistro))}"
    }
    private val pendientes = linkedMapOf<String, Registro>()
    private val handler = Handler(Looper.getMainLooper())
    private var simulando = false
    private var sincronizando = false
    private var sincronizacionAutomaticaPendiente = false
    private var actualizar: (() -> Unit)? = null
    private var uidPendientes: String? = null
    private var autenticando = false
    private var mensajeLogin = ""

    private var registrosRef: DatabaseReference? = null
    private var conexionRef: DatabaseReference? = null
    private var historialListener: ValueEventListener? = null
    private var conexionListener: ValueEventListener? = null
    private var conectado = false
    private var auth: FirebaseAuth? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var uidActual: String? = null
    private var registros = emptyList<Registro>()
    private var historialCargado = false
    private var errorHistorial = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.main)
        val padding = listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(padding[0] + bars.left, padding[1] + bars.top,
                padding[2] + bars.right, padding[3] + bars.bottom)
            insets
        }
        findViewById<Button>(R.id.tabAlarmas).setOnClickListener { mostrarHistorial(false) }
        findViewById<Button>(R.id.tabHistorial).setOnClickListener { mostrarHistorial(true) }
        findViewById<Button>(R.id.enviar).setOnClickListener { enviar() }
        findViewById<Button>(R.id.btnIniciarSesion).setOnClickListener { autenticar(registrar = false) }
        findViewById<Button>(R.id.btnRegistrar).setOnClickListener { autenticar(registrar = true) }
        findViewById<Button>(R.id.btnCerrarSesion).setOnClickListener { cerrarSesion() }
        if (FirebaseApp.initializeApp(this) != null) {
            auth = FirebaseAuth.getInstance()
            if (auth?.currentUser?.isAnonymous == true) auth?.signOut()
        }
        mostrarHistorial(false)
        renderizar()
    }

    override fun onStart() {
        super.onStart()
        actualizar = { actualizarSesion() }
        authListener = FirebaseAuth.AuthStateListener { actualizarSesion() }
        authListener?.let { auth?.addAuthStateListener(it) }
        actualizarSesion()
    }

    override fun onStop() {
        actualizar = null
        authListener?.let { auth?.removeAuthStateListener(it) }
        authListener = null
        desconectarHistorial()
        super.onStop()
    }

    override fun onDestroy() {
        actualizar = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun mostrarHistorial(historial: Boolean) {
        findViewById<View>(R.id.vistaAlarmas).visibility = if (historial) View.GONE else View.VISIBLE
        findViewById<View>(R.id.vistaHistorial).visibility = if (historial) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.tabAlarmas).isEnabled = historial
        findViewById<Button>(R.id.tabHistorial).isEnabled = !historial
    }

    private fun enviar() {
        val uid = uidActual ?: return
        if (simulando) return
        val medicamento = findViewById<EditText>(R.id.medicamento)
        val dosis = findViewById<EditText>(R.id.dosis)
        val hora = findViewById<EditText>(R.id.hora)
        val nombre = medicamento.text.toString().trim()
        val cantidad = dosis.text.toString().trim()
        val tiempo = hora.text.toString().trim()
        if (nombre.isEmpty()) { medicamento.error = "Ingresa el medicamento"; return }
        if (cantidad.isEmpty()) { dosis.error = "Ingresa la dosis"; return }
        if (!Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(tiempo)) {
            hora.error = "Usa HH:mm, por ejemplo 08:30"; return
        }
        simulando = true
        renderizar()
        val app = applicationContext
        fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        toast("Alarma enviada")
        handler.postDelayed({
            toast("Arduino recibió la alarma")
            handler.postDelayed({
                val resultado = if (Random.nextBoolean()) "TOMADA" else "NO TOMADA"
                toast("Alarma apagada. Enviando datos")
                val registro = Registro(UUID.randomUUID().toString(), nombre, cantidad,
                    tiempo, resultado, System.currentTimeMillis())
                handler.postDelayed({
                    toast("Se recibieron los datos: $resultado")
                    if (uidPendientes == uid) pendientes[registro.id] = registro
                    actualizar?.invoke()
                    handler.postDelayed({
                        if (uidPendientes != uid) return@postDelayed
                        simulando = false
                        sincronizacionAutomaticaPendiente = true
                        actualizar?.invoke()
                    }, 2000L)
                }, 2000L)
            }, 2000L + Random.nextLong(1000L, 3001L))
        }, 3000L)
    }

    private fun autenticar(registrar: Boolean) {
        val firebaseAuth = auth ?: return
        if (autenticando) return
        val correo = findViewById<EditText>(R.id.editTextCorreo).text.toString().trim()
        val password = findViewById<EditText>(R.id.editTextPassword).text.toString()
        if (correo.isBlank() || password.isBlank()) {
            mensajeLogin = "Escribe tu correo y contraseña."
            renderizar()
            return
        }
        autenticando = true
        mensajeLogin = if (registrar) "Creando cuenta…" else "Iniciando sesión…"
        renderizar()
        val tarea = if (registrar) firebaseAuth.createUserWithEmailAndPassword(correo, password)
            else firebaseAuth.signInWithEmailAndPassword(correo, password)
        tarea.addOnCompleteListener { resultado ->
            if (isDestroyed) return@addOnCompleteListener
            autenticando = false
            mensajeLogin = if (resultado.isSuccessful) "" else
                if (registrar) "No se pudo crear la cuenta. Revisa los datos e inténtalo nuevamente."
                else "No se pudo iniciar sesión. Revisa el correo y la contraseña."
            actualizar?.invoke()
        }
    }

    private fun actualizarSesion() {
        val usuario = auth?.currentUser?.takeUnless { it.isAnonymous }
        val uid = usuario?.uid
        val cambioUsuario = uidPendientes != uid
        if (cambioUsuario) {
            pendientes.clear()
            handler.removeCallbacksAndMessages(null)
            simulando = false
            sincronizando = false
            sincronizacionAutomaticaPendiente = false
            uidPendientes = uid
        }
        if (uidActual != uid) {
            desconectarHistorial()
            registros = emptyList()
            registrosRef = null
            conexionRef = null
            uidActual = uid
            findViewById<EditText>(R.id.editTextPassword).text.clear()
            if (cambioUsuario) {
                findViewById<EditText>(R.id.medicamento).text.clear()
                findViewById<EditText>(R.id.dosis).text.clear()
                findViewById<EditText>(R.id.hora).text.clear()
                mostrarHistorial(false)
            }
        }
        findViewById<View>(R.id.vistaLogin).visibility = if (uid == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.vistaAplicacion).visibility = if (uid == null) View.GONE else View.VISIBLE
        if (uid != null && historialListener == null) iniciarFirebase()
        renderizar()
        if (uid != null && sincronizacionAutomaticaPendiente && !sincronizando) {
            sincronizacionAutomaticaPendiente = false
            sincronizar()
        }
    }


    private fun cerrarSesion() {
        if (simulando || sincronizando) return
        auth?.signOut()
        mensajeLogin = ""
        findViewById<EditText>(R.id.editTextCorreo).text.clear()
        findViewById<EditText>(R.id.editTextPassword).text.clear()
        actualizarSesion()
    }

    private fun desconectarHistorial() {
        historialListener?.let { registrosRef?.removeEventListener(it) }
        conexionListener?.let { conexionRef?.removeEventListener(it) }
        historialListener = null
        conexionListener = null
        conectado = false
        historialCargado = false
        errorHistorial = false
    }

    private fun iniciarFirebase() {
        val uid = uidActual ?: return
        if (FirebaseApp.getInstance().options.databaseUrl.isNullOrBlank()) {
            conectado = false
            return
        }
        try {
            val db = FirebaseDatabase.getInstance()
            registrosRef = db.getReference("usuarios").child(uid).child("registros")
            conexionRef = db.getReference(".info/connected")
            escucharFirebase()
        } catch (_: DatabaseException) {
            conectado = false
        }
    }

    private fun escucharFirebase() {
        val uid = uidActual ?: return
        historialListener?.let { registrosRef?.removeEventListener(it) }
        conexionListener?.let { conexionRef?.removeEventListener(it) }
        conexionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isDestroyed || uidActual != uid) return
                conectado = snapshot.getValue(Boolean::class.java) == true
                renderizar()
            }
            override fun onCancelled(error: DatabaseError) {
                if (isDestroyed || uidActual != uid) return
                conectado = false
                renderizar()
            }
        }
        conexionRef?.addValueEventListener(conexionListener!!)
        historialListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isDestroyed || uidActual != uid) return
                registros = snapshot.children.mapNotNull { dato ->
                    val id = dato.child("id").getValue(String::class.java) ?: return@mapNotNull null
                    val medicamento = dato.child("medicamento").getValue(String::class.java) ?: return@mapNotNull null
                    val dosis = dato.child("dosis").getValue(String::class.java) ?: return@mapNotNull null
                    val hora = dato.child("horaIngresada").getValue(String::class.java) ?: return@mapNotNull null
                    val resultado = dato.child("resultado").getValue(String::class.java) ?: return@mapNotNull null
                    val fecha = dato.child("fechaRegistro").getValue(Long::class.java) ?: return@mapNotNull null
                    Registro(id, medicamento, dosis, hora, resultado, fecha)
                }
                historialCargado = true
                errorHistorial = false
                renderizar()
            }
            override fun onCancelled(error: DatabaseError) {
                if (isDestroyed || uidActual != uid) return
                registros = emptyList()
                errorHistorial = true
                renderizar()
            }
        }
        registrosRef?.addValueEventListener(historialListener!!)
    }

    private fun sincronizar() {
        val uid = uidActual ?: return
        if (sincronizando || pendientes.isEmpty()) return
        val ref = registrosRef
        if (ref == null || !conectado) {
            Toast.makeText(this, "No se pudo sincronizar. Puedes volver a intentarlo.",
                Toast.LENGTH_SHORT).show()
            iniciarFirebase()
            return
        }
        val lote = pendientes.toMap()
        val cambios = lote.mapValues { (_, r) ->
            mapOf("id" to r.id, "medicamento" to r.medicamento, "dosis" to r.dosis,
                "horaIngresada" to r.horaIngresada, "resultado" to r.resultado,
                "fechaRegistro" to r.fechaRegistro)
        }
        sincronizando = true
        renderizar()
        val app = applicationContext
        ref.updateChildren(cambios).addOnCompleteListener { task ->
            if (isDestroyed || uidPendientes != uid) return@addOnCompleteListener
            if (task.isSuccessful) lote.keys.forEach { pendientes.remove(it) }
            sincronizando = false
            Toast.makeText(app,
                if (task.isSuccessful) "Registros sincronizados."
                else "No se pudo sincronizar. Puedes volver a intentarlo.",
                Toast.LENGTH_SHORT).show()
            actualizar?.invoke()
        }
    }

    private fun renderizar() {
        val enSesion = uidActual != null
        findViewById<Button>(R.id.btnIniciarSesion).isEnabled = auth != null && !autenticando
        findViewById<Button>(R.id.btnRegistrar).isEnabled = auth != null && !autenticando
        findViewById<EditText>(R.id.editTextCorreo).isEnabled = !autenticando
        findViewById<EditText>(R.id.editTextPassword).isEnabled = !autenticando
        findViewById<TextView>(R.id.textEstadoLogin).text = if (auth == null)
            "El servicio de acceso no está disponible." else mensajeLogin
        findViewById<Button>(R.id.btnCerrarSesion).isEnabled = enSesion && !simulando && !sincronizando
        findViewById<TextView>(R.id.textUsuario).text = auth?.currentUser?.email ?: ""
        findViewById<Button>(R.id.enviar).isEnabled = enSesion && !simulando
        findViewById<TextView>(R.id.estadoSimulacion).apply {
            text = if (simulando) "Simulación en curso…" else ""
            visibility = if (simulando) View.VISIBLE else View.GONE
        }
        fun texto(lista: List<Registro>): String = lista.sortedByDescending { it.fechaRegistro }
            .joinToString("\n\n") { it.textoHistorial() }
            .ifEmpty { "Sin registros." }
        findViewById<TextView>(R.id.listaFirebase).text = when {
            !conectado -> "Sin conexión con la base de datos."
            errorHistorial -> "No se pudo leer el historial."
            !historialCargado -> "Cargando registros..."
            else -> texto(registros.filter { it.id !in pendientes })
        }
    }
}




