package dev.k8stools

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class K8sConfigurable : SearchableConfigurable {
    private val kubectl = TextFieldWithBrowseButton()
    private val helm = TextFieldWithBrowseButton()
    private val terraform = TextFieldWithBrowseButton()

    init {
        listOf(kubectl, helm, terraform).forEach {
            it.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor())
        }
    }

    override fun getId() = "dev.k8stools.settings"
    override fun getDisplayName() = "Kubernetes Tools"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("kubectl executable:", kubectl)
        .addLabeledComponent("helm executable:", helm)
        .addLabeledComponent("terraform executable:", terraform)
        .addComponent(com.intellij.ui.components.JBLabel("<html><i>Leave empty to use the PATH (terraform also falls back to the JetBrains Terraform plugin's binary).</i></html>"))
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    private val state get() = K8sSettings.getInstance().state

    override fun isModified() =
        kubectl.text != state.kubectlPath || helm.text != state.helmPath || terraform.text != state.terraformPath

    override fun apply() {
        state.kubectlPath = kubectl.text.trim()
        state.helmPath = helm.text.trim()
        state.terraformPath = terraform.text.trim()
    }

    override fun reset() {
        kubectl.text = state.kubectlPath
        helm.text = state.helmPath
        terraform.text = state.terraformPath
    }
}
