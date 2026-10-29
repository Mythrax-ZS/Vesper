plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.attr)
    implementation(projects.api.utils.utilsSkills)
    implementation(projects.content.interfaces.bank)
    implementation(projects.content.skills.mining)
    testImplementation(libs.mockk)
    testImplementation(libs.fastutil)
}
