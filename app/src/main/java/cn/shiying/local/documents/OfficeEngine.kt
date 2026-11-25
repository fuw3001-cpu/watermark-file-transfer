/* LibreOfficeKit integration adapted from ClearPDF (MIT), Copyright (c) 2026 Chethan616.
 * Source and full license: third_party/clearpdf. Native engine: LibreOffice-Lite v2.0, MPL-2.0.
 */
package cn.shiying.local.documents

import android.app.ActivityManager
import android.app.Service
import android.content.*
import android.net.Uri
import android.os.*
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office

object OfficeEngine {
    suspend fun convert(context: Context, input: File, output: File, format: String) = runInterruptible(Dispatchers.IO) {
        require(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") { "Word/Excel 转换引擎目前需要 ARM64 安卓手机" }
        val connected = CountDownLatch(1)
        val done = CountDownLatch(1)
        val messenger = AtomicReference<Messenger?>()
        val result = AtomicReference<Bundle?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { messenger.set(Messenger(service)); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { connected.countDown(); done.countDown() }
            override fun onBindingDied(name: ComponentName) { connected.countDown(); done.countDown() }
        }
        val reply = Messenger(Handler(Looper.getMainLooper()) { msg -> result.set(msg.data); done.countDown(); true })
        require(context.bindService(Intent(context, OfficeService::class.java), connection, Context.BIND_AUTO_CREATE)) { "无法启动文档转换进程" }
        try {
            require(connected.await(15, TimeUnit.SECONDS)) { "文档引擎启动超时" }
            val remote = messenger.get() ?: error("文档引擎已停止")
            remote.send(Message.obtain(null, 1).apply {
                data = Bundle().apply { putString("input", input.absolutePath); putString("output", output.absolutePath); putString("format", format) }
                replyTo = reply
            })
            require(done.await(180, TimeUnit.SECONDS)) { "文档转换超时，请减少文件页数后重试" }
            val data = result.get() ?: error("文档引擎意外退出，请换一个文件重试")
            require(data.getBoolean("ok")) { data.getString("error") ?: "文档转换失败" }
            require(output.isFile && output.length() > 0) { "文档引擎未生成有效结果" }
        } finally {
            runCatching { context.unbindService(connection) }
            stop(context)
        }
    }
    private fun stop(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.filter { it.processName == "${context.packageName}:office" }
            ?.forEach { android.os.Process.killProcess(it.pid) }
    }
}

class OfficeService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger
    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("office-conversion").apply { start() }
        messenger = Messenger(Handler(worker.looper) { msg ->
            val result = Bundle()
            try {
                val input = File(requireNotNull(msg.data.getString("input")))
                val output = File(requireNotNull(msg.data.getString("output")))
                require(input.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator))
                require(output.canonicalPath.startsWith(input.parentFile!!.canonicalPath + File.separator))
                Runtime.convert(this, input, output, requireNotNull(msg.data.getString("format")))
                result.putBoolean("ok", true)
            } catch (e: Throwable) { result.putString("error", e.message ?: "文档引擎失败") }
            runCatching { msg.replyTo.send(Message.obtain(null, 2).apply { data = result }) }
            true
        })
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { worker.quitSafely(); super.onDestroy() }
}

private object Runtime {
    fun convert(context: Context, input: File, output: File, format: String) {
        require(format in setOf("pdf", "xlsx", "csv"))
        val root = File(context.noBackupFilesDir, "libreoffice-lite-2")
        if (!File(root, ".ready").exists()) {
            fun copy(asset: String, target: File) {
                val children = context.assets.list(asset).orEmpty()
                if (children.isEmpty()) { target.parentFile?.mkdirs(); context.assets.open(asset).use { source -> target.outputStream().use { source.copyTo(it) } } }
                else children.forEach { copy("$asset/$it", File(target, it)) }
            }
            copy("lo", root)
            File(root, ".ready").writeText("2.0")
        }
        val fd = Os.open(root.path, OsConstants.O_RDONLY, 0)
        Os.dup2(fd, 1023)
        Os.close(fd)
        val libraries = File(root, "lib").apply { mkdirs() }
        for (name in listOf("libc++_shared.so", "liblo-native-code.so")) {
            val target = File(libraries, name)
            if (!target.exists()) Os.symlink(File(context.applicationInfo.nativeLibraryDir, name).absolutePath, target.absolutePath)
        }
        System.loadLibrary("c++_shared")
        System.loadLibrary("lo-native-code")
        val tmp = File(context.cacheDir, "office-temp").apply { mkdirs() }
        val fontCache = File(context.cacheDir, "office-fonts").apply { mkdirs() }
        val config = File(root, "etc/fonts/fonts.conf").apply { parentFile?.mkdirs() }
        val bundledFonts = listOf("share/fonts", "share/fonts/truetype", "user/fonts").map { File(root, it) }.filter { it.exists() }
        config.writeText("""<?xml version="1.0"?><fontconfig><dir>/system/fonts</dir><dir>/product/fonts</dir>${bundledFonts.joinToString("") { "<dir>${it.absolutePath}</dir>" }}<cachedir>${fontCache.absolutePath}</cachedir>
            <alias binding="same"><family>Calibri</family><prefer><family>Carlito</family></prefer></alias>
            <alias binding="same"><family>Arial</family><prefer><family>Liberation Sans</family></prefer></alias>
            <alias binding="same"><family>Times New Roman</family><prefer><family>Liberation Serif</family></prefer></alias></fontconfig>""")
        // Disable macros in the private Office profile. No user configuration is imported.
        File(root, "user/registrymodifications.xcu").apply { parentFile?.mkdirs(); writeText("""<?xml version="1.0"?><oor:items xmlns:oor="http://openoffice.org/2001/registry"><item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop></item></oor:items>""") }
        val fundamental = File(root, "program/fundamentalrc")
        val userLayer = "user:!file://${File(root, "user/registrymodifications.xcu").absolutePath}"
        val configuration = fundamental.readText().lineSequence().map { line ->
            if (line.startsWith("CONFIGURATION_LAYERS=") && !line.contains("registrymodifications.xcu")) "$line $userLayer" else line
        }.joinToString("\n")
        fundamental.writeText(configuration)
        for ((key, value) in mapOf("FONTCONFIG_FILE" to config.absolutePath, "FONTCONFIG_PATH" to config.parentFile!!.absolutePath,
            "TMPDIR" to tmp.absolutePath, "SAL_LOG" to "-WARN-INFO", "SAL_LOK_OPTIONS" to "compact_fonts")) {
            LibreOfficeKit.putenv("$key=$value")
            Os.setenv(key, value, true)
        }
        require(LibreOfficeKit.initializeNative(root.absolutePath, tmp.absolutePath, context.packageResourcePath, context.assets)) { "文档引擎初始化失败" }
        val office = Office(LibreOfficeKit.getLibreOfficeKitHandle() ?: error("文档引擎句柄不可用"))
        val url = Uri.fromFile(input).toString()
        office.setOptionalFeatures(Office.FEATURE_DOCUMENT_PASSWORD)
        office.callback = { type, _ -> if (type == Office.LOK_CALLBACK_DOCUMENT_PASSWORD) office.setDocumentPassword(url, null) }
        val document = office.documentLoad(url) ?: error("无法打开文档；暂不支持加密文件")
        try { document.saveAs(Uri.fromFile(output).toString(), format, if (format == "csv") "44,34,76" else null) }
        finally { document.destroy() }
    }
}
