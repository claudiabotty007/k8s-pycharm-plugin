package dev.k8stools.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.k8stools.NewContext
import javax.swing.JComponent

class AddContextDialog(project: Project) : DialogWrapper(project) {
    private val name = JBTextField()
    private val server = JBTextField().apply { emptyText.text = "https://my-cluster.example.com:6443" }
    private val namespace = JBTextField().apply { emptyText.text = "default" }
    private val user = JBTextField().apply { emptyText.text = "optional - existing user, or name for the token" }
    private val token = JBPasswordField()
    private val ca = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor())
    }
    private val insecure = JBCheckBox("Skip TLS verification (insecure)")

    init {
        title = "Add Kubernetes Context"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Context name:", name)
        .addLabeledComponent("API server URL:", server)
        .addLabeledComponent("Namespace:", namespace)
        .addLabeledComponent("User:", user)
        .addLabeledComponent("Bearer token:", token)
        .addLabeledComponent("CA certificate:", ca)
        .addComponent(insecure)
        .panel.apply { preferredSize = java.awt.Dimension(480, preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = name

    override fun doValidate(): ValidationInfo? {
        if (name.text.isBlank()) return ValidationInfo("Enter a context name", name)
        if (!server.text.trim().startsWith("http")) return ValidationInfo("Server must start with http:// or https://", server)
        return null
    }

    fun result() = NewContext(
        name = name.text.trim(),
        server = server.text.trim(),
        namespace = namespace.text.trim(),
        user = user.text.trim(),
        token = String(token.password),
        caFile = ca.text.trim(),
        insecure = insecure.isSelected,
    )
}
