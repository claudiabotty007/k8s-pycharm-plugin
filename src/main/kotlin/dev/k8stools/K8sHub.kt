package dev.k8stools

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.CopyOnWriteArrayList

data class Step(val tool: Tool, val args: List<String>, val workDir: String? = null)

/** Per-project coordinator: shared console, background execution, change events, confirmation prompts. */
@Service(Service.Level.PROJECT)
class K8sHub(private val project: Project) : Disposable {
    val console: ConsoleView by lazy {
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also { Disposer.register(this, it) }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(l: () -> Unit) {
        listeners += l
    }

    /** Call after kubeconfig changed (context added/removed/switched). Listeners run on the EDT. */
    fun fireChanged() = ui { listeners.forEach { it() } }

    fun log(text: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) =
        console.print(text + "\n", type)

    fun notify(message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("K8s Tools")
            .createNotification(message, type).notify(project)
    }

    fun showToolWindow() = ui { ToolWindowManager.getInstance(project).getToolWindow("Kubernetes Tools")?.show() }

    /** Runs [body] on a background thread with a progress indicator; K8sExceptions are reported, not thrown. */
    fun task(title: String, body: () -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    body()
                } catch (e: K8sException) {
                    log("✘ ${e.message}", ConsoleViewContentType.ERROR_OUTPUT)
                    notify("$title: ${e.message}", NotificationType.ERROR)
                }
            }
        })
    }

    /** Synchronous; call from within [task]. Logs every command and its output. Stops at the first failure. */
    fun execSteps(steps: List<Step>): Boolean {
        for (s in steps) {
            log("$ ${display(s)}", ConsoleViewContentType.SYSTEM_OUTPUT)
            val r = Cli.run(s.tool, s.args, s.workDir, timeoutMs = 300_000)
            if (r.stdout.isNotBlank()) log(r.stdout.trimEnd())
            if (r.stderr.isNotBlank()) log(r.stderr.trimEnd(), if (r.ok) ConsoleViewContentType.NORMAL_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT)
            if (!r.ok) return false
        }
        return true
    }

    private fun display(s: Step): String =
        (listOf(s.tool.exe) + s.args.map { if (it.startsWith("--token=")) "--token=****" else if (it.contains(' ')) "\"$it\"" else it })
            .joinToString(" ")

    /** Asks the user to confirm acting on the current context. Call from a background thread. */
    fun confirmOnCurrentContext(action: String, details: String): Boolean {
        val ctx = Kube.currentContext()
        if (ctx.isBlank()) throw K8sException("No current Kubernetes context. Select one in the Contexts tab first.")
        var ok = false
        ApplicationManager.getApplication().invokeAndWait({
            ok = Messages.showYesNoDialog(
                project, "$action\n\nCurrent context: $ctx\n$details", "Kubernetes: Confirm Target Cluster",
                "Continue", "Cancel", Messages.getWarningIcon()
            ) == Messages.YES
        }, ModalityState.any())
        if (!ok) log("Cancelled.", ConsoleViewContentType.SYSTEM_OUTPUT)
        return ok
    }

    fun deploy(paths: List<String>, namespace: String, dryRun: Boolean) {
        if (paths.isEmpty()) return
        showToolWindow()
        task(if (dryRun) "kubectl apply (dry run)" else "kubectl apply") {
            // A server-side dry run changes nothing, so it needs no confirmation.
            if (!dryRun && !confirmOnCurrentContext("Apply ${paths.size} file(s) with kubectl?", paths.joinToString("\n") { "  • $it" })) return@task
            val ok = execSteps(listOf(Step(Tool.KUBECTL, Kube.applyArgs(paths, namespace, dryRun))))
            finish("kubectl apply", ok)
        }
    }

    fun helmInstall(chart: HelmChart, release: String, namespace: String, createNs: Boolean, valuesFile: String, dryRun: Boolean) {
        showToolWindow()
        task("helm install ${chart.name}") {
            if (!dryRun && !confirmOnCurrentContext(
                    "Install ${chart.repoName}/${chart.name} ${chart.version} as release \"$release\"?",
                    "Namespace: ${namespace.ifBlank { "default" }}"
                )
            ) return@task
            val ok = execSteps(listOf(Step(Tool.HELM, HelmHub.installArgs(chart, release, namespace, createNs, valuesFile, dryRun))))
            finish("helm install $release", ok)
        }
    }

    /** Runs one kubectl command against the current context, optionally behind a confirmation prompt. */
    fun kubectl(title: String, args: List<String>, confirm: String? = null, notifyResult: Boolean = false, afterOk: () -> Unit = {}) {
        showToolWindow()
        task(title) {
            if (confirm != null && !confirmOnCurrentContext(confirm, "")) return@task
            val ok = execSteps(listOf(Step(Tool.KUBECTL, args)))
            if (notifyResult) finish(title, ok) else if (!ok) notify("$title failed (see console)", NotificationType.ERROR)
            if (ok) afterOk()
        }
    }

    fun finish(what: String, ok: Boolean) {
        log(if (ok) "✔ $what succeeded" else "✘ $what failed", if (ok) ConsoleViewContentType.SYSTEM_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT)
        notify("$what ${if (ok) "succeeded" else "failed (see console)"}", if (ok) NotificationType.INFORMATION else NotificationType.ERROR)
    }

    override fun dispose() {}
}

fun ui(r: () -> Unit) = ApplicationManager.getApplication().invokeLater(r, ModalityState.any())
fun io(r: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread(r)
