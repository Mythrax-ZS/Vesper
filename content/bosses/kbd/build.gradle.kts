plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.bosses)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.instances)
    testImplementation(libs.mockk)
    testImplementation(libs.fastutil)
    testImplementation(projects.api.combat.combatFormulas)
}
