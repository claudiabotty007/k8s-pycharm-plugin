package dev.k8stools.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.k8stools.Cli
import dev.k8stools.K8sHub
import dev.k8stools.LocalChart
import dev.k8stools.Step
import dev.k8stools.Tool
import dev.k8stools.ui
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** The Helm tab: charts from Artifact Hub, or a chart you are developing in a local folder. */
class HelmTab(project: Project, hub: K8sHub) : JPanel(BorderLayout()) {
    init {
        val tabs = JBTabbedPane()
        tabs.addTab("Artifact Hub", scrolled(HelmPanel(project, hub)))
        tabs.addTab("Local chart", LocalChartPanel(project, hub)) // already scrollable inside
        add(tabs, BorderLayout.CENTER)
    }
}

class CreateChartDialog(project: Project) : DialogWrapper(project) {
    private val name = JBTextField().apply { emptyText.text = "my-app (lowercase letters, digits, -)" }
    private val parent = TextFieldWithBrowseButton().apply {
        text = project.basePath?.replace('/', File.separatorChar) ?: ""
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Create the Chart In"))
    }

    init {
        title = "Create Helm Chart"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Chart name:", name)
        .addLabeledComponent("Create in folder:", parent)
        .panel.apply { preferredSize = Dimension(480, preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = name

    override fun doValidate(): ValidationInfo? {
        val n = name.text.trim()
        if (!LocalChart.validName(n)) return ValidationInfo("Use lowercase letters, digits and '-' (max 53 characters)", name)
        val p = parent.text.trim()
        if (!File(p).isDirectory) return ValidationInfo("Folder does not exist", parent)
        if (File(p, n).exists()) return ValidationInfo("'$n' already exists in that folder", name)
        return null
    }

    fun chartName() = name.text.trim()
    fun parentDir() = parent.text.trim()
}

class LocalChartPanel(private val project: Project, private val hub: K8sHub) : JPanel(BorderLayout()) {
    private val dir = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Helm Chart Folder (contains Chart.yaml)"))
    }
    private val info = JBLabel(" ")
    private val release = JBTextField().apply { emptyText.text = "defaults to the chart name" }
    private val namespace = JBTextField("default")
    private val values = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("values.yaml"))
    }
    private val createNs = JBCheckBox("Create namespace", true)
    private val dryRun = JBCheckBox("Dry run")

