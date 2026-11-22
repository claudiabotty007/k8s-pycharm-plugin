package dev.k8stools.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.k8stools.K8sHub
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class DeployPanel(private val project: Project, private val hub: K8sHub) : JPanel(BorderLayout()) {
    private val model = CollectionListModel<VirtualFile>()
    private val list = JBList(model)
    private val onlyManifests = JBCheckBox("Only Kubernetes manifests (apiVersion + kind)", true)
    private val namespace = JBTextField(14).apply { emptyText.text = "context default" }
    private val dryRun = JBCheckBox("Server-side dry run")

    init {
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        ListSpeedSearch.installOn(list) { it.name }
        list.cellRenderer = object : ColoredListCellRenderer<VirtualFile>() {
            override fun customizeCellRenderer(l: JList<out VirtualFile>, v: VirtualFile?, i: Int, sel: Boolean, focus: Boolean) {
                v ?: return
                append(v.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val base = project.basePath?.replace('\\', '/') ?: ""
                val rel = v.parent?.path?.removePrefix(base)?.trim('/') ?: ""
                if (rel.isNotEmpty()) append("   $rel", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JButton("Rescan project").apply { addActionListener { rescan() } })
            add(JButton("Add file…").apply { addActionListener { addFile() } })
            add(onlyManifests.apply { addActionListener { rescan() } })
        }
        val bottom = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            add(JBLabel("Namespace:"))
            add(namespace)
            add(dryRun)
            add(JButton("Deploy Selected to Current Context").apply { addActionListener { deploy() } })
        }
        border = JBUI.Borders.empty()
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        rescan()
    }

    private val manifestRe = Regex("(?m)^apiVersion:\\s*\\S+")
    private val kindRe = Regex("(?m)^kind:\\s*\\S+")

    private fun isManifest(f: VirtualFile): Boolean {
        if (f.length > 1_000_000) return false
        val text = String(f.contentsToByteArray(), StandardCharsets.UTF_8)
        return manifestRe.containsMatchIn(text) && kindRe.containsMatchIn(text)
    }

    private fun rescan() {
        val filter = onlyManifests.isSelected
        ReadAction.nonBlocking<List<VirtualFile>> {
            val scope = GlobalSearchScope.projectScope(project)
            val index = ProjectFileIndex.getInstance(project)
            (FilenameIndex.getAllFilesByExt(project, "yaml", scope) + FilenameIndex.getAllFilesByExt(project, "yml", scope))
                .filter { index.isInContent(it) && (!filter || isManifest(it)) }
                .sortedBy { it.path }
        }
            .inSmartMode(project)
            .expireWith(hub)
            .finishOnUiThread(ModalityState.any()) { files ->
                val selected = list.selectedValuesList.toSet()
                model.replaceAll(files)
                list.setSelectedIndices(files.indices.filter { files[it] in selected }.toIntArray())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun addFile() {
        val files = FileChooser.chooseFiles(
            FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().withTitle("Select YAML Manifests"), project, null
        )
        files.filter { it !in model.items }.forEach { model.add(it) }
        list.setSelectedIndices(files.mapNotNull { model.items.indexOf(it).takeIf { i -> i >= 0 } }.toIntArray())
    }

    private fun deploy() {
        val files = list.selectedValuesList
        if (files.isEmpty()) {
            hub.log("Select at least one YAML file to deploy.")
            return
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        hub.deploy(files.map { it.path }, namespace.text.trim(), dryRun.isSelected)
    }
}
