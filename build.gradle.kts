// web-bff: the customer web/PWA's same-origin backend (HLD Target AD-2) — session, CSRF,
// the client API of delivery-service under /bff/sdui, and named domain requests.
plugins { id("org.springframework.boot") }

dependencies { implementation(project(":sdui-foundation")) }

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") { workingDir = rootProject.projectDir }
