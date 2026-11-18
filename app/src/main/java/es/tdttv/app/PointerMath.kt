package es.tdttv.app

/**
 * Posicion y movimiento del cursor del modo "cursor y teclado". No usa nada de Android para poder probarlo en JVM.
 * `density` es el de la pantalla: los pasos se definen en dp.
 */
class PointerMath(private var width: Int, private var height: Int, private val density: Float) {

    var x = width / 2f
        private set
    var y = height / 2f
        private set

    fun resize(w: Int, h: Int) {
        width = w
        height = h
        x = x.coerceIn(0f, maxX())
        y = y.coerceIn(0f, maxY())
    }

    /** Paso en px: crece mientras la flecha sigue pulsada (`repeat` = KeyEvent.repeatCount). */
    fun step(repeat: Int): Float = (BASE_DP + minOf(repeat, MAX_REPEAT) * GROWTH_DP) * density

    /**
     * Mueve el cursor en la direccion (dx, dy), cada una -1, 0 o 1.
     * Devuelve -1 / 1 si la flecha vertical se pulso con el cursor ya en el borde superior / inferior
     * (el llamador desplaza la pagina), y 0 en cualquier otro caso.
     */
    fun move(dx: Int, dy: Int, repeat: Int): Int {
        val s = step(repeat)
        val atTop = y <= 0f
        val atBottom = y >= maxY()
        x = (x + dx * s).coerceIn(0f, maxX())
        y = (y + dy * s).coerceIn(0f, maxY())
        return when {
            dy < 0 && atTop -> -1
            dy > 0 && atBottom -> 1
            else -> 0
        }
    }

    private fun maxX() = (width - 1).coerceAtLeast(0).toFloat()
    private fun maxY() = (height - 1).coerceAtLeast(0).toFloat()

    private companion object {
        const val BASE_DP = 10f
        const val GROWTH_DP = 4f
        const val MAX_REPEAT = 12
    }
}
