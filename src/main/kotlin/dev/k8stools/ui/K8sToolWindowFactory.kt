package dev.k8stools.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.k8stools.K8sHub
import dev.k8stools.Kube
import dev.k8stools.io
import dev.k8stools.ui
import java.awt.BorderLayout
import javax.swing.JPanel

class K8sToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val hub = project.service<K8sHub>()

        val header = JBLabel(" ").apply { border = JBUI.Borders.empty(6, 10) }
        fun reloadHeader() = io {
            val ctx = Kube.currentContext()
            ui {
                header.text = if (ctx.isBlank()) "<html>Current context: <b>none</b></html>"
                else "<html>Current context: <b>$ctx</b></html>"
                header.foreground = if (ctx.isBlank()) JBColor.RED else JBColor.foreground()
            }
        }
        hub.addChangeListener { reloadHeader() }
        reloadHeader()

        val tabs = JBTabbedPane().apply {
            addTab("Contexts", ContextsPanel(project, hub))
            addTab("Explorer", ExplorerPanel(project, hub))
            addTab("Deploy YAML", DeployPanel(project, hub))
            addTab("Terraform", TerraformPanel(project, hub))
            addTab("Helm", HelmPanel(project, hub))
        }
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        val console = hub.console
        val actions = DefaultActionGroup(*console.createConsoleActions())
        val toolbar = ActionManager.getInstance().createActionToolbar("K8sToolsConsole", actions, false)
        toolbar.targetComponent = console.component
        val bottom = JPanel(BorderLayout()).apply {
            add(console.component, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.WEST)
        }

        val splitter = OnePixelSplitter(true, 0.6f).apply {
            firstComponent = top
            secondComponent = bottom
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(splitter, "", false))
    }
}
