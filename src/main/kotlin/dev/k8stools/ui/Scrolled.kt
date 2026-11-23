package dev.k8stools.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Wraps a tab so it can shrink and scroll. A tabbed pane's minimum height is the largest minimum of any of its tabs,
 * so one tall form would otherwise squeeze the console out of a short tool window. When there is room the content
 * still fills the viewport, exactly as before.
 */
fun scrolled(c: JComponent): JBScrollPane = JBScrollPane(c).apply { border = JBUI.Borders.empty() }
