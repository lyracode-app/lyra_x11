package com.yukisoffd.lyracode

internal fun x11AgentToolCatalog(): List<AgentToolInfo> = listOf(AgentToolInfo(
    "linux_gui", uiText(com.yukisoffd.lyra.x11.R.string.x11_title),
    uiText(com.yukisoffd.lyra.x11.R.string.x11_tool_description),
))
