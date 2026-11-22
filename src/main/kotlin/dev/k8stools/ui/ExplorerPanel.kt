package dev.k8stools.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBSplitter
import dev.k8stools.ClusterInfo
import dev.k8stools.ContainerInfo
import dev.k8stools.K8sException
import dev.k8stools.K8sHub
import dev.k8stools.NodeInfo
import dev.k8stools.PodInfo
import dev.k8stools.PodsResult
import dev.k8stools.ui
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CompletableFuture
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

private sealed class Item(val key: String) {
    class Group(key: String, val title: String) : Item(key)
    class Node(val n: NodeInfo) : Item("node:${n.name}")
    class Ns(val name: String, val pods: List<PodInfo>) : Item("ns:$name")
    class Pod(val p: PodInfo) : Item("pod:${p.namespace}/${p.name}")
    class Ctr(val p: PodInfo, val c: ContainerInfo) : Item("ctr:${p.namespace}/${p.name}/${c.name}")
}

private val GOOD = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x2E7D32, 0x62B543))
private val WARN = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xB26A00, 0xE0A030))
private val BAD = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xC62828, 0xFF6B68))

private fun podAttr(status: String) = when (status) {
    "Running", "Succeeded", "Completed" -> GOOD
    "Pending", "ContainerCreating", "PodInitializing", "Terminating" -> WARN
    else -> BAD
}

/** Browse nodes, namespaces, pods and containers of the current context. */
class ExplorerPanel(private val project: Project, private val hub: K8sHub) : JPanel(BorderLayout()) {
    private var nodes: List<NodeInfo> = emptyList()
    private var pods: List<PodInfo> = emptyList()
    private var firstLoad = true
    private var updatingCombo = false