    init {
        val folderRow = JPanel(BorderLayout(8, 0)).apply {
            add(dir, BorderLayout.CENTER)
            add(JButton("Create New Chart…").apply { addActionListener { createChart() } }, BorderLayout.EAST)
        }
        fun btn(text: String, tip: String, action: () -> Unit) = JButton(text).apply { toolTipText = tip; addActionListener { action() } }
        val tools = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(btn("Open Chart.yaml", "Open Chart.yaml in the editor") { openChartFile("Chart.yaml") })
            add(btn("Open values.yaml", "Open values.yaml in the editor") { openChartFile("values.yaml") })
            add(btn("Lint", "helm lint") { lint() })
            add(btn("Template", "helm template - opens the rendered YAML in the editor") { template() })
            add(btn("Dependency Update", "helm dependency update") { dependencyUpdate() })
            add(btn("Package", "helm package into the parent folder") { pack() })
        }
        val install = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(createNs); add(dryRun)
            add(btn("Install / Upgrade in Current Context", "helm upgrade --install from this folder (asks first)") { install() })
        }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Chart folder:", folderRow)
            .addComponent(info)
            .addComponent(tools)
            .addVerticalGap(10)
            .addLabeledComponent("Release name:", release)
            .addLabeledComponent("Namespace:", namespace)
            .addLabeledComponent("Values file:", values)
            .addComponent(install)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(8) }
        // Scrollable, so the form can shrink and never squeezes the console out of a short tool window.
        add(JBScrollPane(form).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        dir.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateInfo()
            override fun removeUpdate(e: DocumentEvent) = updateInfo()
            override fun changedUpdate(e: DocumentEvent) = updateInfo()
        })
        updateInfo()
    }

    // ---------- state ----------

    private fun updateInfo() {
        val d = dir.text.trim()
        info.text = when {
            d.isEmpty() -> "Pick the folder of a chart you are working on, or create a new one with helm create."
            !LocalChart.isChartDir(d) -> "No Chart.yaml in this folder."
            else -> LocalChart.readMeta(d)?.let { m ->
                "${m.name} ${m.version}" + (if (m.appVersion.isNotEmpty()) " (app ${m.appVersion})" else "") + " - ${m.type} chart"
            } ?: "Chart.yaml has no name."
        }
    }

    private fun chartDir(): String? {
        val d = dir.text.trim()
        if (!LocalChart.isChartDir(d)) {
            info.text = "Choose a folder that contains Chart.yaml (or create a new chart first)."
            return null
        }
        return d
    }

    private fun releaseName(d: String) = release.text.trim().ifEmpty { LocalChart.readMeta(d)?.name ?: File(d).name }

    private fun openInEditor(f: File) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
        if (vf == null) info.text = "Not found: ${f.path}" else OpenFileDescriptor(project, vf).navigate(true)
    }

    private fun openChartFile(name: String) {
        val d = chartDir() ?: return
        openInEditor(File(d, name))
    }

    // ---------- helm commands (output goes to the console) ----------

    private fun runHelm(title: String, args: List<String>, workDir: String) {
        hub.showToolWindow()
        hub.task(title) {
            val ok = hub.execSteps(listOf(Step(Tool.HELM, args, workDir = workDir)))
            if (ok) hub.log("✔ $title succeeded", ConsoleViewContentType.SYSTEM_OUTPUT)
            else {
                hub.log("✘ $title failed", ConsoleViewContentType.ERROR_OUTPUT)
                hub.notify("$title failed (see console)", NotificationType.ERROR)
            }
        }
    }

    private fun lint() {
        val d = chartDir() ?: return
        runHelm("helm lint", LocalChart.lintArgs(d, values.text.trim()), d)
    }

    private fun dependencyUpdate() {
        val d = chartDir() ?: return
        runHelm("helm dependency update", LocalChart.dependencyUpdateArgs(d), d)
    }

    private fun pack() {
        val d = chartDir() ?: return
        val dest = File(d).absoluteFile.parent ?: d
        runHelm("helm package", LocalChart.packageArgs(d, dest), d)
    }

    /** Renders the chart locally (no cluster needed) and shows the result as a read-only YAML tab. */
    private fun template() {
        val d = chartDir() ?: return
        val rel = releaseName(d)
        val args = LocalChart.templateArgs(rel, d, namespace.text.trim(), values.text.trim())
        hub.showToolWindow()
        hub.task("helm template") {
            hub.log("$ helm ${args.joinToString(" ")}", ConsoleViewContentType.SYSTEM_OUTPUT)
            val r = Cli.run(Tool.HELM, args, workDir = d)
            if (!r.ok) {
                hub.log(r.message, ConsoleViewContentType.ERROR_OUTPUT)
                hub.notify("helm template failed (see console)", NotificationType.ERROR)
                return@task
            }
            hub.log("✔ Rendered ${r.stdout.lines().size} lines - opened in the editor", ConsoleViewContentType.SYSTEM_OUTPUT)
            ui {
                val vf = LightVirtualFile("$rel-rendered.yaml", r.stdout).apply { isWritable = false }
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    }

    private fun createChart() {
        val dlg = CreateChartDialog(project)
        if (!dlg.showAndGet()) return
        val name = dlg.chartName()
        val parent = dlg.parentDir()
        hub.showToolWindow()
        hub.task("helm create $name") {
            val ok = hub.execSteps(listOf(Step(Tool.HELM, LocalChart.createArgs(name), workDir = parent)))
            if (ok) {
                val path = File(parent, name).path
                hub.log("✔ Created chart at $path", ConsoleViewContentType.SYSTEM_OUTPUT)
                ui {
                    dir.text = path
                    release.text = ""
                    updateInfo()
                    openInEditor(File(path, "values.yaml"))
                }
            } else {
                hub.notify("helm create failed (see console)", NotificationType.ERROR)
            }
        }
    }

    private fun install() {
        val d = chartDir() ?: return
        val rel = releaseName(d)
        if (!LocalChart.validName(rel)) {
            info.text = "Release name must be lowercase letters, digits and '-' (max 53 characters)."
            return
        }
        hub.helmInstallLocal(d, rel, namespace.text.trim(), createNs.isSelected, values.text.trim(), dryRun.isSelected)
    }
}
