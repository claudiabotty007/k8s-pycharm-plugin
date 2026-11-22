package dev.k8stools.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.k8stools.AwsSupport
import dev.k8stools.EksCluster
import dev.k8stools.K8sException
import dev.k8stools.K8sHub
import dev.k8stools.Kube
import dev.k8stools.Step
import dev.k8stools.Tool
import dev.k8stools.io
import dev.k8stools.ui
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/** Preset AWS CLI commands. `{cluster}` / `{region}` are filled from the selected cluster row. */
private class Preset(val label: String, val args: List<String>, val needsCluster: Boolean = false)

private val PRESETS = listOf(
    Preset("Who am I (sts get-caller-identity)", listOf("sts", "get-caller-identity")),
    Preset("List configured profiles", listOf("configure", "list-profiles")),
    Preset("Show effective configuration", listOf("configure", "list")),
    Preset("List enabled regions", listOf("ec2", "describe-regions", "--query", "Regions[].RegionName", "--output", "text")),
    Preset("List EKS clusters (region above)", listOf("eks", "list-clusters")),
    Preset("Describe selected cluster", listOf("eks", "describe-cluster", "--name", "{cluster}", "--region", "{region}"), true),
    Preset("List node groups of selected cluster", listOf("eks", "list-nodegroups", "--cluster-name", "{cluster}", "--region", "{region}"), true),
    Preset("Update kubeconfig for selected cluster", listOf("eks", "update-kubeconfig", "--name", "{cluster}", "--region", "{region}"), true),
    Preset("List ECR repositories", listOf("ecr", "describe-repositories", "--query", "repositories[].repositoryName", "--output", "text")),
    Preset("List EC2 instances", listOf("ec2", "describe-instances", "--query", "Reservations[].Instances[].[InstanceId,State.Name,InstanceType,PrivateIpAddress]", "--output", "table")),
    Preset("List S3 buckets", listOf("s3", "ls")),
    Preset("SSO login (selected profile)", listOf("sso", "login")),
)

/** Fills the scroll pane's viewport when it is taller than needed; scrolls vertically only when it is not. */
private class FillingPanel : JPanel(BorderLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = 16
    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = 64
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height >= preferredSize.height } ?: true
}

class AwsPanel(private val project: Project, private val hub: K8sHub) : JPanel(BorderLayout()) {
    private val defaultProfile = "(default)"
    private var clusters: List<EksCluster> = emptyList()

    private val profile = JComboBox(DefaultComboBoxModel(arrayOf(defaultProfile)))
    private val region = JComboBox<String>().apply { isEditable = true }
    private val allRegions = JBCheckBox("Scan all enabled regions", true)
    private val status = JBLabel(" ")
    private val model = object : DefaultTableModel(arrayOf("Cluster", "Region", "Status", "Version", "In kubeconfig"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model)
    private val presetBox = JComboBox(PRESETS.map { it.label }.toTypedArray())
    private val custom = JBTextField().apply { emptyText.text = "aws <arguments>, e.g. eks list-clusters --region us-east-1" }

    init {
        table.setShowGrid(false)

        val identityBtn = JButton("Who am I").apply { addActionListener { whoAmI() } }
        val findBtn = JButton("Find EKS clusters").apply { addActionListener { findClusters() } }
        val addBtn = JButton("Add Selected to Kubeconfig").apply { addActionListener { addSelected() } }
        profile.prototypeDisplayValue = "a-long-profile-name"
        region.prototypeDisplayValue = "ap-southeast-2xx"
        // Everything the user needs after a scan (find / add) is in the top rows, so it never scrolls out of view.
        val selectors = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JBLabel("Profile:")); add(profile); add(JBLabel("Region:")); add(region); add(allRegions)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(identityBtn); add(findBtn); add(addBtn) }
        val header = FormBuilder.createFormBuilder()
            .addComponent(selectors)
            .addComponent(actions)
            .addComponent(status)
            .panel.apply { border = JBUI.Borders.empty(6, 8) }

        val tablePanel = JPanel(BorderLayout()).apply {
            // Small minimum, no fixed size: the table takes all the room the tool window has to give.
            add(JBScrollPane(table).apply { preferredSize = JBUI.size(400, 120); minimumSize = JBUI.size(100, 80) }, BorderLayout.CENTER)
        }

        val runPreset = JButton("Run").apply { addActionListener { runPreset() } }
        val runCustom = JButton("Run").apply { addActionListener { runCustom() } }
        custom.addActionListener { runCustom() }
        val commands = FormBuilder.createFormBuilder()
            .addLabeledComponent("Common commands:", JPanel(BorderLayout(8, 0)).apply { add(presetBox, BorderLayout.CENTER); add(runPreset, BorderLayout.EAST) })
            .addLabeledComponent("Custom command:", JPanel(BorderLayout(8, 0)).apply { add(custom, BorderLayout.CENTER); add(runCustom, BorderLayout.EAST) })
            .panel.apply { border = JBUI.Borders.empty(6, 8) }

