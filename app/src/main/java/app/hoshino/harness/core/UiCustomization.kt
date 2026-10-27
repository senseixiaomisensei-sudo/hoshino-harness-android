package app.hoshino.harness.core

/** Closed declarative data from a Cordis UI plugin; never contains executable UI code. */
data class UiCustomization(
    val accent: String?=null,
    val bodyScale: Float?=null,
    val codeScale: Float?=null,
    val tagline: String?=null,
    val capsules: List<UiCapsule>?=null,
    val commands: List<UiCommand> = emptyList(),
    val sidebar: List<UiSidebarEntry> = emptyList(),
    val raw: org.json.JSONObject = org.json.JSONObject(),
)
data class UiCapsule(val label:String,val prompt:String)
data class UiCommand(val name:String,val description:String,val prompt:String)
data class UiSidebarEntry(val route:String,val visible:Boolean)

internal object DefaultUiCustomization {
    val value: kotlinx.coroutines.flow.StateFlow<UiCustomization> = kotlinx.coroutines.flow.MutableStateFlow(UiCustomization())
    val error: kotlinx.coroutines.flow.StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)
}
