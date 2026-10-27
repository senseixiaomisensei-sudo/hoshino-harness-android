package app.hoshino.harness.ui.nav

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val M2 = "settings/m2"
    const val APPEARANCE = "settings/appearance"
    const val MODELS = "settings/models"
    const val PROVIDER = "settings/provider/{id}"
    fun provider(id: String) = "settings/provider/$id"
    const val PRESETS = "settings/presets"
    const val PRESET = "settings/preset/{id}"
    fun preset(id: String) = "settings/preset/$id"
    const val SECURITY = "settings/security"
    const val SHIZUKU = "settings/shizuku"
    const val PHONE = "settings/phone"
    const val KERNEL = "settings/kernel"
    const val DATA = "settings/data"
    const val CREDENTIALS = "settings/credentials"
    const val ABOUT = "settings/about"
    const val FEEDBACK = "feedback"
    const val PLUGINS = "plugins"
    const val SKILLS = "skills"
    const val MCP = "mcp"
    const val HOOKS = "hooks"
    const val SCHEDULES = "schedules"
    const val GOALS = "goals"
    const val TERMINAL = "terminal"
    const val FILES = "files"
    const val SCREENSHOT = "screenshot"
    const val TRAJECTORY = "trajectory"
    const val INSPECTOR = "inspector"
    const val WEB = "web"
    const val LSP = "lsp"

    /** 新建供应商时的占位 id：new:<catalogId> */
    fun newProvider(catalogId: String) = provider("new:$catalogId")
    const val NEW_PRESET = "new"
}
