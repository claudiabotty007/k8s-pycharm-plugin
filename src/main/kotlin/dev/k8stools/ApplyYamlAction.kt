package dev.k8stools

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

private fun VirtualFile.isYaml() = !isDirectory && extension?.lowercase() in setOf("yaml", "yml")

class ApplyYamlAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun files(e: AnActionEvent): List<VirtualFile> =
        (e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE)))
            .filter { it.isYaml() && it.isInLocalFileSystem }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && files(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val paths = files(e).map { it.path }
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<K8sHub>().deploy(paths, "", false)
    }
}
