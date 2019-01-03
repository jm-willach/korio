package com.soywiz.korio

import com.soywiz.kds.*
import com.soywiz.klock.*
import com.soywiz.korio.async.*
import com.soywiz.korio.crypto.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.net.*
import com.soywiz.korio.net.http.*
import com.soywiz.korio.net.ws.*
import com.soywiz.korio.stream.*
import com.soywiz.korio.util.*
import kotlin.collections.set
import kotlin.reflect.*
import kotlin.coroutines.*
import kotlinx.coroutines.*

import kotlinx.cinterop.*
import platform.posix.*

actual open class IOException actual constructor(msg: String) : Exception(msg)
actual open class EOFException actual constructor(msg: String) : IOException(msg)
actual open class FileNotFoundException actual constructor(msg: String) : IOException(msg)

actual object KorioNative {
	actual val currentThreadId: Long get() = -1L // @TODO

	actual val platformName: String get() = "native"
	actual val rawOsName: String = com.soywiz.korio.TARGET_INFO

	actual fun getRandomValues(data: ByteArray): Unit {
		for (n in 0 until data.size) {
			data[n] = platform.posix.rand().toByte()
		}
	}

	// @TODO: kotlin-native by lazy/atomicLazy
	//val tmpdir: String by atomicLazy { getenv("TMPDIR") ?: getenv("TEMP") ?: getenv("TMP") ?: "/tmp" }
	//
	//val cwd by atomicLazy {
	//	memScoped {
	//		val data = allocArray<ByteVar>(1024)
	//		getcwd(data, 1024)
	//		data.toKString()
	//	}
	//}

	val tmpdir: String = getenv("TMPDIR") ?: getenv("TEMP") ?: getenv("TMP") ?: "/tmp"

	val cwd: String = com.soywiz.korio.nativeCwd()

	actual fun rootLocalVfs(): VfsFile = localVfs(cwd)
	actual fun applicationVfs(): VfsFile = localVfs(cwd)
	actual fun applicationDataVfs(): VfsFile = localVfs(cwd)
	actual fun cacheVfs(): VfsFile = MemoryVfs()
	actual fun externalStorageVfs(): VfsFile = localVfs(cwd)
	actual fun userHomeVfs(): VfsFile = localVfs(cwd)
	actual fun tempVfs(): VfsFile = localVfs(tmpdir)
	actual fun localVfs(path: String): VfsFile = LocalVfsNative()[path]
	actual val ResourcesVfs: VfsFile get() = applicationDataVfs().jail()

	actual val File_separatorChar: Char get() = '/'

	actual val websockets: WebSocketClientFactory get() = com.soywiz.korio.net.ws.RawSocketWebSocketClientFactory
	actual val systemLanguageStrings: List<String> get() = listOf("english")

	actual fun Thread_sleep(time: Long): Unit {
		platform.posix.usleep((time * 1000L).toUInt())
	}

	actual val httpFactory: HttpFactory = object : HttpFactory {
		override fun createClient(): HttpClient = NativeHttpClient()
		override fun createServer(): HttpServer = KorioNativeDefaults.createServer()
	}

	actual fun enterDebugger(): Unit {
		println("enterDebugger")
	}

	actual fun printStackTrace(e: Throwable): Unit {
		e.printStackTrace()
	}

	actual fun getenv(key: String): String? = platform.posix.getenv(key)?.toKString()

	//actual fun asyncEntryPoint(context: CoroutineContext, callback: suspend () -> Unit) = runBlocking(context) { callback() }
	actual fun asyncEntryPoint(context: CoroutineContext, callback: suspend () -> Unit) = runBlocking { callback() }

	actual fun suspendTest(callback: suspend () -> Unit): Unit {
		runBlocking { callback() }
	}
}

class NativeHttpClient : HttpClient() {
	suspend override fun requestInternal(
		method: Http.Method, url: String, headers: Http.Headers, content: AsyncStream?
	): Response = TODO()
}

class LocalVfsNative : LocalVfs() {
	val that = this
	override val absolutePath: String = ""

	fun resolve(path: String) = path

	override suspend fun exec(
		path: String, cmdAndArgs: List<String>, env: Map<String, String>, handler: VfsProcessHandler
	): Int = run {
		TODO("LocalVfsNative.exec")
	}

