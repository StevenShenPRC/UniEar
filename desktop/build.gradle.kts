plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies { implementation(project(":core")) }

application {
    mainClass.set("uniear.desktop.DesktopAppKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

distributions {
    main {
        contents {
            into("radio") {
                from("radio.py", "requirements.txt")
            }
        }
    }
}