        // Header / table / commands stacked. The table stretches to fill the tool window; the tab only
        // scrolls when the window is shorter than the minimum the three parts need.
        val content = FillingPanel().apply {
            add(header, BorderLayout.NORTH)
            add(tablePanel, BorderLayout.CENTER)
            add(commands, BorderLayout.SOUTH)
        }
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        profile.addActionListener { loadDefaultRegion() }
        loadProfiles()
    }

    // ---------- helpers ----------

    private fun selectedProfile(): String = (profile.selectedItem as? String)?.takeIf { it != defaultProfile }.orEmpty()
    private fun regionText(): String = (region.editor.item as? String)?.trim().orEmpty()
    private fun currentEnv() = AwsSupport.env(selectedProfile(), regionText())
    private fun selectedClusters(): List<EksCluster> = table.selectedRows.toList().mapNotNull { clusters.getOrNull(it) }

    private fun loadProfiles() = io {
        val names = AwsSupport.profiles()
        ui {
            profile.model = DefaultComboBoxModel((listOf(defaultProfile) + names).toTypedArray())
            if (names.isEmpty()) status.text = "No AWS profiles found (is the aws CLI installed and configured? Path: Settings | Tools | Kubernetes Tools)."
        }
        loadDefaultRegion()
    }

    private fun loadDefaultRegion() {
        val p = selectedProfile()
        io {
            val r = AwsSupport.defaultRegion(p)
            ui { if (regionText().isEmpty() && r.isNotEmpty()) region.selectedItem = r }
        }
    }

    // ---------- actions ----------

    private fun whoAmI() {
        val env = currentEnv()
        status.text = "Checking credentials…"
        hub.task("AWS identity") {
            try {
                val id = AwsSupport.identity(env)
                hub.log("AWS identity: $id")
                ui { status.text = "Credentials OK: $id" }
            } catch (e: K8sException) {
                ui { status.text = "Credentials check failed: ${e.message?.lineSequence()?.firstOrNull()}" }
                throw e
            }
        }
    }

    private fun findClusters() {
        val env = currentEnv()
        val all = allRegions.isSelected
        val single = regionText()
        status.text = "Scanning for EKS clusters…"
        hub.task("Find EKS clusters") {
            try {
                val regions = if (all) AwsSupport.regions(env) else listOf(single.ifEmpty { throw K8sException("Set a region, or tick 'Scan all enabled regions'.") })
                val scan = AwsSupport.scan(env, regions)
                val known = try { Kube.contexts().map { it.cluster }.toSet() } catch (e: K8sException) { emptySet() }
                scan.errors.take(5).forEach { hub.log("  skipped $it") }
                hub.log("Found ${scan.clusters.size} EKS cluster(s) in ${regions.size} region(s)" + if (scan.errors.isNotEmpty()) " (${scan.errors.size} region(s) failed)" else "")
                ui {
                    clusters = scan.clusters
                    model.rowCount = 0
                    clusters.forEach { model.addRow(arrayOf(it.name, it.region, it.status, it.version, if (it.arn in known) "yes" else "")) }
                    status.text = "${clusters.size} cluster(s) in ${regions.size} region(s)" +
                            (if (scan.errors.isNotEmpty()) " - ${scan.errors.size} region(s) failed, e.g. ${scan.errors.first()}" else "") +
                            if (clusters.isNotEmpty()) ". Select rows and add them to your kubeconfig." else ""
                }
            } catch (e: K8sException) {
                ui { status.text = "Scan failed: ${e.message?.lineSequence()?.firstOrNull()}" }
                throw e
            }
        }
    }

    private fun addSelected() {
        val sel = selectedClusters()
        if (sel.isEmpty()) {
            status.text = "Select one or more clusters in the table first."
            return
        }
        val env = currentEnv()
        hub.showToolWindow()
        hub.task("Add EKS clusters to kubeconfig") {
            var allOk = true
            for (c in sel) {
                val ok = hub.execSteps(listOf(Step(Tool.AWS, listOf("eks", "update-kubeconfig", "--name", c.name, "--region", c.region), env = env)))
                allOk = allOk && ok
            }
            hub.finish("Add ${sel.size} EKS cluster(s) to kubeconfig", allOk)
            hub.fireChanged() // refreshes Contexts, Explorer and the header
            val known = try { Kube.contexts().map { it.cluster }.toSet() } catch (e: K8sException) { emptySet() }
            ui { clusters.forEachIndexed { i, c -> model.setValueAt(if (c.arn in known) "yes" else "", i, 4) } }
        }
    }

    private fun runPreset() {
        val p = PRESETS[presetBox.selectedIndex.coerceAtLeast(0)]
        val sel = selectedClusters().firstOrNull()
        if (p.needsCluster && sel == null) {
            status.text = "Select a cluster in the table first."
            return
        }
        runAws(p.label, p.args.map { it.replace("{cluster}", sel?.name.orEmpty()).replace("{region}", sel?.region.orEmpty()) })
    }

    private fun runCustom() {
        val tokens = ParametersListUtil.parse(custom.text.trim()).let { if (it.firstOrNull() == "aws") it.drop(1) else it }
        if (tokens.isEmpty()) return
        runAws("aws " + tokens.joinToString(" "), tokens)
    }

    private fun runAws(title: String, args: List<String>) {
        val env = currentEnv()
        hub.showToolWindow()
        hub.task(title) {
            val ok = hub.execSteps(listOf(Step(Tool.AWS, args, env = env)))
            if (!ok) hub.notify("$title failed (see console)", NotificationType.ERROR)
        }
    }
}
