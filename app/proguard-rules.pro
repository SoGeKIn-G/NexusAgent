# Minification is disabled until M4 — the reasoning layer's serialized types will need
# keep rules that don't exist yet. See app/build.gradle.kts.

# The system instantiates the accessibility service reflectively from the manifest.
-keep class com.nexusagent.agent.perception.NexusAccessibilityService { *; }
