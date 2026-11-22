package dev.k8stools.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.k8stools.K8sException
import dev.k8stools.K8sHub
import dev.k8stools.Kube
import dev.k8stools.NewContext
import dev.k8stools.TerraformSupport
import dev.k8stools.TfOutput
import dev.k8stools.io
import dev.k8stools.ui
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class TerraformPanel(private val project: Project, private val hub: K8sHub) : JPanel(BorderLayout()) {
    private var outputs: List<TfOutput> = emptyList()
    private val dir = TextFieldWithBrowseButton().apply {
        text = project.basePath?.replace('/', java.io.File.separatorChar) ?: ""
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Terraform Working Directory"))
    }
    private val model = object : DefaultTableModel(arrayOf("Output", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model)
    private val status = JBLabel(" ")
    private val ctxName = JBTextField()
    private val endpoint = JBTextField()
    private val ca = JBTextField().apply { emptyText.text = "optional - PEM or base64 (auto-detected from outputs)" }
    private val user = JBTextField().apply { emptyText.text = "optional - existing kubeconfig user (e.g. an exec/aws/gcloud user)" }
    private val namespace = JBTextField().apply { emptyText.text = "default" }
    private val switchAfter = JBCheckBox("Switch to the new context", true)

    init {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setShowGrid(false)
        table.selectionModel.addListSelectionListener {
            outputs.getOrNull(table.selectedRow)?.let { if (TerraformSupport.looksLikeEndpoint(it)) endpoint.text = TerraformSupport.normalizeEndpoint(it.value) }
        }

        val top = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(JBLabel("Terraform directory:"), BorderLayout.WEST)
            add(dir, BorderLayout.CENTER)
            add(JButton("Read terraform output").apply { addActionListener { readOutputs() } }, BorderLayout.EAST)
        }
        val form = FormBuilder.createFormBuilder()
            .addComponent(status)
            .addLabeledComponent("Context name:", ctxName)
            .addLabeledComponent("Cluster endpoint:", endpoint)
            .addLabeledComponent("CA certificate:", ca)
            .addLabeledComponent("User:", user)
            .addLabeledComponent("Namespace:", namespace)
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(switchAfter)
                add(JButton("Create Context").apply { addActionListener { create() } })
            })
            .panel.apply { border = JBUI.Borders.empty(6, 8) }

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(form, BorderLayout.SOUTH)
    }

    private fun readOutputs() {
        val d = dir.text.trim()
        status.text = "Running terraform output…"
        io {
            val res = try {
                TerraformSupport.outputs(d) to null
            } catch (e: K8sException) {
                emptyList<TfOutput>() to e.message
            }
            ui {
                outputs = res.first
                model.rowCount = 0
                outputs.forEach { model.addRow(arrayOf(it.name, if (it.value.length > 120) it.value.take(120) + "…" else it.value.replace('\n', ' '))) }
                val g = TerraformSupport.guess(outputs)
                g.endpoint?.let { endpoint.text = TerraformSupport.normalizeEndpoint(it.value) }
                g.caData?.let { ca.text = it.value.trim() }
                if (ctxName.text.isBlank()) g.clusterName?.let { ctxName.text = it.value }
                status.text = res.second?.let { "terraform: $it" } ?: when {
                    outputs.isEmpty() -> "No outputs found (has `terraform apply` been run in this directory?)"
                    g.endpoint != null -> "Detected cluster endpoint from output \"${g.endpoint.name}\" - review and create the context."
                    else -> "No endpoint detected - select the output row holding the cluster URL."
                }
            }
        }
    }

    private fun create() {
        val name = ctxName.text.trim()
        val url = endpoint.text.trim()
        if (name.isEmpty() || url.isEmpty()) {
            status.text = "Context name and cluster endpoint are required."
            return
        }
        val caText = ca.text
        val spec = NewContext(name, TerraformSupport.normalizeEndpoint(url), namespace.text.trim(), user.text.trim())
        val sw = switchAfter.isSelected
        hub.showToolWindow()
        hub.task("Create context from Terraform") {
            val caFile = if (caText.isNotBlank()) TerraformSupport.writeCaFile(name, caText) else ""
            Kube.addContext(spec.copy(caFile = caFile))
            hub.log("Created context \"$name\" -> ${spec.server}")
            if (sw) {
                Kube.useContext(name)
                hub.log("Switched to context \"$name\"")
            }
            hub.notify("Context \"$name\" created from Terraform output")
            hub.fireChanged()
        }
    }
}
