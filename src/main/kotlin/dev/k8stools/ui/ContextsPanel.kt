package dev.k8stools.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.k8stools.K8sException
import dev.k8stools.K8sHub
import dev.k8stools.Kube
import dev.k8stools.KubeContext
import dev.k8stools.io
import dev.k8stools.ui
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class ContextsPanel(private val project: Project, private val hub: K8sHub) : JPanel(BorderLayout()) {
    private var contexts: List<KubeContext> = emptyList()
    private val model = object : DefaultTableModel(arrayOf("", "Context", "Cluster", "User", "Namespace"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model)
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }

    init {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setShowGrid(false)
        table.columnModel.getColumn(0).apply { minWidth = 28; maxWidth = 28 }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) switchSelected()
            }
        })

        val panel = ToolbarDecorator.createDecorator(table)
            .setAddAction { addContext() }
            .setRemoveAction { removeSelected() }
            .setRemoveActionUpdater { table.selectedRow >= 0 }
            .disableUpDownActions()
            .addExtraAction(object : DumbAwareAction("Switch to Selected Context", "Make it the current context (double-click)", AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) = switchSelected()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            .addExtraAction(object : DumbAwareAction("Import kubeconfig…", "Merge another kubeconfig file into ~/.kube/config", AllIcons.ToolbarDecorator.Import) {
                override fun actionPerformed(e: AnActionEvent) = importKubeconfig()
            })
            .addExtraAction(object : DumbAwareAction("Refresh", "Reload contexts", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
            .createPanel()

        add(status, BorderLayout.NORTH)
        add(panel, BorderLayout.CENTER)
        hub.addChangeListener { refresh() }
        refresh()
    }

    private fun selected(): KubeContext? = contexts.getOrNull(table.selectedRow)

    fun refresh() {
        io {
            val result = try {
                Kube.contexts() to null
            } catch (e: K8sException) {
                emptyList<KubeContext>() to e.message
            }
            ui {
                contexts = result.first
                model.rowCount = 0
                contexts.forEach { model.addRow(arrayOf(if (it.current) "●" else "", it.name, it.cluster, it.user, it.namespace)) }
                status.text = result.second?.let { "kubectl: $it" }
                    ?: if (contexts.isEmpty()) "No contexts found - add one with +, import a kubeconfig, or create one from Terraform output." else " "
            }
        }
    }

    private fun switchSelected() {
        val ctx = selected() ?: return
        if (ctx.current) return
        hub.task("Switch context") {
            Kube.useContext(ctx.name)
            hub.log("Switched to context \"${ctx.name}\"")
            hub.fireChanged()
        }
    }

    private fun addContext() {
        val dlg = AddContextDialog(project)
        if (!dlg.showAndGet()) return
        val spec = dlg.result()
        hub.task("Add context") {
            Kube.addContext(spec)
            hub.log("Added context \"${spec.name}\"")
            hub.fireChanged()
        }
    }

    private fun removeSelected() {
        val ctx = selected() ?: return
        val ok = Messages.showYesNoDialog(
            project, "Remove context \"${ctx.name}\" from kubeconfig?\n(The cluster and user entries are kept.)",
            "Remove Context", "Remove", "Cancel", Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return
        hub.task("Remove context") {
            Kube.deleteContext(ctx.name)
            hub.log("Removed context \"${ctx.name}\"")
            hub.fireChanged()
        }
    }

    private fun importKubeconfig() {
        val file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Select kubeconfig to Import"), project, null) ?: return
        hub.task("Import kubeconfig") {
            Kube.importKubeconfig(file.path)
            hub.log("Imported ${file.path} (a backup of the previous kubeconfig was saved next to it)")
            hub.fireChanged()
        }
    }
}
