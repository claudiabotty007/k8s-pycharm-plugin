package dev.k8stools

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets

class K8sException(message: String) : Exception(message)

data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    val message: String get() = stderr.ifBlank { stdout }.trim()
}

fun CliResult.orThrow(): CliResult {
    if (!ok) throw K8sException(message.ifBlank { "Command failed with exit code $exitCode" })
    return this
}

enum class Tool(val exe: String) { KUBECTL("kubectl"), HELM("helm"), TERRAFORM("terraform") }

@Service(Service.Level.APP)
@State(name = "K8sToolsSettings", storages = [Storage("k8sTools.xml")])
class K8sSettings : PersistentStateComponent<K8sSettings.State> {
    class State {
        var kubectlPath: String = ""
        var helmPath: String = ""
        var terraformPath: String = ""
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): K8sSettings = service()
    }
}

object Cli {
    /** Configured path > PATH lookup > well-known locations > bare name. */
    fun resolve(tool: Tool): String {
        val st = K8sSettings.getInstance().state
        val configured = when (tool) {
            Tool.KUBECTL -> st.kubectlPath
            Tool.HELM -> st.helmPath
            Tool.TERRAFORM -> st.terraformPath
        }.trim()
        if (configured.isNotEmpty()) return configured
        PathEnvironmentVariableUtil.findInPath(tool.exe)?.let { return it.absolutePath }
        if (tool == Tool.TERRAFORM) {
            // The JetBrains Terraform plugin downloads its own binary here.
            val f = File(System.getProperty("user.home"), ".jetbrains/terraform/" + tool.exe + if (SystemInfo.isWindows) ".exe" else "")
            if (f.isFile) return f.absolutePath
        }
        return tool.exe
    }

    fun run(
        tool: Tool,
        args: List<String>,
        workDir: String? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Int = 60_000,
    ): CliResult {
        val cmd = GeneralCommandLine(listOf(resolve(tool)) + args).withCharset(StandardCharsets.UTF_8)
        if (workDir != null) cmd.withWorkDirectory(workDir)
        if (env.isNotEmpty()) cmd.withEnvironment(env)
        return try {
            val out = CapturingProcessHandler(cmd).runProcess(timeoutMs)
            if (out.isTimeout) CliResult(-1, out.stdout, "${tool.exe} timed out after ${timeoutMs / 1000}s")
            else CliResult(out.exitCode, out.stdout, out.stderr)
        } catch (e: ExecutionException) {
            CliResult(
                -1, "",
                "Cannot run '${tool.exe}': ${e.message}. Install it or set its path in Settings | Tools | Kubernetes Tools."
            )
        }
    }
}
