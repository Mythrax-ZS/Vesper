plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.invtx)
    implementation(projects.api.player)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.script)
    implementation(projects.content.quest)
    implementation(projects.content.skills.slayer)
    implementation(projects.content.skills.utils)
    testImplementation(projects.api.invStorage)
}
