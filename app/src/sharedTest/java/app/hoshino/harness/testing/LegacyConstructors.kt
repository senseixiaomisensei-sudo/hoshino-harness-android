package app.hoshino.harness.testing

import java.lang.reflect.Constructor

/**
 * Builds an instance of a class from leading constructor arguments, choosing the constructor at RUN time.
 *
 * Why: upgrade-seed instrumentation tests are compiled from the current sources but run inside an OLD installed APK, so
 * every class they touch resolves to the old app's version. A plain Kotlin constructor call is linked against whatever the
 * current source declares (for a data class that gained defaulted parameters, the synthetic `(…, int, DefaultConstructorMarker)`
 * overload), which the old APK does not have: NoSuchMethodError. Going through reflection keeps the test independent of how
 * a data class's constructor list changes later, as long as the leading arguments keep their meaning.
 *
 * Resolution order, against the class as it exists in the running process:
 * 1. a declared, non-synthetic constructor taking exactly these arguments;
 * 2. Kotlin's default-arguments constructor `(p0…pN-1, mask…, DefaultConstructorMarker)` with N ≥ args, filling the trailing
 *    parameters from their Kotlin defaults (mask bits set);
 * 3. otherwise fail with the constructors that do exist, so a real incompatibility is reported, not hidden.
 */
object LegacyConstructors {
    private const val MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

    @JvmStatic
    fun <T> construct(type: Class<T>, vararg args: Any?): T {
        val all = type.declaredConstructors
        all.firstOrNull { !it.isSynthetic && accepts(it.parameterTypes, args) }?.let { ctor ->
            ctor.isAccessible = true
            return type.cast(ctor.newInstance(*args))
        }
        for (ctor in all) {
            val n = kotlinDefaultsArity(ctor) ?: continue
            val types = ctor.parameterTypes
            if (n < args.size || !accepts(types.copyOfRange(0, args.size), args)) continue
            val masks = IntArray((n + 31) / 32)
            val call = arrayOfNulls<Any?>(types.size)
            for (i in 0 until n) {
                if (i < args.size) call[i] = args[i] else {
                    call[i] = zeroOf(types[i])
                    masks[i / 32] = masks[i / 32] or (1 shl (i % 32))
                }
            }
            for (m in masks.indices) call[n + m] = masks[m]
            call[types.size - 1] = null
            ctor.isAccessible = true
            return type.cast(ctor.newInstance(*call))
        }
        throw IllegalStateException(
            "No constructor of ${type.name} accepts ${args.size} leading args " +
                args.joinToString(prefix = "(", postfix = ")") { it?.javaClass?.simpleName ?: "null" } +
                "; available: " + all.joinToString { c -> c.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName } },
        )
    }

    inline fun <reified T> new(vararg args: Any?): T = construct(T::class.java, *args)

    /** N for a Kotlin `(p0…pN-1, int×ceil(N/32), DefaultConstructorMarker)` constructor, else null. */
    internal fun kotlinDefaultsArity(ctor: Constructor<*>): Int? {
        val types = ctor.parameterTypes
        if (types.isEmpty() || types.last().name != MARKER) return null
        for (n in 0 until types.size) {
            val masks = (n + 31) / 32
            if (n + masks + 1 != types.size) continue
            if ((n until n + masks).all { types[it] == Int::class.javaPrimitiveType }) return n
        }
        return null
    }

    private fun accepts(types: Array<Class<*>>, args: Array<out Any?>): Boolean =
        types.size == args.size && types.indices.all { i ->
            val t = types[i]; val a = args[i]
            if (t.isPrimitive) a != null && boxed(t).isInstance(a) else a == null || t.isInstance(a)
        }

    private fun boxed(t: Class<*>): Class<*> = when (t) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        else -> t
    }

    private fun zeroOf(t: Class<*>): Any? = when (t) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Boolean::class.javaPrimitiveType -> false
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> 0.toChar()
        else -> null
    }
}