    private val allNs = "All namespaces"
    private val nsCombo = JComboBox(arrayOf(allNs))
    private val filter = JBTextField(16).apply { emptyText.text = "Filter pods…" }
    private val summary = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val detailsModel = object : DefaultTableModel(arrayOf("Property", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val details = JBTable(detailsModel)

    private fun setup() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val it = (value as? DefaultMutableTreeNode)?.userObject) {
                    is Item.Group -> { icon = AllIcons.Nodes.Folder; append(it.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES) }
                    is Item.Node -> {
                        icon = AllIcons.Nodes.Module
                        append(it.n.name)
                        append("  " + (if (it.n.ready) "Ready" else "NotReady") + if (it.n.unschedulable) ", cordoned" else "", if (it.n.ready) GOOD else BAD)
                        append("  ${it.n.roles}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is Item.Ns -> {
                        icon = AllIcons.Nodes.Folder
                        append(it.name)
                        val bad = it.pods.count { p -> podAttr(p.status) === BAD }
                        append("  ${it.pods.size} pods", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        if (bad > 0) append("  $bad failing", BAD)
                    }
                    is Item.Pod -> {
                        icon = AllIcons.Nodes.Package
                        append(it.p.name)
                        append("  ${it.p.status}", podAttr(it.p.status))
                        append("  ${it.p.ready}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        if (it.p.restarts > 0) append("  ↻${it.p.restarts}", WARN)
                    }
                    is Item.Ctr -> {
                        icon = AllIcons.Nodes.Variable
                        append(it.c.name)
                        append("  ${it.c.state}", podAttr(it.c.state))
                        append("  ${it.c.image}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
        tree.addTreeSelectionListener { showDetails() }
        details.setShowGrid(false)
        details.columnModel.getColumn(0).apply { preferredWidth = 120; maxWidth = 200 }

        val actions = DefaultActionGroup().apply {
            add(refreshAction)
            addSeparator()
            add(logsAction); add(describeAction); add(copyExecAction); add(deleteAction)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("K8sExplorer", actions, true).apply { targetComponent = tree }
        PopupHandler.installPopupMenu(tree, actions, "K8sExplorerPopup")

        nsCombo.addActionListener { if (!updatingCombo) rebuild() }
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })

        val bar = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(0, 4)
            add(toolbar.component, BorderLayout.WEST)
            add(JPanel().apply { add(nsCombo); add(filter) }, BorderLayout.CENTER)
        }
        val north = JPanel(BorderLayout()).apply { add(bar, BorderLayout.NORTH); add(summary, BorderLayout.SOUTH) }

        val split = JBSplitter(false, 0.55f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = JBScrollPane(details)
        }
        add(north, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)

        hub.addChangeListener { refresh() }
        refresh()
    }

    // ---------- data ----------

    fun refresh() {
        summary.text = "Loading cluster state…"
        val exec = AppExecutorUtil.getAppExecutorService()
        val nodesF = CompletableFuture.supplyAsync({ runCatching { ClusterInfo.nodes() } }, exec)
        val podsF = CompletableFuture.supplyAsync({ runCatching { ClusterInfo.pods() } }, exec)
        CompletableFuture.allOf(nodesF, podsF).whenComplete { _, _ ->
            val n = nodesF.get()
            val p = podsF.get()
            ui { applyLoaded(n, p) }
        }
    }

    private fun applyLoaded(n: Result<List<NodeInfo>>, p: Result<PodsResult>) {
        nodes = n.getOrDefault(emptyList())
        pods = p.getOrNull()?.pods ?: emptyList()
        val errors = listOfNotNull(
            n.exceptionOrNull()?.let { "nodes: ${(it as? K8sException)?.message ?: it.message}" },
            p.exceptionOrNull()?.let { "pods: ${(it as? K8sException)?.message ?: it.message}" },
        )
        val failing = pods.count { podAttr(it.status) === BAD }
        summary.text = when {
            n.isFailure && p.isFailure -> "Cannot reach the cluster - ${errors.first()}"
            else -> "${nodes.size} nodes · ${pods.size} pods · ${pods.map { it.namespace }.distinct().size} namespaces" +
                    (if (failing > 0) " · $failing failing" else "") +
                    (p.getOrNull()?.note?.let { " · $it" } ?: "") +
                    (if (errors.isNotEmpty()) " · ${errors.joinToString("; ")}" else "")
        }

        updatingCombo = true
        val selected = nsCombo.selectedItem as? String
        nsCombo.removeAllItems()
        nsCombo.addItem(allNs)
        pods.map { it.namespace }.distinct().sorted().forEach { nsCombo.addItem(it) }
        nsCombo.selectedItem = selected?.takeIf { s -> (0 until nsCombo.itemCount).any { nsCombo.getItemAt(it) == s } } ?: allNs
        updatingCombo = false
        rebuild()
    }

    // ---------- tree ----------

    private fun keyOf(node: DefaultMutableTreeNode): String =
        node.userObjectPath.filterIsInstance<Item>().joinToString("/") { it.key }

    private fun rebuild() {
        val expanded = (tree.getExpandedDescendants(TreePath(treeModel.root)) ?.toList() ?: emptyList())
            .map { keyOf(it.lastPathComponent as DefaultMutableTreeNode) }.toSet()
        val selectedKey = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.let { keyOf(it) }

        val root = DefaultMutableTreeNode()
        val nodesGroup = DefaultMutableTreeNode(Item.Group("nodes", "Nodes (${nodes.size})"))
        nodes.forEach { nodesGroup.add(DefaultMutableTreeNode(Item.Node(it))) }
        if (nodes.isNotEmpty()) root.add(nodesGroup)

        val ns = nsCombo.selectedItem as? String ?: allNs
        val text = filter.text.trim()
        val visible = pods.filter { (ns == allNs || it.namespace == ns) && (text.isEmpty() || it.name.contains(text, true)) }
        val byNs = visible.groupBy { it.namespace }.toSortedMap()
        val nsGroup = DefaultMutableTreeNode(Item.Group("namespaces", "Namespaces (${byNs.size})"))
        for ((name, list) in byNs) {
            val nsNode = DefaultMutableTreeNode(Item.Ns(name, list))
            for (p in list) {
                val podNode = DefaultMutableTreeNode(Item.Pod(p))
                p.containers.forEach { podNode.add(DefaultMutableTreeNode(Item.Ctr(p, it))) }
                nsNode.add(podNode)
            }
            nsGroup.add(nsNode)
        }
        if (byNs.isNotEmpty()) root.add(nsGroup)

        treeModel.setRoot(root)
        val all = generateSequence(listOf(root)) { level -> level.flatMap { it.children().toList().filterIsInstance<DefaultMutableTreeNode>() }.takeIf { it.isNotEmpty() } }
            .flatten().toList()
        all.forEach { n ->
            val k = keyOf(n)
            val item = n.userObject
            val expand = if (firstLoad) item is Item.Group || (item is Item.Ns && byNs.size <= 3) else k in expanded
            if (expand && !n.isLeaf) tree.expandPath(TreePath(n.path))
            if (k == selectedKey && selectedKey.isNotEmpty()) tree.selectionPath = TreePath(n.path)
        }
        if (nodes.isNotEmpty() || pods.isNotEmpty()) firstLoad = false
        showDetails()
    }

    // ---------- details ----------

    private fun selectedItem(): Item? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Item

    private fun showDetails() {
        detailsModel.rowCount = 0
        val rows: List<Pair<String, String>> = when (val it = selectedItem()) {
            is Item.Node -> {
                val n = it.n
                listOf(
                    "Name" to n.name, "Status" to if (n.ready) "Ready" else "NotReady", "Roles" to n.roles,
                    "Schedulable" to if (n.unschedulable) "No (cordoned)" else "Yes",
                    "Kubelet version" to n.version, "OS image" to n.os, "Kernel" to n.kernel,
                    "Container runtime" to n.runtime, "Architecture" to n.arch, "Internal IP" to n.ip,
                    "CPU" to n.cpu, "Memory" to n.memory, "Max pods" to n.maxPods,
                    "Pods running here" to pods.count { p -> p.node == n.name }.toString(), "Age" to n.age,
                )
            }
            is Item.Ns -> listOf(
                "Namespace" to it.name, "Pods" to it.pods.size.toString(),
                "Running" to it.pods.count { p -> p.status == "Running" }.toString(),
                "Failing" to it.pods.count { p -> podAttr(p.status) === BAD }.toString(),
                "Containers" to it.pods.sumOf { p -> p.containers.size }.toString(),
                "Restarts" to it.pods.sumOf { p -> p.restarts }.toString(),
            )
            is Item.Pod -> podRows(it.p)
            is Item.Ctr -> listOf(
                "Container" to it.c.name, "Image" to it.c.image, "State" to it.c.state,
                "Ready" to it.c.ready.toString(), "Restarts" to it.c.restarts.toString(),
                "Pod" to it.p.name, "Namespace" to it.p.namespace, "Node" to it.p.node,
            )
            is Item.Group -> if (it.key == "nodes") listOf(
                "Nodes" to nodes.size.toString(), "Ready" to nodes.count { n -> n.ready }.toString(),
            ) else listOf(
                "Pods" to pods.size.toString(),
                "Running" to pods.count { p -> p.status == "Running" }.toString(),
                "Failing" to pods.count { p -> podAttr(p.status) === BAD }.toString(),
            )
            null -> emptyList()
        }
        rows.forEach { (k, v) -> detailsModel.addRow(arrayOf(k, v)) }
    }

    private fun podRows(p: PodInfo) = listOf(
        "Pod" to p.name, "Namespace" to p.namespace, "Status" to p.status, "Ready" to p.ready,
        "Restarts" to p.restarts.toString(), "Node" to p.node, "Pod IP" to p.ip, "Age" to p.age,
        "Containers" to p.containers.joinToString(", ") { it.name },
    )

    // ---------- actions ----------

    private fun target(): Triple<PodInfo, String?, Item>? = when (val it = selectedItem()) {
        is Item.Pod -> Triple(it.p, null, it)
        is Item.Ctr -> Triple(it.p, it.c.name, it)
        else -> null
    }

    private abstract inner class ExplorerAction(text: String, desc: String, icon: javax.swing.Icon, private val needs: () -> Boolean) :
        DumbAwareAction(text, desc, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = needs()
        }
    }

    private val refreshAction = object : ExplorerAction("Refresh", "Reload nodes and pods", AllIcons.Actions.Refresh, { true }) {
        override fun actionPerformed(e: AnActionEvent) = refresh()
    }

    private val logsAction = object : ExplorerAction("Show Logs", "kubectl logs --tail=500 (output in the console below)", AllIcons.Actions.ShowAsTree, { target() != null }) {
        override fun actionPerformed(e: AnActionEvent) {
            val (p, c, _) = target() ?: return
            val args = mutableListOf("logs", "--tail=500", "-n", p.namespace, p.name)
            if (c != null) args += listOf("-c", c) else if (p.containers.size > 1) args += listOf("--all-containers=true", "--prefix=true")
            hub.kubectl("kubectl logs ${p.name}", args)
        }
    }

    private val describeAction = object : ExplorerAction("Describe", "kubectl describe", AllIcons.Actions.Show, { selectedItem().let { it is Item.Node || it is Item.Ns || target() != null } }) {
        override fun actionPerformed(e: AnActionEvent) {
            val args = when (val it = selectedItem()) {
                is Item.Node -> listOf("describe", "node", it.n.name)
                is Item.Ns -> listOf("describe", "namespace", it.name)
                else -> target()?.let { (p, _, _) -> listOf("describe", "pod", "-n", p.namespace, p.name) } ?: return
            }
            hub.kubectl("kubectl describe", args)
        }
    }

    private val copyExecAction = object : ExplorerAction("Copy Shell Command", "Copy a kubectl exec command to the clipboard", AllIcons.Actions.Copy, { target() != null }) {
        override fun actionPerformed(e: AnActionEvent) {
            val (p, c, _) = target() ?: return
            val cmd = "kubectl exec -it -n ${p.namespace} ${p.name}" + (c?.let { " -c $it" } ?: "") + " -- sh"
            CopyPasteManager.getInstance().setContents(StringSelection(cmd))
            hub.log("Copied: $cmd")
            hub.notify("Copied: $cmd")
        }
    }

    private val deleteAction = object : ExplorerAction("Delete Pod", "kubectl delete pod (asks for confirmation)", AllIcons.General.Remove, { target() != null }) {
        override fun actionPerformed(e: AnActionEvent) {
            val (p, _, _) = target() ?: return
            hub.kubectl(
                "kubectl delete pod ${p.name}", listOf("delete", "pod", "-n", p.namespace, p.name),
                confirm = "Delete pod ${p.namespace}/${p.name}?", notifyResult = true,
            ) { ui { refresh() } }
        }
    }

    // Must stay last: it uses the action properties declared above.
    init {
        setup()
    }
}
