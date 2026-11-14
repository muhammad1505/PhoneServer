package dev.pocketshare.tunnel

import java.io.InputStream

interface ManagedTunnelProcess {
    val stdout: InputStream
    val stderr: InputStream

    fun waitFor(): Int

    fun destroy()

    fun destroyForcibly()
}

fun interface TunnelProcessLauncher {
    fun launch(spec: CloudflaredProcessSpec): ManagedTunnelProcess
}

class ProcessBuilderTunnelLauncher : TunnelProcessLauncher {
    override fun launch(spec: CloudflaredProcessSpec): ManagedTunnelProcess {
        require(spec.command.isNotEmpty()) { "Empty cloudflared command" }
        val builder = ProcessBuilder(spec.command)
        builder.directory(spec.workingDirectory)
        builder.environment().putAll(spec.environment)
        builder.redirectErrorStream(false)
        val process = builder.start()
        return object : ManagedTunnelProcess {
            override val stdout: InputStream = process.inputStream
            override val stderr: InputStream = process.errorStream

            override fun waitFor(): Int = process.waitFor()

            override fun destroy() {
                process.destroy()
            }

            override fun destroyForcibly() {
                process.destroyForcibly()
            }
        }
    }
}
