package app.hoshino.harness.phone

/**
 * Where an app opened by phone_launch_app ended up (m2.9). The helper always starts it with the agent display as the
 * launch display and never falls back to the main screen, but Android may still bring an existing background task of
 * that app to the front of the display it already lives on (the main screen). That would pull the user's screen away,
 * so it is detected right after the start and moved back to the agent display, or reported as an error.
 */
enum class Placement { OnAgent, LeakedToMain, Pending }

object LaunchPlacement {
    /**
     * @param mainBefore package in front of the main screen before the start
     * @param agentTop / [mainTop] packages in front of the agent display / main screen now
     */
    fun verdict(pkg: String, mainBefore: String?, agentTop: String?, mainTop: String?): Placement = when {
        mainTop == pkg && mainBefore != pkg -> Placement.LeakedToMain
        agentTop == pkg -> Placement.OnAgent
        else -> Placement.Pending
    }
}
