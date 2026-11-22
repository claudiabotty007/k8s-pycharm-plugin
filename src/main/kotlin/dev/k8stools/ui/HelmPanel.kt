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
import dev.k8stools.HelmChart
import dev.k8stools.HelmHub
import dev.k8stools.K8sException
import dev.k8stools.K8sHub
import dev.k8stools.io
import dev.k8stools.ui
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class HelmPanel(private val project: Project, private val hub: K8sHub) : JPanel(BorderLayout()) {
    private var charts: List<HelmChart> = emptyList()
    private val query = JBTextField().apply { emptyText.text = "Search Artifact Hub (e.g. nginx, postgresql, redis)" }
    private val status = JBLabel(" ")
    private val model = object : DefaultTableModel(arrayOf("Chart", "Repository", "Version", "App", "★", "Description"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
        override fun getColumnClass(c: Int): Class<*> = if (c == 4) Integer::class.java else String::class.java
    }
    private val table = JBTable(model)
    private val release = JBTextField()
    private val namespace = JBTextField("default")
    private val createNs = JBCheckBox("Create namespace", true)
    private val values = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("values.yaml"))
    }
    private val dryRun = JBCheckBox("Dry run")

    init {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setShowGrid(false)
        table.autoCreateRowSorter = true
        table.columnModel.getColumn(4).apply { maxWidth = 60 }
        table.columnModel.getColumn(5).preferredWidth = 400
        table.selectionModel.addListSelectionListener {
            selected()?.let { if (release.text.isBlank() || release.text in charts.map { c -> c.name }) release.text = it.name }
        }

        val searchBar = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(query, BorderLayout.CENTER)
            add(JButton("Search").apply { addActionListener { search() } }, BorderLayout.EAST)
        }
        query.addActionListener { search() }

        val form = FormBuilder.createFormBuilder()
            .addComponent(status)
            .addLabeledComponent("Release name:", release)
            .addLabeledComponent("Namespace:", namespace)
            .addLabeledComponent("Values file:", values)
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(createNs); add(dryRun)
                add(JButton("Install / Upgrade in Current Context").apply { addActionListener { install() } })
            })
            .panel.apply { border = JBUI.Borders.empty(6, 8) }

        add(searchBar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(form, BorderLayout.SOUTH)
    }

    private fun selected(): HelmChart? =
        table.selectedRow.takeIf { it >= 0 }?.let { charts.getOrNull(table.convertRowIndexToModel(it)) }

    private fun search() {
        val q = query.text
        status.text = "Searching Artifact Hub…"
        io {
            val res = try {
                HelmHub.search(q) to null
            } catch (e: K8sException) {
                emptyList<HelmChart>() to e.message
            }
            ui {
                charts = res.first
                model.rowCount = 0
                charts.forEach { model.addRow(arrayOf(it.name, it.repoName, it.version, it.appVersion, it.stars, it.description)) }
                status.text = res.second ?: "${charts.size} chart(s) found. Select one to install."
            }
        }
    }

    private fun install() {
        val chart = selected()
        if (chart == null) {
            status.text = "Select a chart first."
            return
        }
        val rel = release.text.trim().ifEmpty { chart.name }
        hub.helmInstall(chart, rel, namespace.text.trim(), createNs.isSelected, values.text.trim(), dryRun.isSelected)
    }
}
