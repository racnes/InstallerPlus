package ltd.nextalone.pkginstallerplus.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import ltd.nextalone.pkginstallerplus.HookEntry
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

const val INSTALLER_V2_PKG = "com.android.packageinstaller.v2.ui"

internal fun logInfo(msg: String) {
    XposedBridge.log("InstallerPlus: $msg")
}

internal fun logError(msg: String, e: Throwable) {
    XposedBridge.log("InstallerPlus: $msg: $e")
    XposedBridge.log(e)
}

/**
 * Writes one line to the LSPosed log saying which installer variant is present in this process,
 * so a "module does nothing" report can be told apart from a "wrong hook target" report.
 */
fun logEnvironment(pkg: String) {
    val probes = listOf(
        "com.android.packageinstaller.PackageInstallerActivity",
        "$INSTALLER_V2_PKG.InstallLaunch",
        "$INSTALLER_V2_PKG.fragments.InstallConfirmationFragment",
        "$INSTALLER_V2_PKG.fragments.InstallationFragment"
    )
    val found = probes.joinToString(", ") { it.substringAfterLast('.') + "=" + (it.clazz != null) }
    val sdkFull = try {
        Build.VERSION::class.java.getField("SDK_INT_FULL").getInt(null).toString()
    } catch (e: Throwable) {
        "n/a"
    }
    logInfo("loaded in $pkg, SDK_INT=${Build.VERSION.SDK_INT}, SDK_INT_FULL=$sdkFull, $found")
}

internal val String.clazz: Class<*>?
    get() = try {
        HookEntry.lpClassLoader.loadClass(this)
    } catch (e: ClassNotFoundException) {
        null
    }

internal fun Member.hook(callback: XC_MethodHook) = try {
    val unhook = XposedBridge.hookMethod(this, callback)
    logInfo("hooked $this")
    unhook
} catch (e: Throwable) {
    logError("failed to hook $this", e)
    null
}

internal inline fun Member.hookBefore(crossinline hooker: (XC_MethodHook.MethodHookParam) -> Unit) = hook(object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam?) = try {
        hooker(param!!)
    } catch (e: Throwable) {
        logError("hookBefore callback failed", e)
    }
})

internal inline fun Member.hookAfter(crossinline hooker: (XC_MethodHook.MethodHookParam) -> Unit) = hook(object : XC_MethodHook() {
    override fun afterHookedMethod(param: MethodHookParam?) = try {
        hooker(param!!)
    } catch (e: Throwable) {
        logError("hookAfter callback failed", e)
    }
})

internal fun Member.replace(result: Any?) = this.replace { result }

internal inline fun <T : Any> Member.replace(crossinline hooker: (XC_MethodHook.MethodHookParam) -> T?) = hook(object : XC_MethodReplacement() {
    override fun replaceHookedMethod(param: MethodHookParam?): Any = {
        hooker(param!!)
    }
})

internal fun Class<*>.method(name: String): Method? = this.declaredMethods.run {
    this.forEach {
        if (it.name == name) {
            return it
        }
    }
    return null
}

@Throws(IllegalArgumentException::class)
internal fun Class<*>.method(name: String, vararg argsTypesAndReturnType: Any): Method? {
    var clazz = this
    val argc = argsTypesAndReturnType.size / 2
    val argt: Array<Class<*>?> = arrayOfNulls(argc)
    val argv = arrayOfNulls<Any>(argc)
    var returnType: Class<*>? = null
    if (argc * 2 + 1 == argsTypesAndReturnType.size) returnType = argsTypesAndReturnType[argsTypesAndReturnType.size - 1] as Class<*>
    var i: Int
    var ii: Int
    var m: Array<Method>
    var method: Method? = null
    var _argt: Array<Class<*>>
    i = 0
    while (i < argc) {
        argt[i] = argsTypesAndReturnType[argc + i] as Class<*>
        argv[i] = argsTypesAndReturnType[i]
        i++
    }
    loop_main@ do {
        m = clazz.declaredMethods
        i = 0
        loop@ while (i < m.size) {
            if (m[i].name == name) {
                _argt = m[i].parameterTypes
                if (_argt.size == argt.size) {
                    ii = 0
                    while (ii < argt.size) {
                        if (argt[ii] != _argt[ii]) {
                            i++
                            continue@loop
                        }
                        ii++
                    }
                    if (returnType != null && returnType != m[i].returnType) {
                        i++
                        continue
                    }
                    method = m[i]
                    break@loop_main
                }
            }
            i++
        }
    } while (Any::class.java != clazz.superclass.also { clazz = it })
    if (method != null) method.isAccessible = true
    return method
}

internal fun Class<*>.method(size: Int, returnType: Class<*>, condition: (method: Member) -> Boolean = { true }): Method? = this.declaredMethods.run {
    this.forEach {
        if (it.returnType == returnType && it.parameterTypes.size == size && condition(it)) {
            return it
        }
    }
    return null
}

internal fun Class<*>.method(name: String, size: Int, returnType: Class<*>, condition: (method: Member) -> Boolean = { true }): Method? = this.declaredMethods.run {
    this.forEach {
        if (it.name == name && it.returnType == returnType && it.parameterTypes.size == size && condition(it)) {
            return it
        }
    }
    return null
}

/**
 * Calls a no-argument method by name (searching superclasses, private ones included).
 * Public getters are far more stable across Android releases than private field names,
 * so prefer this over [get] when a getter exists. Returns null on any failure.
 */
internal fun Any.callOrNull(name: String): Any? {
    try {
        var c: Class<*>? = this.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterTypes.isEmpty()) {
                    m.isAccessible = true
                    return m.invoke(this)
                }
            }
            c = c.superclass
        }
    } catch (e: Throwable) {
        logError("callOrNull($name) on ${this.javaClass.name}", e)
    }
    return null
}

/**
 * Returns the value of the first non-null instance field whose declared type name ends with
 * [typeNameSuffix]. Used as a fallback when a field was renamed between Android releases.
 */
internal fun Any.fieldByType(typeNameSuffix: String): Any? {
    try {
        var c: Class<*>? = this.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (!Modifier.isStatic(f.modifiers) && f.type.name.endsWith(typeNameSuffix)) {
                    f.isAccessible = true
                    val v = f.get(this)
                    if (v != null) return v
                }
            }
            c = c.superclass
        }
    } catch (e: Throwable) {
        logError("fieldByType($typeNameSuffix) on ${this.javaClass.name}", e)
    }
    return null
}

internal val isV2InstallerAvailable: Boolean
    get() = "$INSTALLER_V2_PKG.InstallLaunch".clazz != null

internal fun PackageManager.getPackageInfoOrNull(pkgName: String): PackageInfo? =
    try {
        @Suppress("DEPRECATION", "InlinedApi")
        getPackageInfo(pkgName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
