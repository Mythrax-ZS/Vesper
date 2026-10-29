plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.npc)
    implementation(projects.api.repo)
    implementation(projects.api.specials)
    testImplementation(libs.mockk)
    testImplementation(projects.api.combat.combatManager)
    testImplementation(projects.api.combat.combatFormulas)
    testImplementation(projects.api.hitPlugin)
}