	override suspend fun open(path: String, mode: VfsOpenMode): AsyncStream {
		val rpath = resolve(path)
		var fd: CPointer<FILE>? = platform.posix.fopen(rpath, mode.cmode) ?: throw FileNotFoundException("Can't find '$rpath'")

		fun checkFd() {
			if (fd == null) error("Error with file '$rpath'")
		}

		return object : AsyncStreamBase() {
			override suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
				checkFd()
				//println("AsyncStreamBase.read($position, buffer=${buffer.size}, offset=$offset, len=$len)")
				return buffer.usePinned { pin ->
					if (len > 0) {
						val totalLen = getLength()
						platform.posix.fseek(fd, position.convert(), platform.posix.SEEK_SET)
						var result = platform.posix.fread(pin.addressOf(offset), 1, len.convert(), fd).toInt()

						//println("AsyncStreamBase:position=$position,len=$len,totalLen=$totalLen,result=$result,presult=$presult,ferror=${platform.posix.ferror(fd)},feof=${platform.posix.feof(fd)}")
						return result
					} else {
						0
					}
				}
			}

			override suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) {
				checkFd()
				return buffer.usePinned { pin ->
					if (len > 0) {
						//platform.posix.fseeko64(fd, position, platform.posix.SEEK_SET)
						platform.posix.fseek(fd, position.convert(), platform.posix.SEEK_SET)

						platform.posix.fwrite(pin.addressOf(offset), 1, len.convert(), fd)
					}
					Unit
				}
			}

			override suspend fun setLength(value: Long): Unit {
				checkFd()
				platform.posix.truncate(rpath, value.convert())
			}

			override suspend fun getLength(): Long {
				checkFd()
				//platform.posix.fseeko64(fd, 0L, platform.posix.SEEK_END)
				//return platform.posix.ftello64(fd)
				platform.posix.fseek(fd, 0L.convert(), platform.posix.SEEK_END)
				return platform.posix.ftell(fd).toLong()
			}
			override suspend fun close() {
				checkFd()
				platform.posix.fclose(fd)
				fd = null
			}

			override fun toString(): String = "$that($path)"
		}.toAsyncStream()
	}

	override suspend fun setSize(path: String, size: Long): Unit = run {
		platform.posix.truncate(resolve(path), size.convert())
		Unit
	}

	override suspend fun stat(path: String): VfsStat = run {
		val rpath = resolve(path)
		val result = memScoped {
			val s = alloc<stat>()
			if (platform.posix.stat(rpath, s.ptr) == 0) {
				val size: Long = s.st_size.toLong()
				val isDirectory = (s.st_mode.toInt() and S_IFDIR) != 0
				createExistsStat(rpath, isDirectory, size)
			} else {
				createNonExistsStat(rpath)
			}
		}
		result
	}

	override suspend fun list(path: String): SuspendingSequence<VfsFile> = run {
		val dir = opendir(resolve(path))
		val out = ArrayList<VfsFile>()
		if (dir != null) {
			while (true) {
				val dent = readdir(dir) ?: break
				val name = dent.pointed.d_name.toKString()
				out += file(name)
			}
			closedir(dir)
		}
		SuspendingSequence(out)
	}

	override suspend fun mkdir(path: String, attributes: List<Attribute>): Boolean = run {
		com.soywiz.korio.doMkdir(resolve(path), "0777".toInt(8).convert()) == 0
	}

	override suspend fun touch(path: String, time: DateTime, atime: DateTime): Unit = run {
		// @TODO:
		println("TODO:LocalVfsNative.touch")
	}

	override suspend fun delete(path: String): Boolean = run {
		platform.posix.unlink(resolve(path)) == 0
	}

	override suspend fun rmdir(path: String): Boolean = run {
		platform.posix.rmdir(resolve(path)) == 0
	}

	override suspend fun rename(src: String, dst: String): Boolean = run {
		platform.posix.rename(resolve(src), resolve(dst)) == 0
	}

	override suspend fun watch(path: String, handler: (FileEvent) -> Unit): Closeable {
		// @TODO:
		println("TODO:LocalVfsNative.watch")
		return DummyCloseable
	}

	override fun toString(): String = "LocalVfs"
}