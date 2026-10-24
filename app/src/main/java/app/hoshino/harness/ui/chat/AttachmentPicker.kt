package app.hoshino.harness.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.hoshino.harness.core.AppMedia
import app.hoshino.harness.core.AttachmentKind
import kotlinx.coroutines.launch

@Composable
fun attachmentPicker(vm:ChatViewModel):(AttachmentKind)->Unit {
    val context=LocalContext.current;val media=remember{AppMedia(context)};val scope=rememberCoroutineScope()
    var camera by remember{mutableStateOf<java.io.File?>(null)}
    fun receive(uri:android.net.Uri?,kind:AttachmentKind) {if(uri!=null)scope.launch{runCatching{media.import(uri,kind)}.onSuccess{vm.attach(it.attachment)}.onFailure{vm.toast(it.message ?: "附件导入失败")}}}
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){receive(it,AttachmentKind.File)}
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){receive(it,AttachmentKind.Image)}
    val take=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->if(ok)camera?.let{receive(media.uri(it),AttachmentKind.Camera)}}
    return {kind->
        if((kind==AttachmentKind.Image || kind==AttachmentKind.Camera) && vm.modelInfo(vm.composer.value.model)?.vision!=true)vm.toast("当前模型未开启视觉，请在模型设置开启")
        else when(kind){
            AttachmentKind.File->file.launch(arrayOf("*/*"))
            AttachmentKind.Image->photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            AttachmentKind.Camera->{val target=media.cameraFile();camera=target;take.launch(media.uri(target))}
            AttachmentKind.WorkspaceFile->vm.toast("输入 @ 搜索工作区文件")
        }
    }
}
